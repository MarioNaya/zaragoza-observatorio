# Estado del proyecto y arranque de sesión

Última actualización: 2026-09-06 (segunda sesión: fase 1 implementada). Este documento es el punto de entrada de cada sesión de trabajo: qué está hecho, qué decisiones rigen, cómo se arranca el entorno y cuál es el siguiente paso concreto. Se actualiza al cerrar cada sesión.

## 1. Dónde estamos

| Fase (SPEC.md §3) | Estado | Evidencia |
|---|---|---|
| Paso 2 de §10: esqueleto | **Hecho** | Spring Boot 4.1.1 + Modulith 2.1.1 + Java 21, PostGIS en Compose y Testcontainers, Flyway, tests de arquitectura |
| Fase 0: spikes S0.1–S0.6 | **Hecho** | Seis clases `@Tag("spike")`, fixtures reales, informes en `docs/spikes/` |
| Paso 4 de §10: revisar la especificación | **Hecho** | `SPEC.md` v0.5; ADR-001..004 |
| Fase 1: `ingestion` + `catalog` | **Hecho en lo esencial** (2026-09-06) | Rama `feat/fase1-ingestion-catalog`, 5 commits; `.\mvnw.cmd verify` en verde (75 tests: unitarios, adaptadores sobre fixtures reales, integración con Testcontainers, calidad de datos); monitor probado contra la API municipal real (ver §5) |
| Fase 1: eje *observado* de la frescura, cruce con el Swagger, `federated` | **Pendiente** | Requiere el spike S1.1 (§4) |
| Fases 2–5 | Pendientes | `geo`, `citizen`, `spending`, `territory`, frontend, `identity`, `workspace` |

Conclusiones que condicionan todo lo demás (fase 0, siguen vigentes):

- **La unidad territorial es la junta municipal o vecinal (29)**, con la sección censal (491) como opción fina. No existen barrios como dato abierto (S0.4).
- **El gasto público no es territorializable** (S0.2, S0.6, ADR-003).
- **La API municipal no honra `If-Modified-Since`** (solo Open311 honra `ETag`); la extensión `.json` es obligatoria; `rows` tope 500 en la sede (S0.5). Todo ello está codificado en `SourceDescriptor` y `ZaragozaHttpClient`.
- **El 60 % del catálogo no es evaluable por periodicidad declarada** (263/436 en la primera instantánea real): el monitor necesita observar el dato, no solo el metadato (S0.1).

## 2. Decisiones vigentes

| ADR | Decisión |
|---|---|
| [ADR-000](decisions/ADR-000-especificacion-inicial.md) | `SPEC.md` es la especificación viva y fundacional |
| [ADR-001](decisions/ADR-001-spring-boot-4.md) | Spring Boot 4.1.x, Modulith 2.1.x vía BOM, Java 21 |
| [ADR-002](decisions/ADR-002-spikes-como-tests-junit.md) | Los spikes son tests JUnit `@Tag("spike")`, fuera del build por defecto (`-Pspikes`) |
| [ADR-003](decisions/ADR-003-contexto-spending.md) | El gasto público es el contexto `spending`, sin entidades territoriales |
| [ADR-004](decisions/ADR-004-eventos-jdbc-resiliencia.md) | Registro de eventos Modulith sobre JDBC con tabla creada por Flyway (`ddl-auto=validate`); Resilience4j core programático; ShedLock aplazado (pool de 1 hilo); `MockRestServiceServer` en lugar de WireMock; springdoc para OpenAPI |

