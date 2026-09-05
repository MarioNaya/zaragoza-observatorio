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
.\mvnw.cmd spring-boot:run     # app + PostGIS vía Docker Compose
.\mvnw.cmd test -Pspikes       # spikes exploratorios contra la API real
```

Datos: Ayuntamiento de Zaragoza, portal de datos abiertos (`https://www.zaragoza.es/sede/portal/datos-abiertos/`). Cada respuesta de la API propia indica el dataset de origen y la fecha de ingesta.
