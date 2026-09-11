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
| S1.3 | Federación en datos.gob.es: paginación real, enlace por `identifier`, qué fichas no están federadas y qué datasets federados faltan en el listado municipal | `S13FederationSpike` | [`S1.3-federacion.md`](S1.3-federacion.md) | hecho 2026-09-06 |
| S2.1 | Resolución dirección/punto → junta: si la API la resuelve de verdad, qué numeración usa cada fuente y qué porcentaje de cada una queda sin asignar | `S21TerritoryResolutionSpike` | [`S2.1-resolucion-territorial.md`](S2.1-resolucion-territorial.md) | hecho 2026-09-08 |
| S2.2 | Quejas y sugerencias: si `fl` permite no pedir el texto libre, cuántos registros tiene el listado, si el incremental puede capturar los cierres, qué cobertura territorial hay en el histórico y si el barrido completo trae de verdad todos los registros | `S22CitizenIngestionSpike` | [`S2.2-quejas-ingesta.md`](S2.2-quejas-ingesta.md) | hecho 2026-09-08 |
| S2.3 | Contraste con Open311: si publica quejas que el listado de sede omite, si sitúa registros que la sede no sitúa y qué hace de verdad su ventana temporal | `S23Open311ContrastSpike` | [`S2.3-contraste-open311.md`](S2.3-contraste-open311.md) | hecho 2026-09-09 |
| S2.4 | Licencias de locales: cobertura de punto sobre el registro entero, por qué eje se puede barrer sin perder registros, qué texto libre trae y si se puede no descargar, y cuál es el modelo real | `S24LicensedPremisesSpike` | [`S2.4-registro-licencia.md`](S2.4-registro-licencia.md) | hecho 2026-09-09 |
| S3.1 | Contratación OCDS: cuál es el universo real de procesos, qué hacen de verdad `before` y `after`, qué proporción tiene release publicado, cuánto cuesta el histórico y qué dato personal trae un identificador que lleva el NIF dentro | `S31OcdsIngestionSpike` | [`S3.1-ocds-ingesta.md`](S3.1-ocds-ingesta.md) | hecho 2026-09-09 |
| S3.2 | Presupuesto de gastos: qué es de verdad una instantánea, si el pasado se reescribe, por qué eje se barre sin perder filas, qué relación guardan los ocho importes y si los resúmenes anuales aportan algo que no esté en las instantáneas | `S32BudgetSpike` | [`S3.2-presupuesto-ingesta.md`](S3.2-presupuesto-ingesta.md) | hecho 2026-09-10 |
| S3.3 | Subvenciones: cuál de las dos versiones de la API es el censo completo, qué publica de verdad del beneficiario y en qué proporción, si se puede no descargar su nombre, y qué texto libre lleva identidad dentro | `S33GrantsSpike` | [`S3.3-subvenciones-ingesta.md`](S3.3-subvenciones-ingesta.md) | hecho 2026-09-11 |

**Datos personales en los fixtures**: las fuentes de quejas y sugerencias devuelven texto ciudadano sin anonimizar (nombres, firmas y DNI; S0.3 adenda). Los fixtures con ese texto se guardan redactados con `SpikeFixtures.saveRedacted` y las cabeceras grabadas no llevan `Set-Cookie` (CLAUDE.md regla 22). El historial se limpió el 2026-09-06.

Fixtures de S1.1 (2026-09-06, `catalog/observation/`): cabeceras `HEAD` de ficheros (`head-*.headers`, grabadas por el propio spike con `SpikeFixtures.saveHeaders`), respuestas `rows=1` y `sort=<campo> desc` de endpoints de la sede, `resultType=hits` y `count=1` de WFS, y un `apiDefinition`. Los usa el adaptador de observación de `catalog` en sus tests.

Fixtures de S1.2 (2026-09-06, `catalog/`): `swagger-api.json` regrabado (idéntico al del día 5) más `swagger-api.headers`; en `catalog/observation/`, `api-sede-servicio-asociacion-list-rows1.json` (redactado) y `api-sede-servicio-clavo-topografico-list-rows1.json`, las dos alternativas `<declarado>/list` que usa `ObservationUrls`. Los usan `SwaggerJsonTranslatorTest`, `ZaragozaHttpClientTest`, `ObservationUrlsTest`, `DistributionHttpObserverTest`, `CatalogDataQualityTest` y `CatalogIntegrationTests`.

