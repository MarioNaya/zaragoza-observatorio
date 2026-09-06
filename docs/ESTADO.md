# Estado del proyecto y arranque de sesión

Última actualización: 2026-09-06, cierre de la tercera sesión (spike S1.1 y eje *observado* de la frescura implementado, probado contra la API real y documentado en ADR-005). Este documento es el punto de entrada de cada sesión de trabajo: qué está hecho, qué decisiones rigen, cómo se arranca el entorno y cuál es el siguiente paso concreto. Se actualiza al cerrar cada sesión.

## 1. Dónde estamos

| Fase (SPEC.md §3) | Estado | Evidencia |
|---|---|---|
| Paso 2 de §10: esqueleto | **Hecho** | Spring Boot 4.1.1 + Modulith 2.1.1 + Java 21, PostGIS en Compose y Testcontainers, Flyway, tests de arquitectura |
| Fase 0: spikes S0.1–S0.6 | **Hecho** | Seis clases `@Tag("spike")`, fixtures reales, informes en `docs/spikes/` |
| Paso 4 de §10: revisar la especificación | **Hecho** | `SPEC.md` v0.6 (revisada al cerrar cada sesión); ADR-001..005 |
| Fase 1: `ingestion` + `catalog` (eje declarado) | **Hecho** (2026-09-06, segunda sesión) | Integrado en `main`; API REST, contrato OpenAPI, instantáneas diarias |
| Higiene del repositorio | **Hecho** (2026-09-06) | Fixtures redactados, historial limpiado con `git filter-repo`; regla 22 |
| Fase 1: **eje observado de la frescura** (S1.1) | **Hecho** (2026-09-06, tercera sesión) | Rama `feat/s11-frescura-observada`: spike `S11ObservedFreshnessSpike` + informe; `DistributionHttpObserver`, `ObserveDatasets`, Flyway V005, API con filtro/orden/resumen por método; `.\mvnw.cmd verify` en verde (103 tests); lote real de 436 fichas en 3 min 17 s (§5); ADR-005. Integrada en `main` (fast-forward) y subida a GitHub el 2026-09-06 |
| Fase 1: cruce con el Swagger, `federated` | Pendiente | §4 |
| Fases 2–5 | Pendientes | `geo`, `citizen`, `spending`, `territory`, frontend, `identity`, `workspace` |

Conclusiones que condicionan todo lo demás (siguen vigentes):

- **La unidad territorial es la junta municipal o vecinal (29)**, con la sección censal (491) como opción fina. No existen barrios como dato abierto (S0.4).
- **El gasto público no es territorializable** (S0.2, S0.6, ADR-003).
- **La API municipal no honra `If-Modified-Since`** (solo Open311 honra `ETag`); la extensión `.json` es obligatoria; `rows` tope 500 en la sede (S0.5). Codificado en `SourceDescriptor` y `ZaragozaHttpClient`.
- **El 60 % del catálogo no es evaluable por periodicidad declarada** (263/436) y **`modified` no describe el dato en ningún sentido** (S1.1): de 149 fichas con fecha observada y `modified`, 107 cambiaron después de lo declarado y 15 antes. El monitor publica los dos ejes por separado y sin categoría observada (ADR-005).
- **El catálogo publica servicios que no existen o no son públicos**: 8 endpoints API con 404 HTML (S1.1; 6 quedan como error en el lote real, los otros caen a otra distribución de la ficha, como «Clavos Topográficos» al WFS), 23 distribuciones WFS/WMS de intranet (`-lan`, 403), 2 índices que redirigen (303), una URL con `https:/` (§5). El monitor los muestra como `observationError`.

## 2. Decisiones vigentes

| ADR | Decisión |
|---|---|
| [ADR-000](decisions/ADR-000-especificacion-inicial.md) | `SPEC.md` es la especificación viva y fundacional |
| [ADR-001](decisions/ADR-001-spring-boot-4.md) | Spring Boot 4.1.x, Modulith 2.1.x vía BOM, Java 21 |
| [ADR-002](decisions/ADR-002-spikes-como-tests-junit.md) | Los spikes son tests JUnit `@Tag("spike")`, fuera del build por defecto (`-Pspikes`) |
| [ADR-003](decisions/ADR-003-contexto-spending.md) | El gasto público es el contexto `spending`, sin entidades territoriales |
| [ADR-004](decisions/ADR-004-eventos-jdbc-resiliencia.md) | Registro de eventos Modulith sobre JDBC con tabla creada por Flyway (`ddl-auto=validate`); Resilience4j core programático; ShedLock aplazado; `MockRestServiceServer`; springdoc |
| [ADR-005](decisions/ADR-005-eje-observado.md) | Eje observado: cuatro métodos (`FILE_HEADERS`, `API_MAX_DATE`, `API_COUNT`, `WFS_HITS`) más `NOT_OBSERVABLE`, lista blanca de campos de fecha, orden API → ficheros → WFS, muestreo diario por lotes sin `IngestionJob`, sin categoría observada; `RestClient` único de la aplicación |

