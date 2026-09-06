# Spikes

Cada spike responde una incógnita de `SPEC.md` §3 con datos reales de la API municipal. Su código es un test JUnit `@Tag("spike")` (ver ADR-002) y su resultado es un informe en esta carpeta. **Lo que aquí se documenta es la única fuente válida de endpoints, campos y formatos para el resto del proyecto.**

Ejecución: `.\mvnw.cmd test -Pspikes` (todos) o `.\mvnw.cmd test -Pspikes "-Dtest=S01*"` (uno). Requieren red; no forman parte del build por defecto.

## Índice

| Spike | Pregunta | Clase | Informe | Estado |
|---|---|---|---|---|
| S0.1 | Estructura y fechas del catálogo de datasets | `S01CatalogSpike` | [`S0.1-catalogo.md`](S0.1-catalogo.md) | hecho 2026-09-05 |
| S0.2 | Volumen, campos y localización en OCDS | `S02OcdsSpike` | [`S0.2-ocds.md`](S0.2-ocds.md) | hecho 2026-09-05 |
| S0.3 | Open311: endpoint, geolocalización, cierre, taxonomía | `S03Open311Spike` | [`S0.3-open311.md`](S0.3-open311.md) | hecho 2026-09-05 |
| S0.4 | Geometrías de juntas/barrios y padrón | `S04GeoSpike` | [`S0.4-geo.md`](S0.4-geo.md) | hecho 2026-09-05 |
| S0.5 | Comportamiento de la API: límites, paginación, cabeceras | `S05ApiBehaviourSpike` | [`S0.5-api.md`](S0.5-api.md) | hecho 2026-09-05 |
| S0.6 | Inventario sistemático del catálogo | `S06InventorySpike` | [`S0.6-inventario.md`](S0.6-inventario.md) + [matriz CSV](S0.6-inventario-matriz.csv) | hecho 2026-09-05 |
| S1.1 | Frescura observada: qué devuelven las distribuciones (cabeceras de ficheros, fechas máximas en API, recuentos WFS) y a qué coste | `S11ObservedFreshnessSpike` | [`S1.1-frescura-observada.md`](S1.1-frescura-observada.md) | hecho 2026-09-06 |
| S1.2 | Inventario de endpoints: forma del Swagger de la API, cruce por tag con las fichas y si los paths documentados sirven para observar las fichas cuyo endpoint declarado falla | `S12ApiInventorySpike` | [`S1.2-inventario-api.md`](S1.2-inventario-api.md) | hecho 2026-09-06 |

**Datos personales en los fixtures**: las fuentes de quejas y sugerencias devuelven texto ciudadano sin anonimizar (nombres, firmas y DNI; S0.3 adenda). Los fixtures con ese texto se guardan redactados con `SpikeFixtures.saveRedacted` y las cabeceras grabadas no llevan `Set-Cookie` (CLAUDE.md regla 22). El historial se limpió el 2026-09-06.

Fixtures de S1.1 (2026-09-06, `catalog/observation/`): cabeceras `HEAD` de ficheros (`head-*.headers`, grabadas por el propio spike con `SpikeFixtures.saveHeaders`), respuestas `rows=1` y `sort=<campo> desc` de endpoints de la sede, `resultType=hits` y `count=1` de WFS, y un `apiDefinition`. Los usa el adaptador de observación de `catalog` en sus tests.

Fixtures de S1.2 (2026-09-06, `catalog/`): `swagger-api.json` regrabado (idéntico al del día 5) más `swagger-api.headers`; en `catalog/observation/`, `api-sede-servicio-asociacion-list-rows1.json` (redactado) y `api-sede-servicio-clavo-topografico-list-rows1.json`, las dos alternativas `<declarado>/list` que usa `ObservationUrls`. Los usan `SwaggerJsonTranslatorTest`, `ZaragozaHttpClientTest`, `ObservationUrlsTest`, `DistributionHttpObserverTest`, `CatalogDataQualityTest` y `CatalogIntegrationTests`.

Fixtures de fase 1 (2026-09-06, grabados con `curl` con cuerpo y cabeceras, S0.1 adenda): `catalog/catalogo-rows2-fl.json`, `catalog/catalogo-rows500-fl.json` (la petición real de `CatalogIngestionJob`) y `catalog/catalogo-999999-notfound.json`. Los usan `ZaragozaHttpClientTest`, `CatalogJsonTranslatorTest`, `CatalogDataQualityTest` y los tests de integración vía `support/Fixtures`.

## Conclusiones de fase 0 (criterio de salida de SPEC.md §3)

- **(a) Cruce territorial inversión–quejas: no viable** con los datos abiertos actuales. OCDS no tiene ningún campo de localización (S0.2); presupuesto y subvenciones tampoco; no existen presupuestos participativos ni obras con importe (S0.6). El eje territorial se sostiene con quejas geolocalizadas (~50 %), juntas y padrón (S0.3, S0.4).
- **(b) Contexto de gasto**: `spending` = OCDS + presupuesto (snapshots de ejecución) + subvenciones, sin entidades territoriales. Decidido en [ADR-003](../decisions/ADR-003-contexto-spending.md) el 2026-09-05.
- **(c) Modelos** revisados en cada informe: `catalog` (S0.1), `spending` (S0.2, S0.6), `citizen` (S0.3), `geo` (S0.4: la unidad es la **junta**, no el barrio).
- Reglas del cliente HTTP de `ingestion` en S0.5.