Fixtures de S1.3 (2026-09-06, `catalog/`): `datos-gob-es-page0.json` regrabado (`_pageSize=50&_page=0`, 50 datasets) con `datos-gob-es-page0.headers`, `datos-gob-es-page-last.json` (`_pageSize=200&_page=1`, 169) y `datos-gob-es-page-beyond.json` (`_page=99`, `items` vacío). Los usan `FederationJsonTranslatorTest`, `ZaragozaHttpClientTest` y `CatalogIntegrationTests`.

Fixtures de S2.1 (2026-09-08, `geo/`): `distrito.json_srsname-wgs84_rows-100` regrabado (las 29 juntas con su polígono en WGS84), `portalero-direccion-alfonso-i-39.json` + `.headers` y `portalero-direccion-inexistente.json` (la búsqueda que devuelve otra calle), `locales-vacios-junta-punto-page0.json` (punto y junta oficial en la misma respuesta, la base de la comprobación de la resolución geométrica) y `quejas-district-geometria.json` (`fl` explícito sin `title` ni `description`, regla 22). Los usan `DistrictJsonTranslatorTest`, `DistrictProfileHttpReaderTest` y `GeoIntegrationTests` del módulo `geo`; el de `locales-vacios` es el que sostiene la comprobación de aceptación de ADR-011.

Fixtures de S2.2 (2026-09-08, `open311/`): `services.json` regrabado (100 servicios) y `sede-list-ingest-page.json` + `.headers`, que es **la petición de ingesta tal cual la hará producción**: `rows=500&srsname=wgs84&sort=requested_datetime desc` con el `fl` de ocho campos de ADR-012. Ese fixture no contiene texto libre porque no se pidió, no porque se redactara después.

Fixtures de S2.3 (2026-09-09, `open311/`): `open311-requests-page.json` + `.headers`, una página de 50 registros de `open311/requests.json` **redactada** (Open311 devuelve el texto libre igual que la sede). Documenta la forma de la respuesta, sus 13 campos y el `ETag` que la fuente sí emite. Ningún adaptador de producción lo usa: ADR-014 decide no ingerir esta fuente.

Fixtures de S2.4 (2026-09-09, `urban/`): `registro-licencia_rows-2.json` + `.headers`, `registro-licencia_page0_rows-500.json` (**la petición de ingesta tal cual la hará producción**: registros completos, `sort=id asc&srsname=wgs84`), `registro-licencia-portal_rows-2.json` (el recurso de portales, sin `junta`), `registro-licencia-iae_page0.json` (la taxonomía de 965 epígrafes) y `registro-licencia-zona-saturada.json` (las 15 zonas con su polígono). Los dos primeros van **redactados** en `comments`: aquí el texto libre no se puede dejar de descargar (`fl` rompe los objetos anidados y `removeproperties` no hace nada), así que la redacción es la única forma de que no entre en el repositorio. Los usan `LicensedPremisesJsonTranslatorTest` y `UrbanIntegrationTests`.

Fixtures de S3.1 (2026-09-09, `ocds/`): `contracting-process-list-rows2.json` + `.headers`, `contracting-process-list-empty.json` (**la respuesta vacía, que cambia de forma y devuelve el envoltorio `{"totalCount":0,…}` en vez de un array**), `contracting-process-tender.json` y `contracting-process-contract.json` (dos release packages reales, uno solo con licitación y otro con contrato firmado) y `contracting-process-detail.headers`. **No van redactados y no hacía falta**: el barrido completo de los 5.622 documentos no encontró un solo DNI ni NIE con letra de control válida, y el spike solo admite como fixture un package que pase esa comprobación.

Fixtures de S3.2 (2026-09-10, `budget/`): `gasto-corriente_fecha.json` + `.headers` (el censo de 140 instantáneas, que llega entero en una petición e **ignora `rows`**), `gasto-corriente_rows-3.json` + `.headers`, `gasto-corriente_20061231_rows-2.json` y `gasto-corriente_20260831_rows-2.json` (la instantánea más antigua y la más reciente: entre las dos se ve que **el programa presupuestario no existía** y que los códigos antiguos llegan rellenos con espacios), los tres resúmenes anuales (`gastado-resumen.json`, `organo-resumen.json`, `programa-resumen.json`) e `ingreso-corriente_rows-1.json`. **No van redactados**: el barrido de las 154.508 filas no encontró un solo DNI, NIE, correo ni teléfono, y las cuatro filas que nombran a una persona física están en los cierres de 2006-2009, fuera de estos fixtures. Los usan `BudgetJsonTranslatorTest` y `SpendingIntegrationTests`.

