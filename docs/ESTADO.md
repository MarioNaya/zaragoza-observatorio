# Estado del proyecto y arranque de sesión

Última actualización: 2026-09-05 (cierre de la fase 0). Este documento es el punto de entrada de cada sesión de trabajo: qué está hecho, qué decisiones rigen, cómo se arranca el entorno y cuál es el siguiente paso concreto. Se actualiza al cerrar cada sesión.

## 1. Dónde estamos

| Fase (SPEC.md §3) | Estado | Evidencia |
|---|---|---|
| Paso 2 de §10: esqueleto | **Hecho** | Spring Boot 4.1.1 + Modulith 2.1.1 + Java 21, PostGIS en Compose y Testcontainers, Flyway, módulos `shared`/`ingestion`/`catalog` declarados, tests de arquitectura (Modulith + ArchUnit). `.\mvnw.cmd verify` en verde (5 tests) |
| Fase 0: spikes S0.1–S0.6 | **Hecho** | Seis clases `@Tag("spike")`, fixtures reales en `src/test/resources/fixtures/zaragoza/` (11 MB), informes en `docs/spikes/`, conclusiones en `docs/spikes/README.md` |
| Paso 4 de §10: revisar la especificación | **Hecho** | `SPEC.md` v0.4; ADR-001 (Boot 4), ADR-002 (spikes JUnit), ADR-003 (`spending`) |
| Fase 1: `ingestion` + `catalog` | **Siguiente** | Ver §4 de este documento |
| Fases 2–5 | Pendientes | `geo`, `citizen`, `spending`, `territory`, frontend, `identity`, `workspace` |

Conclusiones que condicionan todo lo demás:

- **La unidad territorial es la junta municipal o vecinal (29)**, con la sección censal (491) como opción fina. No existen barrios como dato abierto (S0.4).
- **El gasto público no es territorializable**: OCDS, presupuesto y subvenciones no tienen localización; los presupuestos participativos no existen como dato abierto (S0.2, S0.6, ADR-003). El eje territorial se sostiene con incidencias (50 % geolocalizadas), padrón y sociodemografía.
- **La API municipal no honra `If-Modified-Since`** (solo Open311 honra `ETag`); la extensión `.json` es obligatoria; `rows` tope 500 en la sede (S0.5).
- **El 59 % del catálogo no es evaluable por periodicidad declarada**: el monitor de frescura necesita observar el dato, no solo el metadato (S0.1).

## 2. Decisiones vigentes

| ADR | Decisión |
|---|---|
| [ADR-000](decisions/ADR-000-especificacion-inicial.md) | `SPEC.md` es la especificación viva y fundacional |
| [ADR-001](decisions/ADR-001-spring-boot-4.md) | Spring Boot 4.1.x, Modulith 2.1.x vía BOM, Java 21 (Initializr ya no ofrece 3.x) |
| [ADR-002](decisions/ADR-002-spikes-como-tests-junit.md) | Los spikes son tests JUnit `@Tag("spike")`, fuera del build por defecto (`-Pspikes`) |
| [ADR-003](decisions/ADR-003-contexto-spending.md) | El gasto público es el contexto `spending` (OCDS + presupuesto + subvenciones), sin entidades territoriales; no depende de `geo` |

Nombre del proyecto: `observatorio-zaragoza`; groupId y paquete base `es.zaragoza.observatory`. La carpeta local sigue llamándose `api-ayto` hasta que el usuario la renombre.

## 3. Cómo arrancar una sesión

1. Arrancar Docker Desktop y comprobar `docker info` (Testcontainers y Compose lo necesitan).
2. `.\mvnw.cmd -v` debe decir Java 21 (el `java` del PATH es Java 8; el wrapper usa `JAVA_HOME`).
3. `.\mvnw.cmd verify`: build completo con PostGIS real; debe estar en verde antes de tocar nada.
4. Leer `CLAUDE.md` (reglas 1–19) y, para cualquier endpoint, `docs/spikes/README.md` y el informe correspondiente. Nunca escribir un endpoint o campo de memoria.
5. Trabajo en rama por funcionalidad (`feat/…`), commits pequeños, `main` siempre en verde.

Comandos útiles:

```powershell
.\mvnw.cmd verify                          # build + tests (Testcontainers)
.\mvnw.cmd test -Pspikes                   # reejecutar todos los spikes (red real, ~4 min)
.\mvnw.cmd test -Pspikes "-Dtest=S01*"     # un spike; deja métricas en target/spikes/
.\mvnw.cmd spring-boot:run                 # arranca la app con PostGIS vía Docker Compose
```

Al arrancar la app, `GET http://localhost:8080/actuator/health` debe responder `UP` y `/actuator/modulith` listar los módulos.

## 4. Siguiente paso: fase 1 (`ingestion` y `catalog`)

