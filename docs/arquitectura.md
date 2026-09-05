# Arquitectura

Versión gráfica de `SPEC.md` §4 tras la fase 0 (2026-09-05). Tres vistas: cómo fluyen los datos desde la API municipal hasta los consumidores, qué módulos hay y qué dependencias se permiten, y cómo se organiza cada módulo por dentro. Los diagramas son Mermaid (se renderizan en GitHub y en la mayoría de IDE); la misma información, con más detalle visual (tres figuras SVG y tablas de módulos y fuentes), está en la página publicada al cerrar la fase 0: <https://claude.ai/code/artifact/b85ed54d-61c1-4133-b6e3-abb5e0743acd> (privada; se comparte desde el menú de la propia página). Si cambian los módulos o el flujo, actualizar los dos: este fichero y la página.

## 1. Flujo de datos

```mermaid
flowchart LR
  subgraph AYTO["Fuentes municipales · www.zaragoza.es"]
    direction TB
    CAT["Catálogo de datasets<br/>web/espacio-de-datos/…/catalogo.json<br/>436 datasets · rows ≤ 500"]
    SWG["Swagger de la API<br/>sede/servicio/catalogo/api.json<br/>496 endpoints"]
    QYS["Quejas y sugerencias<br/>sede/…/quejas-sugerencias/list.json (+ Open311)<br/>~40.000/año · 50 % con punto"]
    DIS["Juntas y padrón<br/>sede/servicio/distrito*.json<br/>29 polígonos · indicadores por año"]
    OCDS["Contratación OCDS<br/>…/ocds/contracting-process.json<br/>5.720 ocids · sin localización"]
    PRE["Presupuesto y subvenciones<br/>presupuesto/*.json · ayuda-subvencion.json<br/>140 snapshots · sin territorio"]
  end

  subgraph ING["ingestion (fase 1)"]
    direction TB
    SCH["Scheduler<br/>@Scheduled + ShedLock"]
    HTTP["Cliente HTTP<br/>RestClient · retry · circuit breaker<br/>.json · rows=500 · FIQL/after"]
    RUN["IngestionRun<br/>inicio, fin, registros, estado, error"]
    RAW["raw_payload (retención)<br/>evento DatasetIngested"]
    SCH --> HTTP --> RUN --> RAW
  end

  subgraph ACL["Adaptadores anti-corrupción (por módulo)"]
    direction TB
    A_CAT["catalog<br/>catalogo.json → Dataset<br/>tag Swagger → endpoint<br/>muestreo → FreshnessSnapshot"]
    A_CIT["citizen<br/>list.json → ServiceRequest<br/>geometry → punto WGS84"]
    A_GEO["geo<br/>distrito → District<br/>indicadores → PopulationRecord"]
    A_SPE["spending<br/>release → ContractingProcess, Award, Contract<br/>gasto-corriente → BudgetLine<br/>ayuda-subvencion → Grant"]
  end

  subgraph DB["PostgreSQL + PostGIS · tablas por módulo"]
    direction TB
    T_CAT["catalog: dataset, distribution,<br/>freshness_snapshot"]
    T_CIT["citizen: service_request (point 4326)"]
    T_GEO["geo: district, census_section,<br/>population_record"]
    T_SPE["spending: contracting_process, award,<br/>contract, supplier, budget_snapshot,<br/>budget_line, grant (sin geometría)"]
    T_ING["ingestion: ingestion_run, raw_payload<br/>modulith: event_publication"]
  end

  API["API REST /api/v1<br/>lectura pública · paginación · sort explícito<br/>sourceDataset · ingestedAt · caveats"]
  CONS["Consumidores<br/>frontend Angular (fase 4) · otros reutilizadores<br/>workspace / identity (fase 5)"]

  CAT & SWG & QYS & DIS & OCDS & PRE -- "GET .json" --> HTTP
  RAW -- "payload crudo + metadatos del run" --> A_CAT & A_CIT & A_GEO & A_SPE
  RAW -. "DatasetIngested" .-> A_CAT
  A_CAT -- "upsert idempotente" --> T_CAT
  A_CIT -- "upsert idempotente" --> T_CIT
  A_GEO -- "upsert idempotente" --> T_GEO
  A_SPE -- "upsert idempotente" --> T_SPE
  RUN --> T_ING
  T_CAT & T_CIT & T_GEO & T_SPE -- "read models" --> API
  API -- "JSON" --> CONS
```

