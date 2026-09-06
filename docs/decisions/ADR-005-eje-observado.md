# ADR-005 — Eje observado de la frescura: cuatro métodos de observación, muestreo diario y cliente HTTP único

- **Fecha**: 2026-09-06
- **Estado**: aceptada
- **Afecta a**: `SPEC.md` §2.1, §3 (fase 1), §4.6 (`catalog`), §4.7, §9; `CLAUDE.md` regla 23; `docs/ESTADO.md` §4
- **Se apoya en**: `docs/spikes/S1.1-frescura-observada.md` (284 peticiones reales el 2026-09-06), `docs/spikes/S0.5-api.md`, ADR-004

## Contexto

El 60 % del catálogo (263 de 436 fichas) no es evaluable con `modified` y `accrualPeriodicity` (S0.1), y en las evaluables `modified` tampoco describe el dato: el spike S1.1 encontró ficheros hasta 1.940 días más viejos que su `modified` y endpoints cuyo último registro cambió siete años después de la fecha declarada, en ambos sentidos. Había que observar las distribuciones. El spike midió qué devuelven y a qué coste:

- los ficheros descargables responden a `HEAD` en el 100 % de la muestra (80/80), con `Last-Modified` en RFC 1123 (78/80), `ETag` y `Content-Length`; p50 13 ms;
- los endpoints de la sede aceptan `rows=1&sort=<campo> desc` y devuelven el máximo del campo (28 de las 29 distribuciones `application/api` con campo de fecha; 19 de 25 `application/json`); `lastUpdated` es el campo habitual; un campo inexistente da 400 JSON; `puntos-interes` responde 200 vacío a cualquier `sort`; 46 de 67 traen `totalCount`;
- los WFS de `idezar-sig` responden a `GetFeature&resultType=hits` con `numberMatched` (12/12), pero ni `GetCapabilities` ni las features traen fechas de cambio; cuatro servicios `-lan` son de intranet (403);
- SPARQL, buscadores, RSS/Atom y HTML no son observables;
- observar una distribución por ficha en todo el catálogo cuesta unas 900 peticiones y menos de un minuto de red al día.

La regla 6 (nada de conclusiones en el código) prohíbe convertir estas medidas en etiquetas («parado», «vivo») sin ADR y sin serie temporal.

## Decisión

1. **Cuatro métodos de observación y uno de ausencia**, como enumerado `ObservationMethod`, cada uno con una medida definida y ninguna más: `FILE_HEADERS` (`HEAD` a cada fichero de la ficha, máximo 10; `observedLastChange` = máximo `Last-Modified`), `API_MAX_DATE` (`GET <endpoint>.json?rows=1&sort=<campo> desc`; `observedLastChange` = valor del campo en el primer registro; `observedRecords` = `totalCount`/`totalRecords`), `API_COUNT` (solo `totalCount`, cuando no hay campo admitido o el `sort` no responde con JSON), `WFS_HITS` (`observedRecords` = `numberMatched`) y `NOT_OBSERVABLE` (sin distribución de los tipos anteriores).
2. **Lista blanca cerrada de campos de fecha**, por preferencia: `lastUpdated`, `modified`, `updated_datetime`, `requested_datetime`, `publicationDate`, `fechaRegistro`, `pubDate`, `creationDate`, `fechaAlta`, `fecha`. Se elige el primero presente en el primer registro con un valor de fecha reconocible, y **el campo usado se publica** en `observationDetail`: «último `lastUpdated`» (marca de la plataforma) y «último `creationDate`» (fecha del registro más reciente) no significan lo mismo. Fechas de dato sin semántica de cambio (`fechaNac`, `fechaMatriculacion`, `startDate`…) quedan fuera.
3. **Orden de intento por ficha: API → ficheros → WFS**, con un máximo de dos endpoints y dos capas por ficha. Gana la primera observación con medida; si ninguna la tiene se registra el primer intento fallido con método, URL y causa (`observationError`). Un 404 HTML, un 303 a una página web o un 403 de intranet son hallazgos del monitor sobre el catálogo, no fallos del monitor: se publican.
4. **Muestreo diario por lotes desde el planificador de `catalog`** (`CatalogObservationScheduler`, `zaragoza.catalog.observation.*`): cada tick observa las fichas nunca observadas y después las más antiguas, en serie, con 0,5 s entre peticiones (S0.5), sin reintentos ni circuit breaker (una observación fallida se repite al día siguiente) y sin seguir redirecciones. La observación **no es un `IngestionJob`**: no ingiere una fuente ni guarda `raw_payload`; comparte con `ingestion` la hebra única del planificador, así que nunca solapa con una ingesta.
5. **Dos ejes en la misma instantánea, escritos por separado.** `FreshnessSnapshot` guarda el eje declarado (como hasta ahora) y el observado (`observedAt`, `observationMethod`, `observedUrl`, `observedLastChange`, `observedRecords`, `observationDetail`, `observationError`); `TakeFreshnessSnapshots` conserva la observación del día y `RecordObservation` conserva el cálculo declarado (o lo crea si la observación llega antes). `Dataset` lleva `observedAt` (para el reparto) y la marca de la última observación (método y último cambio) para listar, filtrar y resumir; una observación fallida deja esa marca sin `observedLastChange`, y el histórico conserva las anteriores.
6. **Sin categoría observada ni cruce declarado/observado.** La API publica las medidas con su método y sus `caveats`. Una categoría («recuento estable», «dato parado») o un cruce con el eje declarado exigirán una serie de instantáneas reales y una ADR propia.
7. **Un solo `RestClient` para toda la aplicación**, bean del paquete raíz (`HttpClientConfiguration`, como el `Clock`): timeouts y no seguir redirecciones por `spring.http.clients.*`, identificación del reutilizador por `zaragoza.http.user-agent`. `ingestion` le añade retry y circuit breaker (ADR-004); `catalog` lo usa tal cual. Con dos clientes construidos desde el builder de Boot, el `MockRestServiceServer` autoconfigurado de los tests de integración se niega a enlazarse («bound to more than one RestClient»), y además la política HTTP quedaría repartida en dos sitios.

