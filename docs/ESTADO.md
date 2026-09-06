# Estado del proyecto y arranque de sesión

Última actualización: 2026-09-07, cierre de la quinta sesión (despliegue hecho y memoria acotada: ADR-008, ADR-009, `docs/despliegue.md`, `.railway/railway.ts`). **La fase 1 está cerrada y la instancia está en marcha** en <https://observatorio-production-ed20.up.railway.app>, ingiriendo a diario y con un coste medido de ~5,7 $/mes. Lo que queda ya no es escribir código, sino esperar a que se acumule la primera serie de instantáneas (§4). Este documento es el punto de entrada de cada sesión de trabajo: qué está hecho, qué decisiones rigen, cómo se arranca el entorno y cuál es el siguiente paso concreto. Se actualiza al cerrar cada sesión.

## 1. Dónde estamos

| Fase (SPEC.md §3) | Estado | Evidencia |
|---|---|---|
| Paso 2 de §10: esqueleto | **Hecho** | Spring Boot 4.1.1 + Modulith 2.1.1 + Java 21, PostGIS en Compose y Testcontainers, Flyway, tests de arquitectura |
| Fase 0: spikes S0.1–S0.6 | **Hecho** | Seis clases `@Tag("spike")`, fixtures reales, informes en `docs/spikes/` |
| Paso 4 de §10: revisar la especificación | **Hecho** | `SPEC.md` v0.10 (revisada al cerrar cada sesión); ADR-001..009 |
| Fase 1: `ingestion` + `catalog` (eje declarado) | **Hecho** (2026-09-06, segunda sesión) | Integrado en `main`; API REST, contrato OpenAPI, instantáneas diarias |
| Higiene del repositorio | **Hecho** (2026-09-06) | Fixtures redactados, historial limpiado con `git filter-repo`; regla 22 |
| Fase 1: eje observado de la frescura (S1.1) | **Hecho** (2026-09-06, tercera sesión) | `DistributionHttpObserver`, `ObserveDatasets`, Flyway V005, API con filtro/orden/resumen por método; ADR-005 |
| Fase 1: **cruce con el Swagger** (S1.2) | **Hecho** (2026-09-06, cuarta sesión) | Rama `feat/swagger-federated`: spike `S12ApiInventorySpike` + informe; `ResponseShape.DOCUMENT` en `ingestion`; `ApiEndpoint`, `SwaggerJsonTranslator`, `ApiInventoryIngestionJob`, Flyway V006; `apiEndpoints` en el detalle, `GET /catalog/api-tags`, `GET /catalog/api-endpoints`, `apiInventory` en `summary`; la observación prueba `<declarado>/list` (ADR-006). Comprobado contra la API real (§5) |
| Fase 1: **`federated`** (S1.3) | **Hecho** (2026-09-06, cuarta sesión) | Spike `S13FederationSpike` + informe; `Pagination.pages` y `ResponseShape.RESULT_ITEMS` en `ingestion`; `FederatedDataset`, `FederationJsonTranslator`, `FederationIngestionJob`, Flyway V007, baja de lo no visto por evento; `federated`/`federatedUrl`, filtro `federated`, `GET /catalog/federation`, `federation` en `summary` (ADR-007). `.\mvnw.cmd verify` en verde (122 tests); comprobado contra datos.gob.es real (§5) |
| Fase 1: **preparación del despliegue** | **Hecho** (2026-09-06, quinta sesión) | `Dockerfile` multietapa, `.dockerignore`, perfil `prod` completo, `compose.prod.yaml`; ADR-008 y `docs/despliegue.md`. Imagen construida y arrancada en local contra PostGIS: 436 fichas, 497 operaciones, 369 federados desde la imagen de producción (§5) |
| Fase 1: **instancia desplegada** | **Hecho** (2026-09-07, quinta sesión) | Railway, proyecto `zaragoza-observatorio`, región `europe-west4`: servicio `postgis` (imagen `postgis/postgis:17-3.5` con volumen) y servicio `observatorio` desde `main`. Verificado en la URL pública: 436 fichas, 497 operaciones, 369 federados (§5) |
| Fase 1: **memoria y coste acotados** | **Hecho** (2026-09-07, quinta sesión) | ADR-009: topes de JVM explícitos en el `Dockerfile` y `tomcat.threads.max`. Medido en local (367,8 MB estables) y en la instancia: **688 → 356 MB**, ~5,7 $/mes en total (§5) |
| Fase 1: primera serie de instantáneas observadas | En curso: primer barrido completo el 2026-09-07 (436/436); faltan días, no fichas | §4 |
| Fases 2–5 | Pendientes | `geo`, `citizen`, `spending`, `territory`, frontend, `identity`, `workspace` |