Fixtures de S3.3 (2026-09-11, `grants/`): `resolucion-rows2-fl.json` (**la petición de ingesta tal cual la hace producción**, con la proyección que deja fuera el nombre del beneficiario) y `resolucion-rows2-full.json` (la misma sin proyección, para probar que el traductor **no lee** `adjudicatario` aunque llegue), `convocatoria-rows2-fl.json` (con la ruta con punto que trae el tercer nivel entero), `concesion-rows2.json` y `organization-rows3.json` de la v2, `concesion-fl-vacio.json` (**dos bytes**: lo que devuelve `fl` sobre la v2) y `resolucion-beyond.json`. **Todos van redactados** con `SpikeFixtures.saveRedacted` sobre diez campos, el mayor número hasta la fecha: esta fuente publica el nombre de 6.333 personas físicas y el DNI de 2.759. Los usan `GrantJsonTranslatorTest` y `GrantsIntegrationTests`.

Fixtures de fase 1 (2026-09-06, grabados con `curl` con cuerpo y cabeceras, S0.1 adenda): `catalog/catalogo-rows2-fl.json`, `catalog/catalogo-rows500-fl.json` (la petición real de `CatalogIngestionJob`) y `catalog/catalogo-999999-notfound.json`. Los usan `ZaragozaHttpClientTest`, `CatalogJsonTranslatorTest`, `CatalogDataQualityTest` y los tests de integración vía `support/Fixtures`.

## Conclusiones de fase 0 (criterio de salida de SPEC.md §3)

- **(a) Cruce territorial inversión–quejas: no viable** con los datos abiertos actuales. OCDS no tiene ningún campo de localización (S0.2); presupuesto y subvenciones tampoco; no existen presupuestos participativos ni obras con importe (S0.6). El eje territorial se sostiene con quejas geolocalizadas (~50 %), juntas y padrón (S0.3, S0.4).
- **(b) Contexto de gasto**: `spending` = OCDS + presupuesto (snapshots de ejecución) + subvenciones, sin entidades territoriales. Decidido en [ADR-003](../decisions/ADR-003-contexto-spending.md) el 2026-09-05.
- **(c) Modelos** revisados en cada informe: `catalog` (S0.1), `spending` (S0.2, S0.6), `citizen` (S0.3), `geo` (S0.4: la unidad es la **junta**, no el barrio).
- Reglas del cliente HTTP de `ingestion` en S0.5.

## Conclusiones de fase 2 (S2.1, 2026-09-08)

- **La API municipal no resuelve la junta.** `portalero/v2` es un buscador de direcciones que acierta la junta en el 89,5 % y **no permite distinguir un acierto de un fallo** (sin `totalCount`, sin `junta.id`, devolviendo otra calle cuando la pedida no existe); `point`/`distance` no filtra por proximidad en ninguno de los dos recursos que lo documentan. La resolución territorial se hace en casa, con `ST_Contains` sobre los 29 polígonos (ADR-011).
- **La vía geométrica está probada contra la fuente oficial**: 99,69 % de acuerdo con `locales-vacios.portal.junta` (1.304 de 1.308) y 99,0 % con `edificio-historico` (491 de 496), con 0 puntos fuera de las juntas.
- **`distrito.id` ↔ `idpadron` resuelto**: los dos números vienen juntos en `distrito/{id}.indicadores` (`iddatosab` e `idpadron`). El padrón por junta tiene 2020, 2021, 2022 y 2024; **falta 2023**.
- **La cobertura territorial es muy desigual**: 99–100 % de registros con punto en `licencia-obra`, `registro-licencia` y `via-publica`, pero **49 %** en quejas y en locales vacíos (corrige S0.6, que daba las 3.824 fichas de `locales-vacios` como geolocalizadas). Sin punto no hay junta: se cuenta como `unassigned`.
- **Los 29 polígonos no son una partición** (0,29 % de solape, siempre con Juslibol) y los nombres de junta tienen variantes que no casan (`DISTRITO SUR`, `SAN JUAN DE MOZARRIFAR`).

## Conclusiones de fase 2 (S2.2, 2026-09-08)

