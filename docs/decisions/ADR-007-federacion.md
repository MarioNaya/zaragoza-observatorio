# ADR-007 — Federación en datos.gob.es: tabla propia sincronizada por evento y cruce por `sourceId`

- **Fecha**: 2026-09-06
- **Estado**: aceptada
- **Afecta a**: `SPEC.md` §2, §3 (fase 1), §4.5, §4.6 (`catalog`), §4.7, §9; `CLAUDE.md` regla 25; `docs/ESTADO.md` §4
- **Se apoya en**: `docs/spikes/S1.3-federacion.md` (19 peticiones reales el 2026-09-06), `docs/spikes/S0.1-catalogo.md`, ADR-004, ADR-006

## Contexto

SPEC.md §4.6 dejó la marca `federated` («aparece en RDF/datos.gob.es») pendiente hasta poder rellenarla. El spike S1.3 midió la API de datos.gob.es para el publicador municipal (`L01502973`) y su cruce con el catálogo:

- es una Linked Data API con paginación propia (`_page` desde 0, `_pageSize` con tope efectivo de 200; el enlace `next` pierde el `_pageSize`), sin recuento total, sin `Last-Modified` ni `ETag`; 369 datasets en dos páginas;
- cada item lleva en `identifier` la URL municipal de la ficha con su `id` (369 de 369): ese es el enlace;
- 261 datasets están en los dos sitios (todos `abierto=S`); 175 fichas del catálogo no están federadas (149 `abierto=N`, 11 vacío, 15 abiertas); y **108 datasets federados no aparecen en el listado `catalogo.json`** aunque existen en el detalle municipal: son partes de series y colecciones (`datasetRelacionado` con `IS_PART_OF`) que el listado de primer nivel no devuelve;
- el `modified` de datos.gob.es coincide con el del catálogo en los 237 casos comparables.

`ingestion` solo paginaba por `start`/`rows` y solo leía envoltorios de la sede, arrays y documentos.

## Decisión

1. **`ingestion` aprende la paginación por número de página con nombres propios** (`Pagination.pages(rows, pageParam, rowsParam)`, modo `PAGE`) y la forma `RESULT_ITEMS` (`result.items[]`); la fuente `datos-gob-es` valida el tope de 200. La regla de avance es la misma que sin `totalCount`: mientras la página venga llena. `next` no se sigue.
2. **Tabla propia `catalog_federated_dataset`** (V007: `source_id` del `identifier`, `url` = `_about`, `title`, `firstSeenAt`/`lastSeenAt`), no una columna en `catalog_dataset`: así se conservan los 108 datasets federados sin ficha en el listado, que son un hecho sobre el catálogo municipal. `federated` no se almacena: se resuelve al leer (la ficha tiene fila en la tabla).
3. **Sincronización por evento.** El listado llega en varias páginas, así que `handle(RawPage)` hace upsert por página (`RegisterFederatedDatasets`) y, al completarse la ejecución, el listener de `DatasetIngested` da de baja los datasets con `lastSeenAt` anterior al inicio de esa ejecución (`purgeNotSeenSince(run.startedAt)`). Sin marca de cambio en la fuente, es la única forma de detectar altas y bajas; una ejecución fallida no borra nada porque no publica evento.
4. **Exposición**: `federated` y `federatedUrl` en listado y detalle de la ficha, filtro `federated=true|false`, `federation` en `summary` (federados, con ficha, sin ficha, fichas sin federar) y `GET /catalog/federation` con los datasets federados, su `inCatalog` (filtro y texto) y los `caveats` que explican qué son los que no tienen ficha. `modified` de datos.gob.es no se guarda.
5. **Las partes de series y colecciones no se ingieren todavía.** Queda abierto en SPEC.md §9 para la fase 2, con spike propio: el detalle `catalogo/{id}.json` las expone con `datasetRelacionado` (`IS_PART_OF`/`HAS_PART`) y `series[]`; ingerirlas exige recorrer detalles y decidir el modelo de la jerarquía.

## Consecuencias

- Migración `V007__catalog_federation.sql`; `CatalogProperties.federation` (`zaragoza.catalog.federation.url|page-size|interval`); `CatalogSources.FEDERATION` y `Sources.DATOS_GOB_ES`.
- Dos peticiones de ~1 MB al día a datos.gob.es, identificadas con el mismo `User-Agent`.
- El catálogo ingerido (436 fichas) es un subconjunto de lo publicado (al menos 544 fichas): el monitor de frescura no ve las partes de series. Se declara en `caveats` y en `summary.federation.notInCatalog`.
- Hallazgos para comunicar al ayuntamiento (`docs/ESTADO.md` §6): 15 fichas abiertas sin federar y el hecho de que el listado JSON omite las partes de series.

## Alternativas descartadas

- **Columna `federated` en `catalog_dataset` rellenada por la ingesta**: perdería los 108 federados sin ficha y obligaría a un segundo mecanismo para desmarcar.
- **Seguir `result.next`**: pierde `_pageSize` y devuelve páginas de 10 (37 peticiones en vez de 2).
- **Ingerir `catalogo.rdf` (DCAT municipal)**: RDF/XML, fuera del cliente JSON de `ingestion`; y no dice qué está federado, solo qué se publica en RDF.
- **Guardar el `modified` federado**: coincide siempre con el municipal; el texto en castellano con offset no aporta nada nuevo.
- **Acumular las páginas en el job y sincronizar al final**: el job no sabe cuál es la última página sin acoplarse a la paginación; el evento de fin de ejecución ya existe y es transaccional.