Objetivo (SPEC.md §3 fase 1): monitor de frescura funcional y publicable. Orden propuesto, cada punto con sus tests antes de darlo por hecho (regla 10):

1. **Registro de eventos Modulith.** Decidir entre `spring-modulith-starter-jpa` (lo que trae el esqueleto) y `spring-modulith-starter-jdbc`; crear la migración Flyway de `event_publication` y pasar `spring.jpa.hibernate.ddl-auto` a `validate`. Documentar como ADR-004 si cambia el starter.
2. **Dependencias fuera de los BOM** (regla 15): Resilience4j y ShedLock. Verificar en Maven Central versión y compatibilidad con Boot 4 antes de añadirlas; si ShedLock no aporta nada con una sola instancia, dejarlo documentado y posponerlo.
3. **`shared`**: `DatasetRef` (fuente + identificador municipal), `IngestionRunId`, evento base `DatasetIngested` (dataset, run, registros, `ingestedAt`).
4. **`ingestion`**: dominio (`SourceDescriptor` con URL `.json`, `rows`, ventanas; `IngestionRun` con inicio, fin, registros, estado, error; puertos `SourceGateway`, `IngestionRunRepository`, `RawPayloadStore`), aplicación (`RunIngestion` idempotente), infraestructura (`ZaragozaHttpClient` con las reglas de S0.5: extensión `.json`, `srsname=wgs84`, `rows=500`, timeouts ≥ 30 s, ≤ 4 conexiones, retry solo en 5xx/timeout, circuit breaker por fuente; JPA para `ingestion_run` y `raw_payload` con retención; scheduler). Migración `V002__ingestion.sql`. Tests con WireMock sobre los fixtures de `src/test/resources/fixtures/zaragoza/` (grabar cabeceras reales, incluido `Last-Modified` con `CEST`).
5. **`catalog`**: dominio (`Dataset`, `Distribution`, `FreshnessSnapshot`, `FreshnessPolicy` con umbrales configurables y categoría «no evaluable»), adaptador del catálogo (`web/espacio-de-datos/servicio/catalogo.json?rows=500&start=…&fl=…`, campos de S0.1), enlace con el Swagger por tag, job de snapshot diario, muestreo observado mínimo (ficheros: `Last-Modified`/tamaño; API: máximo de `lastUpdated`/`modified` en una página), API REST `GET /api/v1/catalog/datasets`, `/{id}`, `/{id}/freshness-history`, `/summary` con paginación, `sort` explícito, `sourceDataset` e `ingestedAt`. Elegir springdoc-openapi o Spring REST Docs (verificar compatibilidad con Boot 4) y documentar.
6. **Calidad de datos** (SPEC.md §6): tests de propiedades sobre lo ingerido (sin duplicados por id de origen, fechas coherentes) usando `docs/spikes/S0.6-inventario-matriz.csv` como referencia de los 436 datasets.

Criterio de «hecho» de la fase 1: `verify` en verde con Testcontainers, `ApplicationModules.verify()` sin violaciones, ArchUnit sin `allowEmptyShould` en `catalog`, fixtures WireMock refrescables con el spike S0.1, y el monitor publicable en una instancia.

## 5. Pendientes del usuario

- Alta como reutilizador en el portal municipal (SPEC.md §2.2). Aprovechar para pedir inversión por junta y presupuestos participativos como datos abiertos.
- Renombrar la carpeta `api-ayto` a `observatorio-zaragoza` (fuera de la sesión de Claude Code).
- Crear el remoto Git cuando se quiera publicar (hoy el repositorio es solo local, rama `main`).

## 6. Dudas abiertas

Listadas en `SPEC.md` §9. Las que tocan a la fase 1: registro de eventos Modulith (punto 1 de §4), umbrales de frescura (propuesta S0.1, confirmar tras el primer muestreo), retención de `raw_payload`, alcance del muestreo observado por dataset.

## 7. Mapa del repositorio

```
SPEC.md                         especificación viva (v0.4, ADR-000)
CLAUDE.md                       reglas de trabajo y contexto operativo
README.md                       presentación breve y enlaces
docs/ESTADO.md                  este documento
docs/arquitectura.md            diagramas (flujo de datos, módulos, hexagonal)
docs/decisions/                 ADR-000..003
docs/spikes/                    informes S0.1..S0.6, matriz CSV, índice con conclusiones
pom.xml, compose.yaml           Boot 4.1.1, perfil -Pspikes, PostGIS 17-3.5
src/main/java/es/zaragoza/observatory/{shared,ingestion,catalog}/package-info.java
src/main/resources/application.yaml, db/migration/V001__postgis_extension.sql
src/test/java/es/zaragoza/observatory/{ModularityTests,HexagonalArchitectureTests,TestcontainersConfiguration}.java
src/test/java/es/zaragoza/observatory/spikes/                 S01..S06 + support/
src/test/resources/fixtures/zaragoza/{catalog,ocds,open311,geo,inventory}/
```