- **El texto libre se puede no pedir**: `fl` recorta de verdad la respuesta del listado de quejas, así que `title`, `description` y `service_notice` no entran en el sistema (ADR-012). La redacción por patrones no era alternativa: 2 DNI en 7.000 registros frente a **3.353 (47,9 %) con fórmula de firma**, donde el nombre no lo encuentra ninguna expresión regular.
- **El listado tiene 89.432 registros** (2013-01-08 → hoy) y sigue siendo **un subconjunto**: `statistics.json` cuenta ~40.000 cerradas al año.
- **El orden por defecto no es fiable** (sin `sort`, el primer registro fue de 2014 el día 8 y de 2013 el día 5): la ingesta manda `sort` explícito siempre.
- **Y el barrido completo solo es exacto por un eje**: por `requested_datetime asc` salen 89.432 filas y 89.432 ids distintos; por `updated_datetime asc`, las mismas 89.432 filas pero solo **80.628 ids distintos** —2.680 repetidos y **8.804 que no aparecen nunca**—, porque las quejas cerradas por lotes empatan en fecha y entre filas empatadas el orden no es estable entre páginas. Que dos páginas consecutivas no solapen **no** implica que el barrido sea completo: se descubrió ejecutando la ingesta de verdad. La carga del histórico la hace solo el job de altas.
- **`updated_datetime` admite FIQL y `sort`**: el incremental puede capturar los cierres, incluidos los de expedientes antiguos (se observó una queja de 2015 cerrada en septiembre de 2026).
- **La cobertura de punto en el histórico va del 16 % al 45 % según el año** (S0.3 midió 50 % sobre los 500 más recientes): las series por junta y año no son comparables entre sí sin publicar la cobertura al lado.
- **Cuatro sinónimos de nombre de junta con evidencia** (ADR-011 §5): `DISTRITO SUR` → 30, `SAN JUAN DE MOZARRIFAR` → 25, `TORRECILLA` → 26 y `CASCO HISTÓRICO` con un U+0093 intercalado → 3.
- **Comprobado después con PostGIS** (módulo `geo`, 2026-09-08): `ST_Contains` reproduce el ray casting del spike registro a registro sobre la página grabada de `locales-vacios` (233 de 237, las 4 discrepancias en el borde Delicias/La Almozara). La prueba vive en `GeoIntegrationTests` y es la comprobación de aceptación de ADR-011.

## Conclusiones de fase 2 (S2.3, 2026-09-09)

- **Open311 es un subconjunto estricto del listado de sede.** En cuatro meses cerrados de 2017, 2025 y 2026 y en **2025 entero (11.895 registros)**, cero registros que Open311 publique y la sede omita. Lo que la sede tiene y Open311 no son las **`INTERNAL`** (247 de 11.895 en 2025) y **cinco registros** (0,04 %) que además responden 400 en el detalle de la sede.
- **El hueco con las estadísticas municipales no está entre las dos API**: las dos publican lo mismo, así que la duda sobre el criterio de publicación no se cierra consultando otro endpoint (ADR-014).
- **Open311 no aporta nada territorialmente**: en los 1.027 registros comunes de agosto de 2026, cero quejas que sitúe y la sede no, cero discrepancias de coordenadas y cero de junta declarada.
- **La ventana temporal de Open311 se recorta a tres meses en silencio.** Una petición de un año responde 200 con un primer trimestre que parece completo; sin fechas, la respuesta son los últimos tres meses, no el histórico. Corrige de paso la prueba de S0.3: sus ventanas anuales de 2004–2016 solo miraban el primer trimestre de cada año (aquí se comprobaron los cuatro de 2015, 2016 y 2017: Open311 empieza en 2017).
- **`rows` topa en 1.000** (la regla 18 decía «sin tope»), **`totalCount` vale siempre 0** y **`fl=…,lat,long` devuelve `lat` sin `long`**.
- **Las dos fuentes fechan distinto el mismo registro**: el desfase es exactamente el doble del huso (4 h en verano, 2 h en invierno, sin excepciones en 1.892 registros), porque Open311 publica la hora local convertida dos veces y marcada con `Z`. Solo se pueden cruzar por identificador, nunca por fecha.

## Conclusiones de fase 1 (S1.1, 2026-09-06)