## Consecuencias

- Migración `V005__catalog_observation.sql`: cuatro columnas en `catalog_freshness_snapshot`, tres en `catalog_dataset` (con índice por `observed_at NULLS FIRST`) y `wfs_feature_name` en `catalog_distribution`. Aplicada sin incidencias sobre la base de datos de Compose con 436 fichas.
- La API expone los campos nuevos, el filtro `observation=<método>`, el orden `observedLastChange` y `byObservationMethod`/`withoutObservation` en `summary`; los `caveats` explican cada método.
- Carga sobre la infraestructura municipal: unas 900 peticiones al día, repartidas en lotes de 60 cada 10 minutos, todas con `User-Agent` identificado.
- El monitor mostrará como errores persistentes los 8 servicios API inexistentes y las 23 distribuciones WFS/WMS de intranet que publica el catálogo; conviene comunicarlos al ayuntamiento (`docs/ESTADO.md` §6).
- Las 121 fichas sin distribuciones y las 22 con solo tipos no observables quedan en `NOT_OBSERVABLE`: para ellas solo existe el eje declarado, y así se declara.
- `IngestionProperties.http.userAgent` desaparece; la propiedad pasa a `zaragoza.http.user-agent`.

## Alternativas descartadas

- **Una `IngestionJob` por distribución**: centenares de fuentes y de `raw_payload` para guardar un `HEAD` o un registro; el modelo de ingesta está pensado para traer datos, no para preguntar por ellos.
- **Descargar los ficheros y compararlos por hash**: 4 MB por hoja de cálculo y 16 MB por GeoJSON para lo que `Last-Modified` y `ETag` ya dicen; queda como reserva para ficheros sin `Last-Modified` (2 de 80).
- **Heurística abierta «cualquier campo con `fecha` o `date`»**: habría ordenado «Adopción de animales» por `fechaNac` y «Vehículos» por `fechaMatriculacion`; medidas sin sentido presentadas como frescura.
- **Leer el Swagger por servicio (`apiDefinition`) para conocer los campos ordenables**: funciona (79 distribuciones) pero añade una petición y un parser; probar el `sort` y tratar el 400 cuesta lo mismo. Queda como mejora.
- **Categoría observada desde el primer día**: sin serie temporal no hay forma de saber si un `lastUpdated` viejo es un dato parado o un dato que no necesita cambiar.
- **Dos `RestClient` (uno por módulo)**: rompe el `MockRestServiceServer` autoconfigurado y duplica la política HTTP.