## Conclusiones de fase 1 (S1.1, 2026-09-06)

- **`modified` del catálogo no describe el dato en ningún sentido**: en el lote real, de 149 fichas con fecha observada y `modified`, 107 cambiaron después de lo declarado y 15 antes. El monitor publica el eje declarado y el observado por separado, sin categoría observada (ADR-005).
- **Tres tipos de distribución observables** y sus medidas: ficheros por `HEAD` (`Last-Modified` en RFC 1123), API de la sede por `rows=1&sort=<campo> desc` (`lastUpdated` en 72 de 88) más `totalCount`, WFS por `resultType=hits` (`numberMatched`). SPARQL, buscadores, RSS/Atom, HTML y WMS no lo son: 151 fichas quedan `NOT_OBSERVABLE`.
- **El catálogo publica servicios inexistentes o de intranet** (8 endpoints API con 404, 23 distribuciones `-lan` con 403, 2 índices con 303, una URL `https:/`): el monitor los muestra como `observationError`; pendiente comunicarlo al ayuntamiento (`docs/ESTADO.md` §6).
- Coste: 436 fichas en 3 min 17 s con 0,3 s entre peticiones; ~900 peticiones al día.
- **El Swagger de la API es un documento único sin marca de cambio** (S1.2): 497 operaciones, 84 tags (uno por operación), sin `Last-Modified` ni `ETag`, `HEAD` → 400, `rows`/`start` ignorados. Se ingiere entero a diario (`DOCUMENT`, ADR-006) y se sincroniza en `catalog_api_endpoint`.
- **60 de las 68 fichas con tag casan con el Swagger; 28 tags documentados no tienen ficha** (OCDS, juntas, líneas de transporte, emisiones…). 8 fichas declaran tags que el Swagger no documenta; 4 de sus endpoints no existen (S1.1).
- **Los paths documentados no sustituyen al endpoint declarado**: de las 18 fichas cuyo endpoint falla, 11 tienen alternativas pero solo 4 son unívocas (`<declarado>/list`). La observación solo usa esa regla (ADR-006 §4).

## Hechos ya verificados el 2026-09-05 (previos a los spikes)

Comprobados con `curl` durante la planificación; los spikes deben confirmarlos y ampliarlos.

- `https://www.zaragoza.es/sede/servicio/catalogo/api.json` es el **Swagger 2.0 de la API** (496 paths, 241 definitions, 84 tags), no el catálogo de datasets.
- El catálogo de datasets es `https://www.zaragoza.es/web/espacio-de-datos/servicio/catalogo.json?rows=500&start=0&fl=...` → `{"totalCount":436,"start":0,"rows":N,"result":[...]}`. Detalle: `.../catalogo/{id}.json`. DCAT: `.../catalogo.rdf`.
- OCDS: `https://www.zaragoza.es/sede/servicio/contratacion-publica/ocds/contracting-process.json` (la variante sin extensión devuelve 400 salvo con `Accept: application/json`). Listado con solo `{ocid,id}`; parámetros `rows`, `before`, `after` (sin `start`). Detalle por ocid completo: `contracting-process/ocds-1xraxc-8136-ContractingProcess.json`.
- Open311: `https://www.zaragoza.es/api/recurso/open311/requests.json` y `services.json`, GET público sin firma HMAC. Endpoint hermano en sede con más campos: `https://www.zaragoza.es/sede/servicio/quejas-sugerencias/list.json`.
- Juntas: `https://www.zaragoza.es/sede/servicio/distrito.json`, `distrito/municipal.json`, `distrito/vecinal.json`, `distrito/{id}.json` (geo+json, `srsname=wgs84`).
- Presupuesto: `https://www.zaragoza.es/sede/servicio/presupuesto/gasto-corriente.json` y familia (15 paths).
- Licencias de obra: `https://www.zaragoza.es/sede/servicio/licencia-obra.json` (geo+json).
- Cabeceras condicionales irregulares: los listados no devuelven `ETag` ni `Last-Modified`; Open311 devuelve `ETag` pero ignora `If-None-Match`; `distrito/1.json` devuelve `Last-Modified` en formato no RFC (`Wed, 24 Jun 2026 08:58:50 CEST`).
- El texto del catálogo indica 50 registros por defecto y **máximo 500 por petición**.

## Plantilla de informe

```markdown
# S0.x — Título

- Fecha de ejecución:
- Clase: `S0xNombreSpike`
- Fixtures: `src/test/resources/fixtures/zaragoza/<fuente>/`

## Pregunta
## Método (endpoints y parámetros exactos usados)
## Resultados (números, con muestras enlazadas)
## Recomendación
## Impacto en SPEC.md (secciones a actualizar)
## Riesgos y dudas abiertas
```