- **`modified` del catálogo no describe el dato en ningún sentido**: en el lote real, de 149 fichas con fecha observada y `modified`, 107 cambiaron después de lo declarado y 15 antes. El monitor publica el eje declarado y el observado por separado, sin categoría observada (ADR-005).
- **Tres tipos de distribución observables** y sus medidas: ficheros por `HEAD` (`Last-Modified` en RFC 1123), API de la sede por `rows=1&sort=<campo> desc` (`lastUpdated` en 72 de 88) más `totalCount`, WFS por `resultType=hits` (`numberMatched`). SPARQL, buscadores, RSS/Atom, HTML y WMS no lo son: 151 fichas quedan `NOT_OBSERVABLE`.
- **El catálogo publica servicios inexistentes o de intranet** (8 endpoints API con 404, 23 distribuciones `-lan` con 403, 2 índices con 303, una URL `https:/`): el monitor los muestra como `observationError`; pendiente comunicarlo al ayuntamiento (`docs/ESTADO.md` §6).
- Coste: 436 fichas en 3 min 17 s con 0,3 s entre peticiones; ~900 peticiones al día.
- **El Swagger de la API es un documento único sin marca de cambio** (S1.2): 497 operaciones, 84 tags (uno por operación), sin `Last-Modified` ni `ETag`, `HEAD` → 400, `rows`/`start` ignorados. Se ingiere entero a diario (`DOCUMENT`, ADR-006) y se sincroniza en `catalog_api_endpoint`.
- **60 de las 68 fichas con tag casan con el Swagger; 28 tags documentados no tienen ficha** (OCDS, juntas, líneas de transporte, emisiones…). 8 fichas declaran tags que el Swagger no documenta; 4 de sus endpoints no existen (S1.1).
- **Los paths documentados no sustituyen al endpoint declarado**: de las 18 fichas cuyo endpoint falla, 11 tienen alternativas pero solo 4 son unívocas (`<declarado>/list`). La observación solo usa esa regla (ADR-006 §4).
- **El listado del catálogo encoge, y sin aviso** (comprobado el 2026-09-09, sin spike propio: una petición real comparada con el fixture del 2026-09-06). `catalogo.json?rows=500` pasó de `totalCount` 436 a **434**; las dos que faltan son **«Paradas Bus» (4008)** y **«Paradas Tranvía» (4010)**, y su `catalogo/{id}.json` responde **404**. No hay ninguna ficha nueva, ni cabecera ni campo que anuncie la baja: la única señal es la ausencia. El observatorio la marca con `delisted_at` y no borra la ficha (ADR-013).
- **datos.gob.es se pagina con `_page`/`_pageSize` (tope 200) y enlaza por `identifier`** (S1.3): 369 datasets del publicador municipal, 261 con ficha en el catálogo, **108 sin ficha en el listado** (partes de series y colecciones que `catalogo.json` no devuelve pero el detalle sí) y 175 fichas sin federar (149 `abierto=N`, 15 abiertas). El `modified` federado coincide siempre con el municipal. Ingerido a diario con baja de lo no visto al completar (ADR-007).
- **El listado `catalogo.json` (436) no es todo el catálogo**: al menos 544 fichas publicadas; las partes de series solo se alcanzan por `catalogo/{id}.json` (`datasetRelacionado`, `series[]`). Pendiente para la fase 2 (SPEC.md §9).

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

## Conclusiones de fase 2 (S2.4, 2026-09-09)

