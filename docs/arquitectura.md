# Arquitectura

Versión gráfica de `SPEC.md` §4 tras la fase 0 (2026-09-05). Tres vistas: cómo fluyen los datos desde la API municipal hasta los consumidores, qué módulos hay y qué dependencias se permiten, y cómo se organiza cada módulo por dentro. Los diagramas son Mermaid (se renderizan en GitHub y en la mayoría de IDE); la misma información, con más detalle visual (tres figuras SVG y tablas de módulos y fuentes), está en la página publicada al cerrar la fase 0: <https://claude.ai/code/artifact/b85ed54d-61c1-4133-b6e3-abb5e0743acd> (privada; se comparte desde el menú de la propia página). Copia local completa de esa página: [`docs/arquitectura.html`](arquitectura.html) (abrir con doble clic).

**Cuál manda (2026-09-08)**: este fichero. El HTML y la página publicada quedaron **congelados en la fase 0** y ya no reflejan el código: usan nombres previos a la implementación y marcan `geo` como pendiente cuando está desplegado desde el 2026-09-08. Se conservan como registro de aquel momento, no como documentación vigente. Si alguna vez se regeneran, tienen que salir de este fichero; mientras tanto, no se actualizan a medias, que es peor que no actualizarlos.

## 1. Flujo de datos