Flujo de una ingesta (SPEC.md §4.5): (1) el scheduler dispara un job para un `DatasetRef`; (2) `ingestion` consulta la fuente con la estrategia incremental de esa fuente (FIQL por fecha, `after`, o recarga completa; `If-Modified-Since` no sirve, S0.5); (3) pagina con `rows`/`start` o ventanas, aplica retry y circuit breaker y entrega los payloads crudos al adaptador del módulo; (4) el adaptador traduce y persiste con upsert idempotente por identificador de origen; (5) se registra el `IngestionRun` y se publica `DatasetIngested`; (6) `catalog` escucha todos los `DatasetIngested` para el monitor de frescura.

## 2. Módulos Modulith y dependencias permitidas

```mermaid
flowchart TB
  subgraph F5["fase 5"]
    WS["workspace<br/>SavedQuery, Dashboard<br/>(definiciones, no datos)"]
    ID["identity<br/>User · OAuth2 GitHub/Google<br/>Spring Security vive aquí"]
  end
  subgraph F4["fase 4"]
    TER["territory<br/>ficha por junta · sin tablas<br/>compone citizen + geo"]
  end
  subgraph DOM["dominios"]
    CATM["catalog (fase 1)<br/>Dataset, FreshnessSnapshot"]
    CIT["citizen (fase 2)<br/>ServiceRequest, Category"]
    SPE["spending (fase 3)<br/>OCDS, presupuesto, subvenciones<br/>sin dependencia de geo (ADR-003)"]
  end
  subgraph INFRA["infraestructura y kernels"]
    INGM["ingestion (fase 1)<br/>jobs, cliente HTTP, runs<br/>no conoce dominios"]
    GEO["geo (fase 2) · shared kernel<br/>District, CensusSection, PopulationRecord<br/>locate(point) → District"]
  end
  SH["shared · kernel mínimo<br/>DatasetRef, IngestionRun, UserId, eventos base<br/>todos pueden depender de él; él de nadie"]

  WS -- "usuario autenticado" --> ID
  TER -- "API pública" --> CIT
  TER -- "API pública" --> GEO
  TER -. "solo si hubiera gasto territorial" .-> SPE
  CATM -- "puertos" --> INGM
  CIT -- "puertos" --> INGM
  SPE -- "puertos" --> INGM
  CIT -- "API pública" --> GEO
  CATM & CIT & SPE & INGM & GEO & ID & WS --> SH
```

Reglas (SPEC.md §4.3, verificadas con `ApplicationModules.verify()`): ningún módulo accede a tablas ni clases internas de otro; la comunicación entre dominios es por eventos; `workspace` no depende de ningún dominio; `territory` no tiene tablas y nadie depende de él; `spending` no depende de `geo` porque ninguna fuente de gasto tiene territorio (ADR-003).

## 3. Dentro de un módulo (hexagonal), con `catalog` como ejemplo

```mermaid
flowchart LR
  subgraph MOD["es.zaragoza.observatory.catalog"]
    direction TB
    APIP["CatalogApi + eventos publicados<br/>única superficie visible para otros módulos"]
    subgraph HEX[" "]
      direction LR
      WEB["infrastructure/web<br/>CatalogController<br/>GET /api/v1/catalog/…"]
      APP["application<br/>IngestCatalog · ComputeFreshness<br/>transacciones"]
      DOMN["domain<br/>Dataset, FreshnessSnapshot, Distribution<br/>puertos: CatalogSource, DatasetRepository<br/>FreshnessPolicy (umbrales configurables)<br/>sin Spring, sin JPA, sin infrastructure"]
      ZGZ["infrastructure/zaragoza (ACL)<br/>CatalogApiAdapter implementa CatalogSource<br/>JSON municipal → Dataset"]
      PER["infrastructure/persistence<br/>JpaDatasetRepository implementa DatasetRepository<br/>Flyway db/migration"]
      WEB -- "caso de uso" --> APP -- "usa" --> DOMN
      ZGZ -. "implementa puerto" .-> DOMN
      PER -. "implementa puerto" .-> DOMN
    end
  end
  INGX["módulo ingestion"] -- "payload crudo" --> ZGZ
  PER -- "tablas del módulo" --> PG["PostgreSQL + PostGIS"]
```

ArchUnit (`HexagonalArchitectureTests`): `domain` no importa `org.springframework`, `jakarta.persistence`, `application` ni `infrastructure`; `application` no importa `infrastructure`. El adaptador anti-corrupción es obligatorio: el dominio nunca refleja la forma del JSON municipal; si el esquema upstream cambia, cambia solo el adaptador.
