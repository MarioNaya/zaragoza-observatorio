# Observatorio de Datos Abiertos de Zaragoza

Plataforma que ingesta los datos abiertos del Ayuntamiento de Zaragoza (API REST v2) en una base de datos propia y los sirve por una API REST para ofrecer: un monitor de frescura del catálogo municipal, un observatorio de quejas y sugerencias por junta, y un observatorio de gasto público (contratación OCDS, presupuesto y subvenciones). Herramienta de análisis, no de conclusiones.

Monolito modular con Spring Boot 4.1 y Spring Modulith, arquitectura hexagonal por módulo, PostgreSQL + PostGIS, Java 21.

Instancia en marcha: <https://observatorio-production-ed20.up.railway.app> ([resumen del catálogo](https://observatorio-production-ed20.up.railway.app/api/v1/catalog/summary) · [contrato OpenAPI](https://observatorio-production-ed20.up.railway.app/swagger-ui.html))

Repositorio: <https://github.com/MarioNaya/zaragoza-observatorio>

## Empezar

- **Estado del proyecto y siguiente paso**: [`docs/ESTADO.md`](docs/ESTADO.md)
- **Especificación viva**: [`SPEC.md`](SPEC.md)
- **Reglas de trabajo**: [`CLAUDE.md`](CLAUDE.md)
- **Arquitectura (diagramas)**: [`docs/arquitectura.md`](docs/arquitectura.md)
- **Decisiones (ADR)**: [`docs/decisions/`](docs/decisions/)
- **Hechos verificados sobre la API municipal**: [`docs/spikes/`](docs/spikes/README.md)
- **Despliegue**: [`docs/despliegue.md`](docs/despliegue.md)

```powershell
.\mvnw.cmd verify              # requiere Docker en marcha (Testcontainers con PostGIS)
.\mvnw.cmd spring-boot:run     # app + PostGIS vía Docker Compose (añadir "-Dspring-boot.run.arguments=--server.port=8085" si el 8080 está ocupado)
.\mvnw.cmd test -Pspikes       # spikes exploratorios contra la API real
docker compose -f compose.prod.yaml up --build -d   # la imagen de producción, en local
```

## API (fase 1: monitor de frescura · fase 2: territorio y ciudadanía)

Lectura pública, sin clave. Toda respuesta lleva `source` (dataset municipal y URL consultada), `ingestedAt` (fin de la última ingesta con éxito) y `caveats`.

| Endpoint | Qué devuelve |
|---|---|
| `GET /api/v1/catalog/datasets` | Fichas del catálogo paginadas (`page`, `size` ≤ 200) y ordenadas (`sort=title,asc`; campos `title`, `id`, `issued`, `declaredModified`, `metadataUpdated`, `declaredRatio`, `observedLastChange`), con filtros `periodicity`, `status`, `hasGeo`, `open`, `hasApi`, `freshness`, `observation`, `federated`, `listed`, `q` |
| `GET /api/v1/catalog/datasets/{id}` | Ficha completa, distribuciones, última instantánea de frescura (eje declarado y eje observado) y `apiEndpoints`: operaciones que el Swagger de la API documenta bajo el tag de la ficha |
| `GET /api/v1/catalog/datasets/{id}/freshness-history` | Histórico de instantáneas, la más reciente primero (`limit`) |
| `GET /api/v1/catalog/summary` | Recuentos por categoría de frescura declarada, por periodicidad y por método de observación, fichas que ya no aparecen en el listado (`notListed`), inventario de endpoints y umbrales vigentes |
| `GET /api/v1/catalog/api-tags` | Cruce catálogo ↔ Swagger: cada tag con sus operaciones documentadas y las fichas que lo declaran (0 operaciones = tag declarado que el Swagger no documenta; sin fichas = fuente sin ficha) |
| `GET /api/v1/catalog/api-endpoints` | Inventario de operaciones del Swagger de la API (`tag`, `q`, `templated`; `sort=document|path|tag`) |
| `GET /api/v1/catalog/federation` | Datasets del publicador municipal en datos.gob.es con `inCatalog` (`inCatalog`, `q`; `sort=id|title`); los que no tienen ficha en el listado municipal son partes de series y colecciones |
| `GET /api/v1/geo/districts` | Las 29 juntas municipales y vecinales con sus **dos numeraciones** (`id` de la API y `padronId` de los datasets de población) y el padrón del último año (filtro `kind`; `sort=id|name|padronId`) |
| `GET /api/v1/geo/districts/{id}` | Una junta con su serie de padrón (2020, 2021, 2022 y 2024: la serie **no** es continua) |
| `GET /api/v1/geo/locate?lon=&lat=` | La junta que contiene un punto WGS84: `RESOLVED`, `AMBIGUOUS` (los polígonos oficiales se solapan en el entorno de Juslibol) u `OUTSIDE` |
| `GET /api/v1/citizen/requests` | Quejas y sugerencias paginadas y ordenadas (`sort=requestedAt\|updatedAt\|id`), con filtros `district` (la junta **resuelta**), `serviceCode`, `status`, `assignment`, `from`, `to`. **Sin el texto de la queja**: no se pide al origen |
| `GET /api/v1/citizen/aggregations?by=district\|category\|month` | Recuentos por junta, categoría o mes, cada grupo con su padrón, el año usado, las quejas por mil habitantes, la **cobertura de punto** del grupo y la mediana de tiempo de respuesta de sus cerradas; la respuesta, con el reparto por estado de asignación y el total sin asignar |
| `GET /api/v1/citizen/summary` | Totales ingeridos, rango temporal, reparto por estado y contraste entre la junta resuelta y la que declara el origen |
| `GET /v3/api-docs` · `/swagger-ui.html` | Contrato OpenAPI 3 y su interfaz |