```mermaid
flowchart LR
  subgraph AYTO["Fuentes municipales · www.zaragoza.es"]
    direction TB
    CAT["Catálogo de datasets<br/>web/espacio-de-datos/…/catalogo.json<br/>434 datasets (436 hasta 2026-09-08) · rows ≤ 500"]
    SWG["Swagger de la API<br/>sede/servicio/catalogo/api.json<br/>497 operaciones · 84 tags · documento único (S1.2)"]
    FED["Federación datos.gob.es<br/>apidata/catalog/dataset/publisher/L01502973.json<br/>369 datasets · _page/_pageSize ≤ 200 (S1.3)"]
    QYS["Quejas y sugerencias<br/>sede/…/quejas-sugerencias/list.json (+ Open311)<br/>~40.000/año · 50 % con punto"]
    DIS["Juntas y padrón<br/>sede/servicio/distrito*.json<br/>29 polígonos · indicadores por año"]
    OCDS["Contratación OCDS<br/>…/ocds/contracting-process.json<br/>5.720 ocids · sin localización"]
    PRE["Presupuesto y subvenciones<br/>presupuesto/*.json · ayuda-subvencion.json<br/>140 snapshots · sin territorio"]
  end

  subgraph ING["ingestion (fase 1)"]
    direction TB
    SCH["Scheduler<br/>@Scheduled, pool de 1 hilo (ShedLock aplazado, ADR-004)<br/>descubre los IngestionJob de cada módulo"]
    HTTP["ZaragozaHttpClient<br/>RestClient · Resilience4j retry + circuit breaker por dataset · 4 conexiones<br/>.json · rows=500 · start · Last-Modified con CET/CEST"]
    RUN["IngestionRun<br/>inicio, fin, registros, páginas, estado, error"]
    RAW["raw_payload (retención 14 d)<br/>evento DatasetIngested (registro JDBC, V002)"]
    SCH --> HTTP --> RUN --> RAW
  end

  subgraph ACL["Adaptadores anti-corrupción (por módulo)"]
    direction TB
    A_CAT["catalog<br/>catalogo.json (fl) → Dataset<br/>CatalogJsonTranslator · CatalogIngestionJob<br/>api.json → ApiEndpoint (SwaggerJsonTranslator · ApiInventoryIngestionJob, S1.2)<br/>datos.gob.es → FederatedDataset (FederationJsonTranslator · FederationIngestionJob, S1.3)<br/>DatasetIngested → FreshnessSnapshot diaria (eje declarado) · baja de federados no vistos<br/>DistributionHttpObserver: HEAD · rows=1+sort desc · WFS hits (eje observado, S1.1)"]
    A_CIT["citizen<br/>quejas-sugerencias/list.json con fl de 8 campos, SIN texto libre (ADR-012)<br/>ServiceRequestJsonTranslator · dos jobs con marca de agua:<br/>altas por requested_datetime · cierres por updated_datetime (S2.2)<br/>geometry → punto WGS84 → Geo.locateAll (una consulta por página)"]
    A_GEO["geo<br/>distrito.json?srsname=wgs84 → District + Boundary<br/>DistrictJsonTranslator · DistrictsIngestionJob<br/>DatasetIngested → DistrictProfileHttpReader: 29 detalles → idpadron + PopulationRecord<br/>PostgisDistrictLocator: ST_Contains → junta (ADR-011)"]
    A_SPE["spending<br/>release → ContractingProcess, Award, Contract<br/>gasto-corriente → BudgetLine<br/>ayuda-subvencion → Grant"]
  end

  subgraph DB["PostgreSQL + PostGIS · tablas por módulo"]
    direction TB
    T_CAT["catalog: catalog_dataset, catalog_distribution,<br/>catalog_freshness_snapshot (V004, V005 eje observado),<br/>catalog_api_endpoint (V006 inventario del Swagger),<br/>catalog_federated_dataset (V007 federación),<br/>catalog_dataset.delisted_at (V010 baja del listado, ADR-013)"]
    T_CIT["citizen: citizen_service_request (V009)<br/>lon/lat + district_id resuelto + district_declared<br/>sin columna de texto libre (ADR-012)"]
    T_GEO["geo: geo_district (geometry 4326 + GiST),<br/>geo_population_record (V008)<br/>census_section: pendiente"]
    T_SPE["spending: contracting_process, award,<br/>contract, supplier, budget_snapshot,<br/>budget_line, grant (sin geometría)"]
    T_ING["ingestion: ingestion_run, raw_payload (V003)<br/>modulith: event_publication (V002, JDBC)"]
  end

  API["API REST /api/v1 (+ OpenAPI en /v3/api-docs)<br/>lectura pública · paginación · sort explícito<br/>source · ingestedAt · caveats"]
  CONS["Consumidores<br/>frontend Angular (fase 4) · otros reutilizadores<br/>workspace / identity (fase 5)"]

  CAT & SWG & FED & QYS & DIS & OCDS & PRE -- "GET .json" --> HTTP
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

Flujo de una ingesta (SPEC.md §4.5, implementado en fase 1): (1) el scheduler recorre los beans `IngestionJob` que declara cada módulo y ejecuta los vencidos según su `interval()`; (2) `RunIngestion` abre un `IngestionRun` y pide páginas a `ZaragozaHttpClient` con la estrategia del `SourceDescriptor` (`OFFSET` por `start` hasta agotar `totalCount` o recibir página corta; `NONE` una sola petición; `DOCUMENT` un documento único sin parámetros de paginación, el Swagger de la API, S1.2; `PAGE` por número de página con nombres propios, datos.gob.es, S1.3; `If-Modified-Since` no sirve, S0.5); (3) cada página se guarda en `raw_payload` y se entrega al `handle()` del job; (4) el job traduce con su adaptador anti-corrupción y persiste con upsert idempotente por identificador de origen; (5) el cierre del run y la publicación de `DatasetIngested` van en una sola transacción (`CompleteIngestionRun`), y Modulith registra el evento en `event_publication`; (6) los módulos escuchan `DatasetIngested` con `@ApplicationModuleListener`: `catalog`, tras cada ingesta del catálogo, toma la instantánea diaria de frescura de todas las fichas, y `geo`, tras la de las juntas, lee el detalle de cada una para completar el `idpadron` y el padrón (29 peticiones; ADR-011).

Una variante del paso 2 la estrena `citizen` (S2.2): su `SourceDescriptor` **se construye en cada ejecución** con una marca de agua leída de su propia tabla, de modo que la primera ejecución barre el histórico completo y las siguientes piden solo lo nuevo. Son dos jobs sobre el mismo endpoint —uno por eje de fecha— porque un `DatasetRef` es una ejecución periódica con su propio registro, y sin el eje de `updated_datetime` no se vería nunca el cierre de un expediente antiguo.

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
    CATM["catalog (fase 1)<br/>Dataset, FreshnessSnapshot, Observation, ApiEndpoint, FederatedDataset"]
    CIT["citizen (fase 2, implementado)<br/>ServiceRequest sin texto (ADR-012)<br/>DistrictAssignment: RESOLVED / AMBIGUOUS / OUTSIDE / NO_POINT"]
    SPE["spending (fase 3)<br/>OCDS, presupuesto, subvenciones<br/>sin dependencia de geo (ADR-003)"]
  end
  subgraph INFRA["infraestructura y kernels"]
    INGM["ingestion (fase 1)<br/>jobs, cliente HTTP, runs<br/>no conoce dominios"]
    GEO["geo (fase 2, implementado) · shared kernel<br/>District (id + padronId), Boundary, PopulationRecord<br/>API pública Geo: locate/locateAll, districtNames (sinónimos), districts (denominador)"]
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
    APIP["paquete raíz: CatalogSources (CATALOG, API_INVENTORY, FEDERATION)<br/>única superficie visible para otros módulos"]
    subgraph HEX[" "]
      direction LR
      WEB["infrastructure/web<br/>CatalogController + ApiInventoryController + FederationController + CatalogDtos + Caveats + Sorting<br/>GET /api/v1/catalog/datasets · /{id} · /{id}/freshness-history · /summary · /api-tags · /api-endpoints · /federation"]
      APP["application<br/>RegisterDatasets · TakeFreshnessSnapshots<br/>ObserveDatasets · RecordObservation<br/>@Transactional"]
      DOMN["domain<br/>Dataset, Distribution, FreshnessSnapshot, DeclaredFreshness<br/>Observation, ObservationMethod<br/>FreshnessPolicy (umbrales configurables), Periodicity<br/>puertos: DatasetRepository, FreshnessSnapshotRepository, DatasetReadModel, DistributionObserver<br/>sin Spring, sin JPA, sin Jackson, sin infrastructure"]
      ZGZ["infrastructure/zaragoza (ACL)<br/>CatalogJsonTranslator: JSON municipal → Dataset · CatalogIngestionJob<br/>SwaggerJsonTranslator: Swagger 2.0 → ApiEndpoint · ApiInventoryIngestionJob (S1.2)<br/>FederationJsonTranslator: datos.gob.es → FederatedDataset · FederationIngestionJob (S1.3)<br/>DistributionHttpObserver + ObservationUrls implementan DistributionObserver<br/>(RestClient común de la aplicación; scheduling/CatalogObservationScheduler)"]
      EVT["infrastructure/events<br/>CatalogIngestedListener<br/>@ApplicationModuleListener(DatasetIngested)<br/>marca la baja del listado y toma las instantáneas"]
      PER["infrastructure/persistence<br/>JpaDatasetRepository, JpaFreshnessSnapshotRepository,<br/>JpaDatasetReadModel (Specifications) · Flyway V004 a V007 y V010"]
      WEB -- "puerto de lectura" --> DOMN
      ZGZ -- "caso de uso" --> APP
      ZGZ -. "implementa DistributionObserver" .-> DOMN
      EVT -- "caso de uso" --> APP -- "usa" --> DOMN
      PER -. "implementa puertos" .-> DOMN
    end
  end
  INGX["módulo ingestion"] -- "RawPage por página" --> ZGZ
  INGX -. "DatasetIngested" .-> EVT
  PER -- "tablas del módulo" --> PG["PostgreSQL + PostGIS"]
```