- **La cobertura de punto real de `registro-licencia` es del 89,4 %, no del 99 %**: aquel 99,0 % de S2.1 estaba medido sobre el primer lote de 500. Sobre los 42.342 locales, 37.843 traen punto, 37.827 caen dentro de una junta, 16 fuera y 0 en zona de solape. Sigue siendo tres veces la cobertura de las quejas.
- **El barrido solo es exacto por `id asc`**: 85 páginas, 42.342 filas y 42.342 ids distintos. Por `lastUpdated asc`, las mismas filas dan **39.414 ids** —2.928 registros que no se verían—, porque **22.044 locales comparten un mismo instante de carga**. De ahí el diseño de ADR-016 §4: filtrar por fecha en `q` y ordenar por `id`, que no empata.
- **El texto libre no se puede dejar de descargar.** `fl` recorta los campos planos pero **vacía los objetos anidados** (una licencia proyectada llega sin año, sin expediente y sin tipo), y `removeproperties`, documentado en el Swagger, **se acepta y no hace nada**. Contiene **15 DNI con letra de control válida** en los comentarios de las licencias y 2 dentro del nombre de la actividad. La decisión (ADR-016 §3) es no guardarlo en ninguna parte, ni siquiera en `raw_payload`.
- **La fuente no declara junta** por ninguna vía: ni el local ni el recurso de portales del que cuelga, al contrario que `locales-vacios`. No hay contraste declarado/resuelto que publicar, y no se fabrica uno cruzando el código de portal contra el callejero.
- **El modelo**: 42.342 locales y 69.631 licencias (media 1,64, máximo 12). La licencia se identifica por **`(año, expediente)`** dentro de su local, sin una sola colisión; el campo `orden` colisiona 950 veces en 788 locales y no vale como clave.
- **Códigos sin taxonomía**: `estado` toma cuatro valores y no hay endpoint ni ficha que los describa; las zonas saturadas publican 15 códigos y los locales usan 17 (`O` y `P` no están en el catálogo). Se guardan como códigos y no se les pone nombre (regla 6).
- **`Last-Modified` describe la página, no el recurso**: cambia con `sort` y vale el `lastUpdated` del registro devuelto. Observar esta ficha por cabeceras mediría el orden de la petición, no la frescura del dato.

## Conclusiones de fase 3 (S3.1, 2026-09-09)

- **El listado documentado de OCDS es un subconjunto estricto**: publica 5.730 procesos de los 8.001 que existen. Los 2.271 que esconde solo aparecen mandando `after`, y **`after` no es una fecha: es un interruptor**. Por debajo de un umbral que está entre el 1 y el 2 de enero de 2017 no hace nada; por encima, el valor da igual y siempre devuelve los mismos 8.001, incluidos procesos publicados en 2008. Quien pagine el listado documentado se lleva el 71,6 % del histórico creyendo que lo tiene entero.
- **`before` sí filtra por fecha de publicación, pero solo el conjunto base.** Los recuentos son exactamente aditivos: los 2.271 ocultos entran o no entran, y ningún filtro de fecha los toca. Ninguno de los dos parámetros sirve de marca de agua: `after` = hoy menos 7, 30 o 365 días devuelve siempre los 8.001.
- **El 70,3 % de los procesos tiene release** (5.622 de 8.001) y el 404 se concentra limpiamente en los recientes: del 100 % de cobertura en los expedientes más antiguos al **8 %** en el último decil. No son procesos inexistentes, son releases sin publicar todavía. Además, **16 packages responden 200 con `releases` vacío**: un 200 no garantiza release, y el 404 llega con un cuerpo que dice 400.
- **Ni gasto previsto ni gasto pagado**: `planning` aparece en 0 documentos y `contracts[].implementation` en 0. El importe de contratación es **adjudicado** (1.819.170.873 € frente a 4.359.428.216 € licitados) y así hay que etiquetarlo. `executed` solo puede salir del presupuesto (S0.6).
- **1.560 contratos son cáscaras vacías**: `contracts[].id` aparece 4.970 veces pero `awardID`, `dateSigned` y `description` solo 3.410. Eso deja 1.560 procesos completos **sin `stage` derivable**, y ponerles `committed` por deducción sería una conclusión del observatorio (regla 6).
- **Sin territorio, ahora medido sobre la fuente entera**: cero caminos de localización en 184 caminos distintos y 5.622 documentos. ADR-003 §1 se mantiene sin matices.
- **Dato personal: otro orden de magnitud que en `citizen` y `urban`.** Cero DNI y cero NIE con letra válida en todo el barrido. De 8.482 NIF incrustados en `parties[].id`, **8.481 son de persona jurídica y 1 empieza por dígito**. El texto libre trae **21 coincidencias del patrón de tratamiento** sobre 5.606 documentos, el 0,4 %, frente al 47,9 % de fórmula de firma que hizo a ADR-012 descartar la redacción por patrones.
- **El histórico cuesta 8.001 peticiones y 25,2 MB**, 30,6 min con pausa de 40 ms. El incremental no depende de ningún filtro: los ocids nuevos aparecen al final del listado, así que una pasada diaria es una petición de listado más un detalle por proceso nuevo, más los reintentos de los 2.379 sin release.
- **`sort=id desc` se acepta y no se aplica** en el listado, la misma familia que `q=junta.id==N` (S2.1), `status=rejected` (S2.2) y `removeproperties` (S2.4).

## Conclusiones de fase 3 (S3.2, 2026-09-10)