Conclusiones que condicionan todo lo demás (siguen vigentes):

- **La unidad territorial es la junta municipal o vecinal (29)**, con la sección censal (491) como opción fina. No existen barrios como dato abierto (S0.4).
- **El gasto público no es territorializable** (S0.2, S0.6, ADR-003).
- **La API municipal no honra `If-Modified-Since`** (solo Open311 honra `ETag`); la extensión `.json` es obligatoria; `rows` tope 500 en la sede (S0.5). Codificado en `SourceDescriptor` y `ZaragozaHttpClient`.
- **El 60 % del catálogo no es evaluable por periodicidad declarada** (263/436) y **`modified` no describe el dato en ningún sentido** (S1.1). El monitor publica los dos ejes por separado y sin categoría observada (ADR-005).
- **El catálogo publica servicios que no existen o no son públicos** (S1.1): 8 endpoints API con 404 HTML, 23 distribuciones WFS/WMS de intranet (`-lan`, 403), índices que redirigen (303), una URL con `https:/`. El monitor los muestra como `observationError`.
- **El Swagger documenta la API pero no cambia lo anterior** (S1.2): 60 de 68 fichas con tag casan; 8 tags declarados no existen en el Swagger; 28 tags documentados (OCDS, juntas, líneas de transporte, emisiones…) no tienen ficha. Solo `<declarado>/list` es una alternativa unívoca (4 fichas); el resto de paths del tag no se usa (ADR-006).
- **El listado `catalogo.json` (436) no es todo el catálogo** (S1.3): datos.gob.es lista 369 datasets del ayuntamiento, 261 con ficha en el listado y **108 sin ficha** que existen en el detalle `catalogo/{id}.json` como partes de series y colecciones (`datasetRelacionado IS_PART_OF`). Al menos 544 fichas publicadas. 175 fichas del listado no están federadas (149 `abierto=N`, 15 abiertas, 11 sin valor). El `modified` federado coincide siempre con el municipal.

## 2. Decisiones vigentes

