# ADR-006 — Inventario de endpoints: el Swagger de la API como documento ingerido y cruce por tag

- **Fecha**: 2026-09-06
- **Estado**: aceptada
- **Afecta a**: `SPEC.md` §2, §3 (fase 1), §4.5, §4.6 (`catalog`), §4.7, §9; `CLAUDE.md` regla 24; `docs/ESTADO.md` §4
- **Se apoya en**: `docs/spikes/S1.2-inventario-api.md` (117 peticiones reales el 2026-09-06), `docs/spikes/S0.1-catalogo.md`, ADR-004, ADR-005

## Contexto

El catálogo enlaza 68 de sus fichas con la API municipal a través del tag del Swagger que declara la distribución `application/api` (`accessURL` con `#/<tag>`), y S0.1 ya vio que el Swagger documenta fuentes sin ficha (OCDS, juntas). Faltaba ingerir el Swagger como inventario de endpoints y cruzarlo con las fichas (`docs/ESTADO.md` §4 paso 1). El spike S1.2 midió el documento y el cruce:

- es un Swagger 2.0 de 1,3 MB con 497 operaciones en 496 paths y 84 tags (uno por operación), sin lista `tags` en la raíz y con 11 paths sin barra inicial; no publica `Last-Modified` ni `ETag`, responde 400 a `HEAD` e ignora `rows`/`start`; era idéntico el 5 y el 6 de septiembre de 2026;
- 60 de las 68 fichas con tag casan con el Swagger; 50 declaran un `downloadURL` documentado en su tag; 28 tags documentados no tienen ficha;
- de las 18 fichas cuyo endpoint declarado no devuelve lista, 11 tienen algún path documentado que sí responde, pero solo en 4 la elección es unívoca (`<declarado>/list`); en las otras 7 el tag agrupa varios recursos y el «primer path del tag» mediría otra cosa con el nombre de la ficha.

`ingestion` solo sabía traer listas (`ENVELOPE`, `ARRAY`) con `rows` y `start`.

## Decisión

1. **Nueva forma de respuesta `DOCUMENT` en `ingestion`**: un único objeto JSON que cuenta como un registro; `SourceDescriptor.document(dataset, url)` exige paginación `NONE` y `ZaragozaHttpClient` no añade `rows` ni `start`. Todo lo demás (extensión `.json`, retry, circuit breaker, `raw_payload`, `DatasetIngested`) se aplica igual. El run del Swagger registra `records = 1`.
2. **El inventario vive en `catalog`** (regla 9: no se crea un módulo por dataset): `ApiEndpoint` (`tag`, `method`, `path` con barra inicial, `url` = `schemes[0]://host + basePath + path` leídos del propio documento, `summary` nulo si vacío, `ordinal`, `firstSeenAt`/`lastSeenAt`), tabla `catalog_api_endpoint` (V006) con clave natural `tag + method + path`, y un `IngestionJob` diario (`ApiInventoryIngestionJob`, fuente `sede:catalogo/api`) cuyo `handle` **sincroniza la tabla entera** con el documento: alta, actualización conservando `firstSeenAt`, baja de lo que desaparece. Sin `Last-Modified` ni `ETag`, esa es la única forma de detectar cambios. Un documento que no sea Swagger 2.0 o no tenga `paths` ni `host` hace fallar la ingesta.
3. **Cruce por `apiTag` al leer, sin tabla de enlace.** El detalle de la ficha incluye `apiEndpoints` (operaciones de su tag, con el origen y el `ingestedAt` del inventario y `tagDocumented`); `GET /catalog/api-tags` devuelve la unión de tags del Swagger y tags declarados por las fichas, con el número de operaciones y las fichas de cada uno (0 operaciones = tag declarado que el Swagger no documenta; sin fichas = fuente documentada sin ficha); `GET /catalog/api-endpoints` es el inventario paginado con filtros `tag`, `q` y `templated`; `summary` añade `apiInventory`. Los `caveats` explican que estar documentado no significa responder.
4. **Del Swagger, la observación solo usa `<declarado>/list`.** `ObservationUrls.documentedListUrl` añade, justo después del endpoint declarado, la operación `GET` sin plantilla del tag cuyo path sea el `downloadURL` declarado más `/list`, comparando sin tildes (`clavo-topográfico` frente a `clavo-topografico`) y sin barra final. Ningún otro path del tag se usa; el orden de intento de ADR-005 (API → ficheros → WFS) no cambia. Con el catálogo y el Swagger reales, la regla alcanza a 10 fichas y se activa en las 4 cuyo endpoint declarado falla (asociaciones, clavos, artistas, premios).
5. **Ninguna conclusión nueva en el código** (regla 6): el inventario publica hechos (tag documentado o no, operaciones, marcas de aparición); que un tag no exista en el Swagger o que un `downloadURL` no esté documentado se ve en los datos, no en una etiqueta.

## Consecuencias

- Migración `V006__catalog_api_inventory.sql`: tabla `catalog_api_endpoint` con índice por `tag, ordinal` e índice `catalog_dataset(api_tag)`.
- `CatalogProperties.apiInventory` (`zaragoza.catalog.api-inventory.url|interval`, un día por defecto); `CatalogSources.API_INVENTORY`.
- `DistributionHttpObserver` recibe `ApiEndpointRepository`; por cada ficha con tag y `application/api` hace una lectura local del inventario (68 al día).
- Carga sobre la infraestructura municipal: una petición de 1,3 MB al día, más las observaciones del `/list` cuando el declarado falla (4 fichas).
- Hallazgos para comunicar al ayuntamiento (`docs/ESTADO.md` §6): 8 tags declarados sin documentar, la tilde de `clavo-topográfico` y los 4 índices con `jsessionid` declarados como endpoint.
- El monitor sigue registrando como fallo el endpoint declarado de 14 fichas; las 7 que tienen alternativas ambiguas se quedan así a propósito.

## Alternativas descartadas

- **Módulo `api-inventory` propio**: el Swagger describe la misma API que el catálogo enlaza; separarlo obligaría a un cruce entre módulos por eventos para 68 tags.
- **Tabla de enlace ficha ↔ endpoint mantenida en la ingesta**: el enlace es el tag, que ya vive en `catalog_dataset.api_tag`; recalcularlo en cada ingesta duplicaría el dato.
- **Usar el «primer path sin plantilla del tag» cuando el declarado falla** (como S0.6 en su sondeo): en 7 de 11 fichas elegiría un recurso distinto al de la ficha (líneas de autobús para el tranvía, `organo-resumen` para la ejecución presupuestaria).
- **Detectar cambios del Swagger por hash del cuerpo**: la sincronización entera con `firstSeenAt`/`lastSeenAt` ya deja rastro por operación, que es lo que interesa.
- **Guardar `produces`, `parameters` y `definitions`**: no hacen falta para el cruce; `produces` (`application/geo+json`) y `parameters` (campos ordenables, equivalente al `apiDefinition` de S1.1) quedan como mejora con petición y fixture propios.
- **Ingerir el documento con `rows=1`** para reutilizar la paginación existente: la fuente lo ignora, pero enviaría un parámetro sin sentido en cada petición.