- **El gasto ejecutado existe y sale de aquí.** `presupuesto/gasto-corriente` publica por partida los ocho importes del ciclo presupuestario, y las tres identidades contables cuadran al céntimo: crédito definitivo = inicial + modificaciones, remanente = definitivo − obligación neta, pendiente de pago = obligación neta − pago neto. Las cuatro cifras que importan (definitivo, comprometido, obligación neta, pago neto) **no son intercambiables** y se publican las cuatro.
- **La unidad no es la partida sino la instantánea**: 140 fotos datadas del presupuesto entero, de 2006-12-31 a 2026-08-31, con **154.508 filas** en total. `id` = `fecha` + `concepto`, así que identifica a la partida *en esa instantánea*, y `concepto` lleva dentro los dos dígitos del ejercicio: de los 22.838 conceptos distintos, **ninguno cruza de año**.
- **El orden por defecto sirve dos ordenaciones distintas a la misma URL.** Es la misma familia que el orden no documentado de las quejas (S2.2), pero peor: puede cambiar entre dos páginas del mismo barrido. Con `sort=id asc` el barrido completo no pierde ni repite una sola fila (0 desajustes entre ids distintos y `totalCount` en las 140 instantáneas).
- **Una instantánea publicada no se reescribe**, hasta donde se puede comprobar: las tres partidas grabadas el 2026-09-05 mantienen los ocho importes cinco días después. Eso hace la ingesta barata de verdad: cada instantánea se lee una vez y solo se relee la más reciente. El histórico entero son **396 peticiones y 2,3 minutos**; una instantánea nueva al mes, 3 peticiones.
- **Los resúmenes anuales de la API son derivables al céntimo** (Δ = 0,00 € en los doce años que publican, en las dos columnas) y **más cortos que la serie**: empiezan en 2015 cuando las instantáneas llegan a 2006. No se ingieren.
- **El nombre del endpoint engaña**: `gasto-corriente` incluye inversiones reales (305 partidas del capítulo 6 en la última instantánea) y los capítulos financieros. Es el presupuesto de gastos entero.
- **El esquema cambia con los años**: `programa` no existe en 2010-2014 y llega incompleto en otros seis ejercicios (121.890 de 154.508 filas lo traen). No es un fallo del traductor y se dice en `caveats`.
- **Dato personal: cero identificadores y una partida que nombra a una persona.** Cero DNI, NIE, correos y teléfonos en las 154.508 filas, pero **4 filas** de los cierres de 2006-2009 son una pensión «a la viuda de D. …». Se guarda el texto de la partida —sin él es un importe sin concepto— redactando la lista cerrada de fórmulas de persona, con el recuento en `caveats`.

## Conclusiones de fase 3 (S3.3, 2026-09-11)

- **El censo completo de subvenciones es la versión vieja.** `ayuda-subvencion/resolucion` publica 46.925 concesiones de 2013 a 2026 y `ayuda-subvencion-v2/concesion` es un **subconjunto estricto** (44.316, cero registros propios) que esconde 2013 y 2014. Es la tercera vez que esta API publica un listado que parece completo y no lo es, y la primera en la que **el recurso más nuevo es el más incompleto**.
- **La fuente se contradice en datos personales**: enmascara el NIF de la persona física, anonimiza su directorio de entidades hasta dejarlo sin un solo dato de contacto… y publica el **nombre y apellidos de 6.333 beneficiarios** en un campo estructural y **2.378 DNI y 381 NIE con letra de control válida** dentro del texto del título. Es, junto con el texto de las quejas (S2.2), el hallazgo más serio del proyecto.
- **Aquí sí se puede no pedir el nombre**: `fl` recorta de verdad en la v1. Pero recorta **por subárbol y a dos niveles**, y la salida para el tercero es la **ruta con punto**, que el Swagger no documenta. Sobre la v2, `fl` devuelve `{}`.
- **Mezclar `rows` con `page` solapa páginas en silencio**: el desplazamiento lo calcula `pageSize` (50 por defecto) aunque el tamaño lo fije `rows`. Y `rows` topa en 500 mientras `pageSize` no topa en nada.
- Decisión en [ADR-018](../decisions/ADR-018-subvenciones-y-beneficiario.md): el beneficiario entra como **seudónimo** y su identidad solo si no es una persona física; el título se guarda con el identificador **redactado por forma**; y dos CHECK lo imponen desde la base de datos.