| ADR | Decisión |
|---|---|
| [ADR-000](decisions/ADR-000-especificacion-inicial.md) | `SPEC.md` es la especificación viva y fundacional |
| [ADR-001](decisions/ADR-001-spring-boot-4.md) | Spring Boot 4.1.x, Modulith 2.1.x vía BOM, Java 21 |
| [ADR-002](decisions/ADR-002-spikes-como-tests-junit.md) | Los spikes son tests JUnit `@Tag("spike")`, fuera del build por defecto (`-Pspikes`) |
| [ADR-003](decisions/ADR-003-contexto-spending.md) | El gasto público es el contexto `spending`, sin entidades territoriales |
| [ADR-004](decisions/ADR-004-eventos-jdbc-resiliencia.md) | Registro de eventos Modulith sobre JDBC con tabla creada por Flyway (`ddl-auto=validate`); Resilience4j core programático; ShedLock aplazado; `MockRestServiceServer`; springdoc |
| [ADR-005](decisions/ADR-005-eje-observado.md) | Eje observado: cuatro métodos (`FILE_HEADERS`, `API_MAX_DATE`, `API_COUNT`, `WFS_HITS`) más `NOT_OBSERVABLE`, lista blanca de campos de fecha, orden API → ficheros → WFS, muestreo diario por lotes sin `IngestionJob`, sin categoría observada; `RestClient` único de la aplicación |
| [ADR-006](decisions/ADR-006-inventario-api.md) | Inventario de endpoints: el Swagger se ingiere como documento único (`DOCUMENT`, sin `rows`/`start`) y se sincroniza entero en `catalog_api_endpoint`; cruce por `apiTag` al leer, sin tabla de enlace; la observación solo usa `<declarado>/list` |
| [ADR-007](decisions/ADR-007-federacion.md) | Federación: datos.gob.es paginado por número (`_page`/`_pageSize` ≤ 200, `RESULT_ITEMS`), tabla propia `catalog_federated_dataset` con upsert por página y baja de lo no visto al completar la ingesta (evento); `federated` resuelto al leer por `sourceId`; los 108 federados sin ficha se conservan; las partes de series no se ingieren hasta spike y ADR |
| [ADR-008](decisions/ADR-008-despliegue.md) | Despliegue: imagen propia (`Dockerfile` de la raíz, JRE 21 no root, capas de Boot; los tests no corren en el builder) sobre Railway con la imagen PostGIS `postgis/postgis:17-3.5`; conexión por variables `PG*` sin valor por defecto; una sola réplica (sin ShedLock); en `prod` actuator solo `health`/`info` (**sin `modulith`**) y springdoc visible; infraestructura declarada en `.railway/railway.ts` (IaC, sin secretos), no en el panel ni en el deprecado `railway.json` |
| [ADR-009](decisions/ADR-009-memoria-y-coste.md) | El coste se paga en RAM residente (10 $/GB/mes), no en peticiones: topes de JVM **explícitos** (`-Xmx256m`, `UseSerialGC`, `TieredStopAtLevel=1`) en vez de `MaxRAMPercentage`, que con el límite de 8 GB del plan dejaba el proceso en ~690 MB; medido, baja a 367,8 MB en local y **356 MB** en la instancia. **Serverless no se usa**: el planificador es el producto. Filtrar bots no reduce la factura (sin coste por petición, egress 0) |

