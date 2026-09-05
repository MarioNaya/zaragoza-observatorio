# Spikes de fase 0

Cada spike responde una incógnita de `SPEC.md` §3 con datos reales de la API municipal. Su código es un test JUnit `@Tag("spike")` (ver ADR-002) y su resultado es un informe en esta carpeta. **Lo que aquí se documenta es la única fuente válida de endpoints, campos y formatos para el resto del proyecto.**

Ejecución: `.\mvnw.cmd test -Pspikes` (todos) o `.\mvnw.cmd test -Pspikes "-Dtest=S01*"` (uno). Requieren red; no forman parte del build por defecto.

## Índice

| Spike | Pregunta | Clase | Informe | Estado |
|---|---|---|---|---|
| S0.1 | Estructura y fechas del catálogo de datasets | `S01CatalogSpike` | `S0.1-catalogo.md` | pendiente |
| S0.2 | Volumen, campos y localización en OCDS | `S02OcdsSpike` | `S0.2-ocds.md` | pendiente |
| S0.3 | Open311: endpoint, geolocalización, cierre, taxonomía | `S03Open311Spike` | `S0.3-open311.md` | pendiente |
| S0.4 | Geometrías de juntas/barrios y padrón | `S04GeoSpike` | `S0.4-geo.md` | pendiente |
| S0.5 | Comportamiento de la API: límites, paginación, cabeceras | `S05ApiBehaviourSpike` | `S0.5-api.md` | pendiente |
| S0.6 | Inventario sistemático del catálogo | `S06InventorySpike` | `S0.6-inventario.md` | pendiente |

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