ArchUnit (`HexagonalArchitectureTests`): `domain` no importa `org.springframework`, `jakarta.persistence`, `org.hibernate`, `tools.jackson`, `io.github.resilience4j`, `application` ni `infrastructure`; `application` no importa `infrastructure`, JPA, Spring Web ni Spring Data; el paquete raíz de un módulo no depende de `application` ni `infrastructure`. El adaptador anti-corrupción es obligatorio: el dominio nunca refleja la forma del JSON municipal; si el esquema upstream cambia, cambia solo el adaptador (y su test contra el fixture lo delata).

El módulo `ingestion` sigue la misma estructura: raíz (`SourceDescriptor`, `RawPage`, `IngestionJob`, `Ingestion`, `IngestionRunSummary`, `RunStatus`), `domain` (`IngestionRun`, `RawPayload`, `SourceAccessException`, puertos `SourceGateway`, `IngestionRunRepository`, `RawPayloadStore`, `IngestionEventPublisher`), `application` (`RunIngestion`, `CompleteIngestionRun`, `IngestionService`, `PurgeRawPayloads`) e `infrastructure` (`zaragoza/ZaragozaHttpClient` + `LastModifiedParser`, `persistence` JPA con V003, `events`, `scheduling/IngestionScheduler`, `IngestionConfiguration` + `IngestionProperties`).