Nombre del proyecto: `observatorio-zaragoza`; groupId y paquete base `es.zaragoza.observatory`. La carpeta local y el repositorio remoto se llaman `zaragoza-observatorio` (<https://github.com/MarioNaya/zaragoza-observatorio>); no importa para el build.

## 3. Cómo arrancar una sesión

1. Arrancar Docker Desktop y comprobar `docker info` (Testcontainers y Compose lo necesitan). Se puede lanzar desde PowerShell: `Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"` y esperar a que `docker info` responda.
2. `.\mvnw.cmd -v` debe decir Java 21 (el `java` del PATH es Java 8; el wrapper usa `JAVA_HOME`).
3. `.\mvnw.cmd verify`: build completo con PostGIS real (~1,5 min); debe estar en verde antes de tocar nada.
4. Leer `CLAUDE.md` (reglas 1–27) y, para cualquier endpoint, `docs/spikes/README.md` y el informe correspondiente. Nunca escribir un endpoint o campo de memoria.
5. **`main` se despliega solo**: el servicio de Railway construye desde esa rama, así que un push a `main` redespliega la instancia. Razón de más para no romperla. Estado del despliegue: `railway status`, `railway logs --service observatorio --deployment` (antes, en PowerShell: `$env:_ = "$env:APPDATA\npm\node_modules\@railway\cli\bin\railway.exe"`, ver `CLAUDE.md`).
6. Trabajo en rama por funcionalidad (`feat/…`), commits pequeños, `main` siempre en verde. `main` integra toda la fase 1 y el despliegue (2026-09-07). Las ramas `feat/fase1-ingestion-catalog`, `feat/s11-frescura-observada`, `feat/swagger-federated` y `feat/despliegue` pueden borrarse en local y en GitHub.

Al cerrar una sesión: `.\mvnw.cmd verify` en verde; actualizar este documento (§1, §4, §5, §6), `SPEC.md` si cambió el modelo o el alcance, y los informes de spikes; pasar la comprobación de datos personales de la regla 22 (`git grep -i -E '\b[0-9]{8}[A-Z]\b|atentamente|set-cookie' -- src/test/resources/fixtures`) y de secretos antes de `git push`; commits descriptivos y push de la rama.

Comandos útiles:

```powershell
.\mvnw.cmd verify                                   # build + tests (Testcontainers), ~1,5 min
.\mvnw.cmd test "-Dtest=CatalogIntegrationTests"    # una clase de test
.\mvnw.cmd test -Pspikes "-Dtest=S13*"              # un spike (red real); refresca fixtures
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=8085"   # app + PostGIS vía Compose
```

El puerto 8080 suele estar ocupado en esta máquina por un contenedor phpMyAdmin de otro proyecto: usar `--server.port=8085` (o parar ese contenedor). No lanzar otro `mvnw` (tests, spikes) con la app arrancada: comparten `target/`. Para parar la app: `Get-NetTCPConnection -LocalPort 8085 -State Listen | Select -Expand OwningProcess | % { Stop-Process -Id $_ -Force }` y después `docker compose -f compose.yaml stop`. Con la app arrancada:

- `GET /actuator/health` → `UP`; `GET /actuator/modulith` lista los módulos.
- A los 30 s el planificador ejecuta los tres jobs vencidos de `catalog`: el catálogo (436 fichas, ~5 s), el Swagger (`sede:catalogo/api`, 1 documento, ~2 s) y la federación (`datos-gob-es:publisher/L01502973`, 369 datasets en 2 páginas, ~5 s); el listener toma las instantáneas del día y da de baja los federados no vistos. A los 2 min empieza el muestreo observado: lotes de 60 fichas cada 10 min (`zaragoza.catalog.observation.*`); para observar todo el catálogo de una vez, añadir `--zaragoza.catalog.observation.initial-delay=PT50S --zaragoza.catalog.observation.batch-size=500 --zaragoza.catalog.observation.request-delay=PT0.3S` (3 min 20 s); para forzar la reobservación de fichas ya observadas hoy, `--zaragoza.catalog.observation.interval=PT1H`.
- `GET /api/v1/catalog/summary` (incluye `apiInventory` y `federation`), `/api/v1/catalog/datasets?size=5&sort=observedLastChange,desc`, `/api/v1/catalog/datasets?federated=false&open=true`, `/api/v1/catalog/datasets/132` (ficha observada por `asociacion/list`, con `apiEndpoints`), `/api/v1/catalog/api-tags`, `/api/v1/catalog/api-endpoints?tag=Urbanismo:%20Clavos%20Topograficos`, `/api/v1/catalog/federation?inCatalog=false`.
- Contrato OpenAPI en `/v3/api-docs`; Swagger UI en `/swagger-ui.html`.
- La base de datos de Compose usa el volumen `postgres-data` (persiste entre arranques; V007 ya aplicada); `docker compose -f compose.yaml stop` para pararla. Para consultas SQL: `docker exec -i zaragoza-observatorio-postgres-1 psql -U observatorio -d observatorio < fichero.sql` (desde Git Bash, la ruta dentro del contenedor se pasa por stdin para evitar la conversión de rutas).

## 4. Siguiente paso: lo que queda de la fase 1 y la fase 2

La fase 1 está completa y desplegada. Lo único pendiente de ella es tiempo:

1. **Primera serie de instantáneas observadas.** El **primer barrido completo ya terminó el 2026-09-07 (436 de 436 fichas observadas)**; a partir de ahí la instancia reobserva cada ficha una vez al día, así que lo que falta es acumular *días*, no fichas: una sola foto no es una serie. Con dos o tres semanas habrá con qué decidir. Con ella: decidir en una ADR si existe una categoría observada y cómo se cruza con la declarada (ADR-005 §6), afinar los umbrales `zaragoza.catalog.freshness.*` y la retención de `raw_payload`. También dirá si el Swagger y la federación cambian (hoy no hay marca de cambio en ninguna de las dos fuentes; `firstSeenAt`/`lastSeenAt` son la única serie). **Al retomar, mirar la serie antes que nada**: es el dato que llevamos toda la fase 1 esperando.
2. **Limpieza pendiente en el volumen de producción**: el clúster de PostgreSQL 18 que Railway inicializó por defecto sigue en `/var/lib/postgresql/data/pgdata`, inservible pero intacto (ADR-008 §8). Se conservó por prudencia; se puede borrar cuando conste que la instancia va bien.
3. **Vigilar el coste y la memoria** (ADR-009): `railway metrics --service observatorio`. La referencia es **~356 MB**; si sube de forma sostenida por encima de ~400 MB es una regresión, y lo primero que hay que mirar es si alguien tocó `JAVA_TOOL_OPTIONS` en el `Dockerfile`. Ojo: el límite de gasto de Railway es **de la cuenta, no del proyecto**, y hay otra aplicación en la misma cuenta.
4. **Primer despliegue con migración nueva**: `main` se despliega solo (dos redespliegues ya comprobados el 2026-09-07, ambos con los datos intactos), pero todavía no se ha desplegado un cambio que traiga una migración Flyway. Con `ddl-auto=validate`, un fallo de ese tipo aborta el arranque, no se manifiesta en caliente: conviene estar delante la primera vez.

Mejoras menores, solo si hacen falta: `apiDefinition`/`parameters` del Swagger para conocer los campos ordenables sin sondear; `fl=<campo>` en el `sort`; `ETag`/`Content-Length` como firma de cambio para ficheros sin `Last-Modified` (2 de 72); `produces` del Swagger para saber qué endpoints tienen `application/geo+json`.

Después viene la **fase 2** (`geo` y `citizen`, SPEC.md §3), que empieza por el modelo de `District` con geometría PostGIS (S0.4) y la ingesta de `quejas-sugerencias/list.json` (S0.3), con la decisión previa sobre el texto libre (SPEC.md §9). Y, con spike propio, la **ingesta de las partes de series y colecciones** que el listado `catalogo.json` omite (SPEC.md §9, S1.3): al menos 108 fichas federadas solo alcanzables por `catalogo/{id}.json`.

## 5. Comprobaciones reales (19:01, 22:27, 23:12 CEST del 2026-09-06 y 00:26 del 2026-09-07)

**00:26 del 2026-09-07 (quinta sesión, instancia desplegada).** <https://observatorio-production-ed20.up.railway.app>. `health` UP; `/actuator/modulith` **404**; `/v3/api-docs` con OpenAPI 3.1.0 y 7 rutas; `summary` con **436 fichas, 497 operaciones en 84 tags y 369 federados** (261 con ficha, 108 sin ella), `ingestedAt` 2026-09-06T22:26:43Z. El muestreo observado arrancó con su primer lote de 60 fichas (`FILE_HEADERS` 11, `API_MAX_DATE` 17, `API_COUNT` 15, `WFS_HITS` 1, `NOT_OBSERVABLE` 16; 376 sin observar). Las mismas cifras que en local, ahora desde la nube.

**Memoria, el mismo día (ADR-009).** Con la instancia arriba y sin uso real (10 peticiones en una hora, egress 0 MB), `railway metrics` daba **688 MB** en `observatorio` y ~125 MB de media en `postgis`. La causa era `-XX:MaxRAMPercentage=75.0` en el `Dockerfile`: Railway anuncia el límite del plan (8 GB), así que la JVM se creía con derecho a ~6 GB de heap. Con topes explícitos, medido primero en local sobre una ingesta completa más un lote de observación (**367,8 MB estables** durante siete minutos, sin OOM) y después en la instancia: **356 MB**. El proyecto queda en ~5,7 $/mes (3,6 la app + 1,25 `postgis` + 0,75 el volumen de 5 GB).

El despliegue costó tres intentos, y los dos fallos están documentados en ADR-008 §8 porque volverían a morder: (1) declarar la base con `database(...)` e imagen propia genera un bucle de borrar y recrear —hay que declararla como `service` con `image(...)`—; y (2) Railway inicializa el volumen con **Postgres 18**, sobre el que PostgreSQL 17 no arranca (`unrecognized configuration parameter "autovacuum_worker_slots"`), así que `PGDATA` va a un directorio propio.

**23:12 (quinta sesión, imagen de producción).** `docker compose -f compose.prod.yaml up --build` con PostGIS efímero y proyecto aislado: imagen construida desde cero con el wrapper del proyecto; Flyway aplicó las 7 migraciones incluida la extensión PostGIS; la aplicación arrancó en 14,6 s y respondió `health=UP` a los 8 s. Comprobado dentro del contenedor: proceso como `uid=10001(observatory)` (no root) y `TZ=Europe/Madrid`. `/actuator` expone solo `health` e `info`; **`/actuator/modulith` devuelve 404** (ADR-008 §6); `/v3/api-docs` sirve OpenAPI 3.1.0 con 7 rutas; los logs salen en ECS JSON. A los 30 s los tres jobs ingirieron contra las fuentes reales desde la imagen de producción: **436 fichas, 497 operaciones en 84 tags y 369 federados (261 con ficha, 108 sin ella)**, las mismas cifras que los dos arranques anteriores. Se paró antes de los 2 min para no lanzar el muestreo observado contra la API municipal sin necesidad.

Cuarta sesión, aplicación en el puerto 8085 con PostGIS de Compose (base de datos de las sesiones anteriores). Dos arranques:

**19:01 (Swagger y observación).** Flyway aplicó `V006` sin incidencias; a los 30 s `ingestion … succeeded for sede:catalogo/api: 1 records in 1 pages` (1,8 s, 1,3 MB) y el listener registró el documento; el catálogo no se reingirió (última ingesta a las 17:51, intervalo 6 h). API: `summary.apiInventory` = 497 operaciones, 84 tags, 68 fichas con tag, 60 documentadas, 28 tags sin ficha; `api-tags` devuelve 92 entradas (84 del Swagger más los 8 tags declarados sin documentar: Convenios, Cuentas Bancarias, Facturas, Periodo Medio de Pago, Entornos BIC, Puntos wifi, Solares, Mapas colaborativos) y los 3 tags con varias fichas (Agenda Zaragoza [282, 2700], Presupuestos [336, 2200, 2201], Transporte urbano [327, 335]); el detalle de la 13 lista sus 10 operaciones con `tagDocumented=true`, el de la 79 (Puntos WIFI) `tagDocumented=false`. Con `initial-delay=PT50S`, `batch-size=500`, `request-delay=PT0.3S` e `interval=PT1H`, el muestreo reobservó **las 436 fichas en 3 min 20 s**: `observed 436 datasets (243 with a measure)` (antes 240).

| Método (última observación) | Fichas | Con fecha | Con recuento | Con error |
|---|---|---|---|---|
| `API_MAX_DATE` | 91 (+3) | 91 | 89 | 0 |
| `API_COUNT` | 46 | — | 31 | 15 |
| `FILE_HEADERS` | 72 | 69 | — | 3 |
| `WFS_HITS` | 76 (−1) | — | 52 | 24 |
| `NOT_OBSERVABLE` | 151 | — | — | — |

- La regla `<declarado>/list` (ADR-006) actuó en las cuatro fichas previstas: «Censo de Asociaciones» (132) pasó de 303 a `API_MAX_DATE` por `asociacion/list` (2.823 registros, `creationDate` 2026-07-27); «Clavos Topográficos» (247) pasó de `WFS_HITS` a `API_MAX_DATE` por `clavo-topografico/list` (4.357, `lastUpdated` 2017-12-29); «Premios y Concursos» (1760) de objeto sin lista a `API_MAX_DATE` por `premios-concursos/list` (32, `lastUpdated` 2026-08-12); «Artistas y Creadores» (1920) de «respuesta no JSON» a `API_COUNT` por `artista-creador/list` (759, sin campo de fecha admitido).
- 42 intentos fallidos (antes 45): 23 × 403 de intranet, 6 × 404 HTML, 3 × 303 (`aplicacion`, `catalogo`, `presupuesto`), 2 × 400, 2 ficheros sin `Last-Modified`, 3 objetos sin lista (`convenios`, `cultura`, `calidad-aire`), 1 URL `https:/`, 1 TLS del Catastro, 1 respuesta no JSON.

**22:27 (federación).** Flyway aplicó `V007`; a los 30 s `ingestion … succeeded for datos-gob-es:publisher/L01502973: 369 records in 2 pages` (5 s) y el listener: `federation run … listed 369 datasets; 0 no longer federated`. API: `summary.federation` = 369 federados, 261 con ficha, 108 sin ficha, 175 fichas sin federar; `federation?inCatalog=false` devuelve los 108 (279 «Vías», 307, 1880 «Mapa Estratégico de Ruido 2016», 2720, 3060 «Portalero», … 6201 «Carril Bici 2018», 6202, 6240 «Calidad del aire: Histórico completo»); «Callejero» (22) `federated=true` con su URL en datos.gob.es. En base de datos, las 175 no federadas se reparten en 149 `is_open=false`, 15 `true` y 11 `null`, como en el spike; y de los padres declarados por las partes de series, 22 «Callejero» y 131 «Calidad del Aire» están en el listado y federados, mientras que 16 «Carril Bici» y 2940 «Temperatura Ambiental e Islas de calor» no están en ninguna de las dos fuentes (la jerarquía tiene fichas que nadie lista).

## 6. Pendientes del usuario

- **Vigilar el coste de Railway**: hay dos servicios y un volumen de 5 GB corriendo de forma continua. Conviene mirar el consumo en el panel los primeros días y decidir si compensa.
- **Reiniciar Claude Code** para que registre el MCP de Railway, ya configurado (`C:\Users\mario\.claude.json`). No hace falta para desplegar —la CLI basta— pero el instalador lo avisa.
- **Alta como reutilizador en el portal municipal** (SPEC.md §2.2): sigue pendiente. No bloquea nada (toda la API es GET público sin clave), pero conviene hacerlo antes de desplegar, registrar las URL consumidas (catálogo, Swagger, una distribución por ficha al día; en fase 2 quejas y distritos) y aprovechar para pedir inversión por junta y presupuestos participativos como datos abiertos. datos.gob.es no requiere alta.
- **Avisar al ayuntamiento** (Gobierno Abierto, `gobiernoabierto@zaragoza.es`, o su delegado de protección de datos) de que el texto libre de quejas y sugerencias sale por la API sin anonimizar (S0.3 adenda). Y, en el mismo aviso o en `datosabiertos@zaragoza.es` (el contacto que publica el Swagger), de lo que el monitor detecta en el catálogo: 8 servicios API inexistentes (S1.1), 23 distribuciones WFS/WMS de intranet (`-lan`), `puntos-interes` que responde vacío a `sort`, una URL con `https:/`, 8 tags declarados que el Swagger no documenta, la tilde de `clavo-topográfico`, los 4 índices con `jsessionid` declarados como endpoint (S1.2), las 15 fichas abiertas sin federar en datos.gob.es y el hecho de que `catalogo.json` no devuelve las partes de series (S1.3).
- Remoto: `origin` = <https://github.com/MarioNaya/zaragoza-observatorio>; `main` integra la fase 1 completa (2026-09-06). Pendiente solo decidir si se borran las ramas `feat/*` ya integradas.
- Si se quiere usar el 8080, parar el contenedor phpMyAdmin que lo ocupa.

## 7. Dudas abiertas

Listadas en `SPEC.md` §9. Las que tocan al cierre de la fase 1: categoría observada y cruce declarado/observado (ADR tras la primera serie), umbrales de frescura, retención de `raw_payload` (14 días provisional), qué hacer con las fichas sin distribución observable (151) más allá de declararlo en `caveats`, la ingesta de las partes de series y colecciones que el listado omite (108 federadas; fase 2 con spike), la **imagen nativa con GraalVM** (bajaría de ~356 MB a 80–150 MB; aplazada en ADR-009) y la **limpieza del clúster de Postgres 18 que quedó en el volumen**. La exposición de `/actuator/modulith` en producción queda **resuelta**: no se expone (ADR-008 §6).

## 8. Mapa del repositorio

```
SPEC.md                         especificación viva (v0.9, ADR-000)
CLAUDE.md                       reglas de trabajo (1–27) y contexto operativo
README.md                       presentación breve, enlaces y API
docs/ESTADO.md                  este documento
docs/despliegue.md              runbook de despliegue en Railway (IaC, variables, comprobaciones)
.railway/railway.ts             infraestructura en código: base de datos PostGIS y servicio de la app (ADR-008)
package.json, package-lock.json  única dependencia: el SDK `railway` que importa .railway/railway.ts
docs/arquitectura.md            diagramas Mermaid (flujo de datos, módulos, hexagonal con clases reales)
docs/arquitectura.html          página HTML autónoma de la fase 0 (nombres previos a la implementación)
docs/decisions/                 ADR-000..009
docs/spikes/                    informes S0.1..S0.6 y S1.1..S1.3, matriz CSV, índice
pom.xml, compose.yaml           Boot 4.1.1, Modulith 2.1.1, Resilience4j 2.4.0, springdoc 3.1.0, perfil -Pspikes
Dockerfile, .dockerignore       imagen de producción multietapa (JDK 21 -> JRE 21, no root, capas de Boot); ADR-008
compose.prod.yaml               prueba local de esa imagen con el perfil prod (proyecto y base de datos propios)
src/main/resources/application.yaml            spring.http.clients.*, zaragoza.http.user-agent, zaragoza.ingestion.*, zaragoza.catalog.* (freshness, observation, api-inventory, federation)
src/main/resources/db/migration/               V001 postgis · V002 event_publication · V003 ingestion · V004 catalog · V005 eje observado · V006 inventario Swagger · V007 federación
src/main/java/es/zaragoza/observatory/
  ClockConfiguration, HttpClientConfiguration, OpenApiConfiguration   configuración de aplicación (Clock y RestClient únicos)
  shared/                                      DatasetRef, Sources (data-space, sede, ocds, open311, datos-gob-es), IngestionRunId, DatasetIngested, ZaragozaTime, HttpDates
  ingestion/                                   API (SourceDescriptor con Pagination NONE/OFFSET/PAGE y ResponseShape ENVELOPE/ARRAY/DOCUMENT/RESULT_ITEMS, RawPage, IngestionJob, Ingestion…), domain, application, infrastructure
  catalog/                                     API (CatalogSources: CATALOG, API_INVENTORY, FEDERATION); domain (Dataset, FreshnessSnapshot, Observation, ObservationMethod, ApiEndpoint, FederatedDataset, puertos y read models);
                                               application (RegisterDatasets, TakeFreshnessSnapshots, ObserveDatasets, RecordObservation, RegisterApiEndpoints, RegisterFederatedDatasets);
                                               infrastructure (zaragoza: traductores, jobs, DistributionHttpObserver, ObservationUrls; persistence; events; scheduling; web: CatalogController, ApiInventoryController, FederationController, Sorting, Caveats)
src/test/java/es/zaragoza/observatory/
  ModularityTests, HexagonalArchitectureTests, *IntegrationTests, CatalogDataQualityTest, support/Fixtures (bytes, text, headers)
  catalog/support/                             InMemoryDatasets, InMemorySnapshots, InMemoryApiEndpoints (dobles de los puertos)
  spikes/                                      S01..S06, S11, S12, S13 + support/ (SpikeFixtures.saveRedacted/saveHeaders, ZaragozaSpikeClient.head/tryGet)
src/test/resources/fixtures/zaragoza/          catalog (rows2-fl, rows500-fl, 404 con cabeceras; swagger-api.json + .headers; datos-gob-es page0/last/beyond; observation/: HEAD, rows=1, sort, WFS hits, /list), ocds, open311, geo, inventory
```
