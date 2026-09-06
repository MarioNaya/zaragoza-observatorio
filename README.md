# Observatorio de Datos Abiertos de Zaragoza

Plataforma que ingesta los datos abiertos del Ayuntamiento de Zaragoza (API REST v2) en una base de datos propia y los sirve por una API REST para ofrecer: un monitor de frescura del catálogo municipal, un observatorio de quejas y sugerencias por junta, y un observatorio de gasto público (contratación OCDS, presupuesto y subvenciones). Herramienta de análisis, no de conclusiones.

Monolito modular con Spring Boot 4.1 y Spring Modulith, arquitectura hexagonal por módulo, PostgreSQL + PostGIS, Java 21.

## Empezar

- **Estado del proyecto y siguiente paso**: [`docs/ESTADO.md`](docs/ESTADO.md)
- **Especificación viva**: [`SPEC.md`](SPEC.md)
- **Reglas de trabajo**: [`CLAUDE.md`](CLAUDE.md)
- **Arquitectura (diagramas)**: [`docs/arquitectura.md`](docs/arquitectura.md)
- **Decisiones (ADR)**: [`docs/decisions/`](docs/decisions/)
- **Hechos verificados sobre la API municipal**: [`docs/spikes/`](docs/spikes/README.md)

```powershell
.\mvnw.cmd verify              # requiere Docker en marcha (Testcontainers con PostGIS)
.\mvnw.cmd spring-boot:run     # app + PostGIS vía Docker Compose (añadir "-Dspring-boot.run.arguments=--server.port=8085" si el 8080 está ocupado)
.\mvnw.cmd test -Pspikes       # spikes exploratorios contra la API real
```

## API (fase 1: monitor de frescura del catálogo)

Lectura pública, sin clave. Toda respuesta lleva `source` (dataset municipal y URL consultada), `ingestedAt` (fin de la última ingesta con éxito) y `caveats`.

| Endpoint | Qué devuelve |
|---|---|
| `GET /api/v1/catalog/datasets` | Fichas del catálogo paginadas (`page`, `size` ≤ 200) y ordenadas (`sort=title,asc`; campos `title`, `id`, `issued`, `declaredModified`, `metadataUpdated`, `declaredRatio`), con filtros `periodicity`, `status`, `hasGeo`, `open`, `hasApi`, `freshness`, `q` |
| `GET /api/v1/catalog/datasets/{id}` | Ficha completa, distribuciones y última instantánea de frescura |
| `GET /api/v1/catalog/datasets/{id}/freshness-history` | Histórico de instantáneas, la más reciente primero (`limit`) |
| `GET /api/v1/catalog/summary` | Recuentos por categoría de frescura declarada y por periodicidad, y umbrales vigentes |
| `GET /v3/api-docs` · `/swagger-ui.html` | Contrato OpenAPI 3 y su interfaz |

La frescura *declarada* compara `modified` con `accrualPeriodicity` (ambos declarados por el publicador) contra umbrales configurables; `NOT_EVALUABLE` agrupa las fichas sin periodicidad evaluable o sin `modified` (la mayoría). La frescura *observada* (muestreo de distribuciones) llegará al cerrar la fase 1.

Datos: Ayuntamiento de Zaragoza, portal de datos abiertos (`https://www.zaragoza.es/sede/portal/datos-abiertos/`), bajo su licencia de reutilización. Cada respuesta de la API propia indica el dataset de origen y la fecha de ingesta.