La frescura *declarada* compara `modified` con `accrualPeriodicity` (ambos declarados por el publicador) contra umbrales configurables; `NOT_EVALUABLE` agrupa las fichas sin periodicidad evaluable o sin `modified` (la mayoría). La frescura *observada* pregunta a diario a una distribución de cada ficha y publica lo que devuelve con su método: `FILE_HEADERS` (`Last-Modified` del fichero, por `HEAD`), `API_MAX_DATE` (valor máximo de un campo de fecha de la API de la sede, pedido con `sort desc`, más `totalCount`), `API_COUNT` (solo `totalCount`), `WFS_HITS` (`numberMatched`) o `NOT_OBSERVABLE`; los intentos fallidos (servicios inexistentes, redirecciones, intranet) quedan registrados con su causa. Ninguno de los dos ejes es un juicio sobre el dato: cada respuesta lleva `caveats`. El Swagger 2.0 de la API municipal se ingiere a diario como inventario de endpoints y se cruza con las fichas por el tag que declaran (S1.2, ADR-006), y el listado del publicador en datos.gob.es se ingiere a diario para marcar las fichas federadas y mostrar los datasets federados que el listado municipal omite (S1.3, ADR-007).

La asignación territorial **no se pide a la API municipal**: su buscador de direcciones acierta la junta el 89,5 % de las veces sin permitir distinguir el acierto del fallo, y los parámetros de consulta espacial que documenta no filtran por proximidad (S2.1). Se resuelve en casa con `ST_Contains` sobre la geometría oficial de las 29 juntas, que coincide con la junta que asigna el ayuntamiento en el 99,69 % de los 1.308 registros contrastados (ADR-011). Un registro sin coordenadas queda **sin asignar** y se cuenta como tal: no se geocodifica por dirección para rellenar el hueco.

**El texto de las quejas no está aquí, y no por descuido.** El ayuntamiento publica `title` y `description` de cada queja sin anonimizar: contienen nombres, firmas y algún DNI. Como la API permite pedir solo los campos que interesan, este observatorio **no los pide**: no se descargan, no se almacenan y no hay columna donde guardarlos (ADR-012). Se descartó redactarlos con expresiones regulares porque los números lo desaconsejaban: en una muestra de 7.000 registros había 2 DNI y 3.353 (47,9 %) fórmulas de firma, es decir, nombres que ninguna expresión regular reconoce. Lo que se pierde con esa decisión —búsqueda por texto, análisis de temas— se declara en vez de disimularlo. El listado abierto tampoco son todas las quejas: tiene 89.432 registros desde 2013, mientras que las estadísticas municipales cuentan del orden de 40.000 incidencias cerradas al año; y la proporción de quejas con coordenadas varía entre el 16 % y el 45 % según el año, así que cada cifra viaja con su cobertura al lado (S2.2).

Datos: Ayuntamiento de Zaragoza, portal de datos abiertos (`https://www.zaragoza.es/sede/portal/datos-abiertos/`), bajo su licencia de reutilización. Cada respuesta de la API propia indica el dataset de origen y la fecha de ingesta.