Nombre del proyecto: `observatorio-zaragoza`; groupId y paquete base `es.zaragoza.observatory`. La carpeta local y el repositorio remoto se llaman `zaragoza-observatorio` (<https://github.com/MarioNaya/zaragoza-observatorio>); no importa para el build.

## 3. Cómo arrancar una sesión

1. Arrancar Docker Desktop y comprobar `docker info` (Testcontainers y Compose lo necesitan). Se puede lanzar desde PowerShell: `Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"` y esperar a que `docker info` responda.
2. `.\mvnw.cmd -v` debe decir Java 21 (el `java` del PATH es Java 8; el wrapper usa `JAVA_HOME`).
3. `.\mvnw.cmd verify`: build completo con PostGIS real (~3 min); debe estar en verde antes de tocar nada.
4. Leer `CLAUDE.md` (reglas 1–23) y, para cualquier endpoint, `docs/spikes/README.md` y el informe correspondiente. Nunca escribir un endpoint o campo de memoria.
5. Trabajo en rama por funcionalidad (`feat/…`), commits pequeños, `main` siempre en verde. `main` contiene ya el eje observado (integrado el 2026-09-06); la siguiente sesión parte de `main` y abre la rama siguiente (`feat/swagger-federated` o la que toque). Las ramas `feat/fase1-ingestion-catalog` y `feat/s11-frescura-observada` pueden borrarse en local y en GitHub.

Al cerrar una sesión: `.\mvnw.cmd verify` en verde; actualizar este documento (§1, §4, §5, §6), `SPEC.md` si cambió el modelo o el alcance, y los informes de spikes; pasar la comprobación de datos personales de la regla 22 (`git grep -i -E '\b[0-9]{8}[A-Z]\b|atentamente|set-cookie' -- src/test/resources/fixtures`) y de secretos antes de `git push`; commits descriptivos y push de la rama.

Comandos útiles:

```powershell
.\mvnw.cmd verify                                   # build + tests (Testcontainers), ~3 min
.\mvnw.cmd test "-Dtest=CatalogIntegrationTests"    # una clase de test
.\mvnw.cmd test -Pspikes "-Dtest=S11*"              # un spike (red real); refresca fixtures
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=8085"   # app + PostGIS vía Compose
```

El puerto 8080 suele estar ocupado en esta máquina por un contenedor phpMyAdmin de otro proyecto: usar `--server.port=8085` (o parar ese contenedor). Con la app arrancada:

- `GET /actuator/health` → `UP`; `GET /actuator/modulith` lista los módulos.
- A los 30 s el planificador ejecuta la ingesta del catálogo real (436 fichas, ~5 s) y el listener toma las instantáneas del día. A los 2 min empieza el muestreo observado: lotes de 60 fichas cada 10 min (`zaragoza.catalog.observation.*`); para observar todo el catálogo de una vez, añadir `--zaragoza.catalog.observation.initial-delay=PT50S --zaragoza.catalog.observation.batch-size=500`.
- `GET /api/v1/catalog/summary`, `/api/v1/catalog/datasets?size=5&sort=observedLastChange,desc`, `/api/v1/catalog/datasets?observation=API_MAX_DATE`, `/api/v1/catalog/datasets/55` (ejemplo con eje observado), `/api/v1/catalog/datasets/13/freshness-history`.
- Contrato OpenAPI en `/v3/api-docs`; Swagger UI en `/swagger-ui.html`.
- La base de datos de Compose usa el volumen `postgres-data` (persiste entre arranques; V005 ya aplicada); `docker compose -f compose.yaml stop` para pararla. Para consultas SQL: `docker exec -i zaragoza-observatorio-postgres-1 psql -U observatorio -d observatorio < fichero.sql` (desde Git Bash, la ruta dentro del contenedor se pasa por stdin para evitar la conversión de rutas).

## 4. Siguiente paso: cerrar la fase 1

1. **Cruce con el Swagger**: ingerir `sede/servicio/catalogo/api.json` (Swagger 2.0, 496 paths, 84 tags; fixture `swagger-api.json`) como segundo `IngestionJob` de `catalog`, guardar `catalog_api_endpoint(tag, path, method, summary)` y enlazar por `apiTag` (60 de 68 casan, S0.1). Exponerlo en el detalle del dataset. De paso, comprobar si los 6 endpoints API con 404 y los 2 con 303 tienen otro path válido en su tag (S0.6 ya vio un 8 % de tags cuyo primer path no es el útil) y, si lo tienen, usarlo en `ObservationUrls`.
2. **`federated`**: ingerir `catalogo.rdf` o datos.gob.es (fixture `datos-gob-es-page0.json`) y marcar las fichas federadas.
3. **Primera serie de instantáneas observadas** (necesita la app corriendo varios días o un despliegue): con ella, decidir en una ADR si existe una categoría observada y cómo se cruza con la declarada (ADR-005 §6), afinar los umbrales `zaragoza.catalog.freshness.*` y la retención de `raw_payload`.
4. **Despliegue de una instancia** (VPS pequeño o PaaS; SPEC.md §5): perfil `prod`, PostgreSQL gestionado, `springdoc` visible. Antes, decidir si `/actuator/modulith` se expone en producción.
5. Mejoras menores del eje observado, solo si hacen falta: `apiDefinition` para conocer los campos ordenables sin sondear; `fl=<campo>` en el `sort`; `ETag`/`Content-Length` como firma de cambio para ficheros sin `Last-Modified` (2 de 72).

Después de cerrar la fase 1 viene la fase 2 (`geo` y `citizen`, SPEC.md §3), que empieza por el modelo de `District` con geometría PostGIS (S0.4) y la ingesta de `quejas-sugerencias/list.json` (S0.3), con la decisión previa sobre el texto libre (SPEC.md §9).

## 5. Comprobación real del 2026-09-06 (17:51 CEST)

Arrancada la aplicación en el puerto 8085 con PostGIS de Compose (base de datos de la sesión anterior): Flyway aplicó `V005` sin incidencias; `Started ObservatorioZaragozaApplication in 12.3 seconds`; a los 30 s `ingestion … succeeded for data-space:catalogo: 436 records in 1 pages`; 6 s después `freshness snapshots taken for 436 datasets on 2026-09-06` (mismos recuentos declarados que en la sesión anterior: 50/8/63/52/263). Con `initial-delay=PT50S`, `batch-size=500` y `request-delay=PT0.3S`, el muestreo observó **las 436 fichas en 3 min 17 s** (`observed 436 datasets (240 with a measure)`), sin avisos ni errores en el log.

| Método (última observación) | Fichas | Con fecha | Con recuento | Con error | De ellas no evaluables |
|---|---|---|---|---|---|
| `API_MAX_DATE` | 88 | 88 | 86 | 0 | 29 |
| `WFS_HITS` | 77 | — | 53 | 24 | 69 (22 con error) |
| `FILE_HEADERS` | 72 | 69 | — | 3 | 55 (2 con error) |
| `API_COUNT` | 48 | — | 30 | 18 | 28 (12 con error) |
| `NOT_OBSERVABLE` | 151 | — | — | — | 82 |

- **240 fichas con medida** (55 %), 45 intentos fallidos registrados, 151 sin distribución observable. De las 263 no evaluables por periodicidad, **145 tienen ahora una medida** (82 con fecha observada, 63 solo con recuento).
- Errores por causa: 23 × `HTTP 403 text/html` (WFS/WMS de intranet `-lan`), 6 × `404 HTML` (servicios API inexistentes), 2 × `400 JSON`, 2 × `303` (índices `presupuesto/` y `asociacion/` con `jsessionid`), 2 ficheros dinámicos sin `Last-Modified`, 3 respuestas sin lista de registros (`calidad-aire`, `premios-concursos`, `cultura`), 1 URL con `https:/` en el catálogo, 1 TLS del Catastro. Todos con URL y causa en `observationError`.
- Campo usado en `API_MAX_DATE`: `lastUpdated` 72, `fecha` 9, `pubDate` 2, `publicationDate`/`requested_datetime`/`fechaAlta`/`fechaRegistro`/`creationDate` 1 cada uno.
- Fecha observada frente a `modified` declarado (149 fichas comparables): **107 cambiaron después** de lo declarado, 15 antes y 27 coinciden (±1 día). Año del último cambio observado: 2026 → 58 fichas, 2025 → 23, 2024 → 9, 2023 → 16, y 24 fichas entre 2009 y 2014.
- Ejemplos: «Aparcamientos Públicos» (55) declara `modified` 2022-06-16 y su último `lastUpdated` es 2013-07-08 (41 registros); «Arte Público» (13) declara 2019-10-23 y cambió el 2026-06-07 (471); «Estaciones Bizi» (70) devuelve el instante de la petición (tiempo real); «Registro de Facturas» (1440) 385.878 registros con `fechaRegistro` 2026-09-05; «Clavos Topográficos» (247): el endpoint API da 404 y el monitor cae al WFS (4.313 features); «Ejecución Presupuestaria» (336) queda como intento fallido (303).
- API comprobada: `summary.byObservationMethod`, `datasets?sort=observedLastChange,desc` (primero Bizi, Bomberos, Facturas), `datasets?observation=NOT_OBSERVABLE` (151), detalle de 55 con `observedUrl`, `observationDetail` y `observationError`.

## 6. Pendientes del usuario

- **Alta como reutilizador en el portal municipal** (SPEC.md §2.2): sigue pendiente. No bloquea nada (toda la API es GET público sin clave), pero conviene hacerlo antes de desplegar, registrar las URL consumidas (catálogo y, ahora, una distribución por ficha al día; en fase 2 quejas y distritos) y aprovechar para pedir inversión por junta y presupuestos participativos como datos abiertos.
- **Avisar al ayuntamiento** (Gobierno Abierto, `gobiernoabierto@zaragoza.es`, o su delegado de protección de datos) de que el texto libre de quejas y sugerencias sale por la API sin anonimizar (S0.3 adenda). Y, en el mismo aviso o en `datosabiertos@zaragoza.es`, de lo que el monitor detecta en el catálogo (S1.1, §5): 8 servicios API inexistentes (`clavo-topográfico`, `solar`, `entorno-bic`, `cuenta-bancaria`, `morosidad`, `datos-movilidad` y `transporte-urbano` para dos fichas), 23 distribuciones WFS/WMS de intranet (`-lan`), `puntos-interes` que responde vacío a `sort`, y una URL con `https:/`.
- Remoto: `origin` = <https://github.com/MarioNaya/zaragoza-observatorio>; `main` integra la fase 1 con los dos ejes de frescura (2026-09-06). Pendiente solo decidir si se borran las ramas `feat/*` ya integradas.
- Si se quiere usar el 8080, parar el contenedor phpMyAdmin que lo ocupa.

## 7. Dudas abiertas

Listadas en `SPEC.md` §9. Las que tocan al cierre de la fase 1: categoría observada y cruce declarado/observado (ADR tras la primera serie), umbrales de frescura, retención de `raw_payload` (14 días provisional), exposición de `/actuator/modulith` en producción, y qué hacer con las fichas sin distribución observable (151) más allá de declararlo en `caveats`.

## 8. Mapa del repositorio

```
SPEC.md                         especificación viva (v0.6, ADR-000)
CLAUDE.md                       reglas de trabajo (1–23) y contexto operativo
README.md                       presentación breve, enlaces y API
docs/ESTADO.md                  este documento
docs/arquitectura.md            diagramas Mermaid (flujo de datos, módulos, hexagonal con clases reales)
docs/arquitectura.html          página HTML autónoma de la fase 0 (nombres previos a la implementación)
docs/decisions/                 ADR-000..005
docs/spikes/                    informes S0.1..S0.6 y S1.1, matriz CSV, índice
pom.xml, compose.yaml           Boot 4.1.1, Modulith 2.1.1, Resilience4j 2.4.0, springdoc 3.1.0, perfil -Pspikes
src/main/resources/application.yaml            spring.http.clients.*, zaragoza.http.user-agent, zaragoza.ingestion.*, zaragoza.catalog.* (freshness, observation)
src/main/resources/db/migration/               V001 postgis · V002 event_publication · V003 ingestion · V004 catalog · V005 eje observado
src/main/java/es/zaragoza/observatory/
  ClockConfiguration, HttpClientConfiguration, OpenApiConfiguration   configuración de aplicación (Clock y RestClient únicos)
  shared/                                      DatasetRef, Sources, IngestionRunId, DatasetIngested, ZaragozaTime (parseInstant), HttpDates
  ingestion/                                   API (SourceDescriptor, RawPage, IngestionJob, Ingestion…), domain, application, infrastructure
  catalog/                                     API (CatalogSources); domain (Dataset, FreshnessSnapshot, Observation, ObservationMethod, puertos);
                                               application (RegisterDatasets, TakeFreshnessSnapshots, ObserveDatasets, RecordObservation);
                                               infrastructure (zaragoza: translator, job, DistributionHttpObserver, ObservationUrls; persistence; events; scheduling; web)
src/test/java/es/zaragoza/observatory/
  ModularityTests, HexagonalArchitectureTests, *IntegrationTests, CatalogDataQualityTest, support/Fixtures (bytes, text, headers)
  catalog/support/                             InMemoryDatasets, InMemorySnapshots (dobles de los puertos)
  spikes/                                      S01..S06, S11 + support/ (SpikeFixtures.saveRedacted/saveHeaders, ZaragozaSpikeClient.head/tryGet)
src/test/resources/fixtures/zaragoza/          catalog (rows2-fl, rows500-fl, 404 con cabeceras; observation/: HEAD, rows=1, sort, WFS hits), ocds, open311, geo, inventory
```