Nombre del proyecto: `observatorio-zaragoza`; groupId y paquete base `es.zaragoza.observatory`. La carpeta local y el repositorio remoto se llaman `zaragoza-observatorio` (<https://github.com/MarioNaya/zaragoza-observatorio>); no importa para el build.

## 3. Cómo arrancar una sesión

1. Arrancar Docker Desktop y comprobar `docker info` (Testcontainers y Compose lo necesitan). Se puede lanzar desde PowerShell: `Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"` y esperar a que `docker info` responda.
2. `.\mvnw.cmd -v` debe decir Java 21 (el `java` del PATH es Java 8; el wrapper usa `JAVA_HOME`).
3. `.\mvnw.cmd verify`: build completo con PostGIS real (~2 min); debe estar en verde antes de tocar nada.
4. Leer `CLAUDE.md` (reglas 1–21) y, para cualquier endpoint, `docs/spikes/README.md` y el informe correspondiente. Nunca escribir un endpoint o campo de memoria.
5. Trabajo en rama por funcionalidad (`feat/…`), commits pequeños, `main` siempre en verde. **La rama `feat/fase1-ingestion-catalog` está pendiente de integrar en `main`** (`git checkout main; git merge --ff-only feat/fase1-ingestion-catalog`).

Comandos útiles:

```powershell
.\mvnw.cmd verify                                   # build + tests (Testcontainers), ~2 min
.\mvnw.cmd test "-Dtest=CatalogIntegrationTests"    # una clase de test
.\mvnw.cmd test -Pspikes "-Dtest=S01*"              # un spike (red real); refresca fixtures
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=8085"   # app + PostGIS vía Compose
```

El puerto 8080 suele estar ocupado en esta máquina por un contenedor phpMyAdmin de otro proyecto: usar `--server.port=8085` (o parar ese contenedor). Con la app arrancada:

- `GET /actuator/health` → `UP`; `GET /actuator/modulith` lista los módulos.
- A los 30 s el planificador ejecuta la ingesta del catálogo real (436 fichas, ~5 s) y el listener toma las instantáneas del día.
- `GET /api/v1/catalog/summary`, `/api/v1/catalog/datasets?size=5&sort=declaredModified,desc`, `/api/v1/catalog/datasets/13`, `/api/v1/catalog/datasets/13/freshness-history`.
- Contrato OpenAPI en `/v3/api-docs`; Swagger UI en `/swagger-ui.html`.
- La base de datos de Compose usa el volumen `postgres-data` (persiste entre arranques); `docker compose -f compose.yaml stop` para pararla.

## 4. Siguiente paso: cerrar la fase 1 (eje observado de la frescura)

Objetivo: que el monitor diga algo del 60 % de fichas no evaluables por periodicidad declarada, observando el dato (SPEC.md §4.6, S0.1 recomendación 4). Orden propuesto:

1. **Spike S1.1** (`S11ObservedFreshnessSpike`, `@Tag("spike")`, informe `docs/spikes/S1.1-frescura-observada.md`): para una muestra representativa de las 436 fichas, medir qué devuelven las distribuciones: (a) ficheros (`text/csv`, `xlsx`, `zip`, `pdf`…): ¿responde `HEAD`?, ¿trae `Last-Modified` (¿en qué formato?), `Content-Length`, `ETag`?; (b) distribuciones `application/api` (69, con `downloadURL` relativo a `www.zaragoza.es`): ¿qué campos de fecha tienen los registros (`lastUpdated`, `modified`, `fecha`…)?, ¿admiten `sort` por ellos?, ¿cuántos registros y cuánto tardan con `rows=1`?; (c) WFS/WMS (92/87): `GetCapabilities` y fechas si las hay. Anotar coste de red por dataset y proponer el método de observación por tipo de distribución. Nada de esto se implementa sin el informe (reglas 1 y 2).
2. Con el informe: `ObservationMethod` real, `ObserveDistribution` (puerto + adaptador en `catalog/infrastructure/zaragoza`), job de muestreo con su propio `IngestionJob` o con `ingestion` bajo demanda, y rellenar `observedLastChange`/`observedRecords`/`observationMethod` en `FreshnessSnapshot`. Actualizar `Caveats.CATALOG`.
3. **Cruce con el Swagger**: ingerir `sede/servicio/catalogo/api.json` (Swagger 2.0, 496 paths, 84 tags; fixture `swagger-api.json`) como segundo `IngestionJob` de `catalog`, guardar `catalog_api_endpoint(tag, path, method, summary)` y enlazar por `apiTag` (60 de 68 casan, S0.1). Exponerlo en el detalle del dataset.
4. **`federated`**: ingerir `catalogo.rdf` o datos.gob.es (fixture `datos-gob-es-page0.json`) y marcar las fichas federadas.
5. Afinar umbrales (`zaragoza.catalog.freshness.*`) con la primera serie de instantáneas reales y decidir la retención de `raw_payload`.
6. Despliegue de una instancia (VPS pequeño o PaaS; SPEC.md §5): perfil `prod`, PostgreSQL gestionado, `springdoc` visible. Antes, decidir si `/actuator/modulith` se expone en producción.

Después de cerrar la fase 1 viene la fase 2 (`geo` y `citizen`, SPEC.md §3), que empieza por el modelo de `District` con geometría PostGIS (S0.4) y la ingesta de `quejas-sugerencias/list.json` (S0.3).

## 5. Comprobación real del 2026-09-06 (11:05 CEST)

Arrancada la aplicación en el puerto 8085 con PostGIS de Compose: `Started ObservatorioZaragozaApplication in 11.54 seconds`; a los 30 s `ingestion … succeeded for data-space:catalogo: 436 records in 1 pages` (5 s de petición real con `fl`); 4 s después `freshness snapshots taken for 436 datasets on 2026-09-06` desde el listener asíncrono (`task-1`). Resumen devuelto por `/api/v1/catalog/summary`:

| Categoría declarada | Fichas |
|---|---|
| `ON_TIME` | 50 |
| `SLIGHT_DELAY` | 8 |
| `DELAYED` | 63 |
| `NOT_UPDATED` | 52 |
| `NOT_EVALUABLE` | 263 |

Con API 69, abiertos 276, explorables 110, con geo 317. Ficha más reciente por `modified`: «indicadores sociodemográficos básicos» (3360, 2026-08-11). Ficha 13 («Arte Público», `P3M`, `modified` 2019-10-23): ratio 27,9 → `NOT_UPDATED`, como estimaba S0.6 (27,9). Los errores llegan como `application/problem+json` y el contrato OpenAPI lista las cuatro rutas.

## 6. Pendientes del usuario

- **Alta como reutilizador en el portal municipal** (SPEC.md §2.2): sigue pendiente. No bloquea nada (toda la API es GET público sin clave), pero conviene hacerlo antes de desplegar, registrar las URL consumidas (catálogo, y en fase 2 quejas y distritos) y aprovechar para pedir inversión por junta y presupuestos participativos como datos abiertos.
- Integrar `feat/fase1-ingestion-catalog` en `main` (fast-forward, o con un pull request en GitHub). El remoto existe desde el 2026-09-06: `origin` = <https://github.com/MarioNaya/zaragoza-observatorio> (`main` y la rama de fase 1 subidas).
- Si se quiere usar el 8080, parar el contenedor phpMyAdmin que lo ocupa.

## 7. Dudas abiertas

Listadas en `SPEC.md` §9. Las que tocan al cierre de la fase 1: método de observación por tipo de distribución (S1.1), umbrales de frescura tras el primer muestreo, retención de `raw_payload` (14 días provisional), exposición de `/actuator/modulith` en producción.

## 8. Mapa del repositorio

```
SPEC.md                         especificación viva (v0.5, ADR-000)
CLAUDE.md                       reglas de trabajo (1–21) y contexto operativo
README.md                       presentación breve, enlaces y API
docs/ESTADO.md                  este documento
docs/arquitectura.md            diagramas Mermaid (flujo de datos, módulos, hexagonal con clases reales)
docs/arquitectura.html          página HTML autónoma de la fase 0 (nombres previos a la implementación)
docs/decisions/                 ADR-000..004
docs/spikes/                    informes S0.1..S0.6 (S0.1 con adenda de fase 1), matriz CSV, índice
pom.xml, compose.yaml           Boot 4.1.1, Modulith 2.1.1, Resilience4j 2.4.0, springdoc 3.1.0, perfil -Pspikes
src/main/resources/application.yaml            spring.http.clients.*, zaragoza.ingestion.*, zaragoza.catalog.*
src/main/resources/db/migration/               V001 postgis · V002 event_publication · V003 ingestion · V004 catalog
src/main/java/es/zaragoza/observatory/
  ClockConfiguration, OpenApiConfiguration     configuración de aplicación (fuera de los módulos)
  shared/                                      DatasetRef, Sources, IngestionRunId, DatasetIngested, ZaragozaTime
  ingestion/                                   API (SourceDescriptor, RawPage, IngestionJob, Ingestion…), domain, application, infrastructure
  catalog/                                     API (CatalogSources), domain, application, infrastructure (zaragoza, persistence, events, web)
src/test/java/es/zaragoza/observatory/
  ModularityTests, HexagonalArchitectureTests, *IntegrationTests, CatalogDataQualityTest, support/Fixtures
  spikes/                                      S01..S06 + support/
src/test/resources/fixtures/zaragoza/          catalog (incluye rows2-fl, rows500-fl y 404 con cabeceras), ocds, open311, geo, inventory
```
