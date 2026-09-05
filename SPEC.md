# Observatorio de Datos Abiertos de Zaragoza — Especificación inicial

Versión 0.2 · 5 de septiembre de 2026 · Documento de arranque para trabajar con Claude Code.

Este documento fija el propósito, el alcance, la arquitectura y las reglas de trabajo del proyecto. Es una especificación viva: las decisiones marcadas como "a verificar" deben resolverse con spikes antes de construir sobre ellas, y el documento debe actualizarse cuando se resuelvan.

---

## 1. Propósito

Construir una plataforma que consuma los datos abiertos del Ayuntamiento de Zaragoza (API REST v2) para ofrecer una visión territorial de qué está pasando en la ciudad, a dónde ha ido el dinero público y a dónde está previsto que vaya, junto con una herramienta para que reutilizadores y desarrolladores sepan qué datasets municipales están vivos.

Capacidades iniciales:

1. **Monitor de frescura del catálogo**: qué datasets del catálogo municipal están vivos y cuáles llevan años sin actualizarse, con histórico. Dirigido a reutilizadores y desarrolladores.
2. **Observatorio de inversión y gasto público**: ingesta y análisis de la contratación municipal (OCDS) y, según lo que confirme el inventario de la fase 0, presupuestos participativos, presupuesto municipal y obras/licencias, con localización territorial cuando exista.
3. **Observatorio de quejas y sugerencias**: ingesta y análisis de las incidencias ciudadanas publicadas vía Open311, por territorio, temática y tiempo de respuesta municipal.
4. **Espacio personal opcional**: registro voluntario para guardar búsquedas, dashboards e instantáneas. Nunca obligatorio para usar la plataforma.

El eje que integra las capacidades 2 y 3 es el **territorio** (barrio / junta municipal): dónde invierte el ayuntamiento frente a dónde y de qué se quejan los vecinos, normalizado por padrón.

La capacidad 1 no es un dominio hermano de las otras: es la capa de ingesta y observabilidad que todas necesitan, expuesta públicamente y extendida a todo el catálogo.

### 1.1 Principios no negociables

- **Herramienta de análisis, no de conclusiones.** El sistema expone datos, cruces, series y filtros. No emite juicios ("barrio degradado", "contratación sospechosa"). Las conclusiones son del usuario.
- **Sesgos explicitados en producto.** El volumen de quejas mide propensión a quejarse, no estado del barrio. Cualquier vista que agregue quejas por territorio debe mostrar esa advertencia y ofrecer normalización por población. Lo mismo para cualquier otro sesgo conocido que aparezca durante el desarrollo.
- **No adelantar lecturas.** No se diseñan indicadores compuestos ni etiquetas interpretativas hasta haber visto los datos reales. Primero ingesta y exploración, después métricas.
- **Ser dueños del dato.** Nunca proxy fino sobre la API municipal. Todo se ingesta a base de datos propia con jobs programados y se sirve desde ahí. La API upstream es lenta, con disponibilidad irregular y esquemas que pueden cambiar sin aviso.
- **Trazabilidad.** Cada registro almacenado conserva la referencia al dataset y la fecha de ingesta de origen. Se debe poder responder "de dónde sale este número y cuándo se obtuvo".
- **El backend decide qué datos y en qué orden; el frontend decide cómo se ven.** Ver §4.9.
- **Datos personales mínimos.** El módulo de usuarios guarda lo imprescindible y no gestiona contraseñas propias salvo demanda demostrada.

---

## 2. Fuentes de datos

Todas bajo `https://www.zaragoza.es/sede/servicio/...` (API REST v2). Documentación interactiva (Swagger): `https://www.zaragoza.es/docs-api_sede/`. Documentación general: `https://www.zaragoza.es/sede/portal/datos-abiertos/api`.

| Fuente | Endpoint conocido | Uso | Estado |
|---|---|---|---|
| Catálogo de datasets | `https://www.zaragoza.es/sede/servicio/catalogo/api.json` | Monitor de frescura, inventario | Confirmado |
| Contratación pública OCDS | `https://www.zaragoza.es/sede/servicio/contratacion-publica/ocds/contracting-process/` | Inversión y gasto | Confirmado (usado por Open Contracting Kingfisher Collect) |
| Quejas y sugerencias (Open311) | Documentación en `https://www.zaragoza.es/sede/portal/datos-abiertos/open311` | Observatorio quejas | Endpoint exacto a verificar |
| Presupuestos participativos | A localizar en el catálogo | Inversión prevista por junta | A verificar (hipótesis fuerte: existen desde 2017 y se organizan por junta municipal) |
| Presupuesto municipal y ejecución | A localizar en el catálogo | Gasto agregado | A verificar; probablemente sin dimensión territorial salvo anexos de inversión |
| Obras y licencias urbanísticas | A localizar en el catálogo | Actividad física por barrio | A verificar (existencia, dirección o coordenadas) |
| Padrón / población por barrio | A localizar en el catálogo | Normalización territorial | A verificar (existencia, granularidad, periodicidad) |
| Barrios y juntas municipales (geometrías) | A localizar en el catálogo / IDEZar | Shared kernel geográfico | A verificar |

### 2.1 Parámetros comunes de la API (documentados)

`fl` (campos), `srsname` (`utm30` por defecto, `wgs84`, `etrs89`), `start`, `rows`, `sort`, `q` (FIQL), `point`, `distance`. Formatos por extensión (`.json`, `.geojson`, `.csv`, ...) o cabecera `Accept`. Soporta `ETag`/`If-None-Match` y `Last-Modified`/`If-Modified-Since` (respuesta `304`): **usar siempre** para ingesta incremental barata.

### 2.2 Registro como reutilizador

Darse de alta en el portal como reutilizador: permite registrar las URLs consumidas (el ayuntamiento avisa antes de cambios), publicar la aplicación en su catálogo y obtener apikey si algún endpoint la exige. Hacerlo antes de la fase 1.

---

## 3. Alcance y fases

### Fase 0 — Spikes (sin código de producción)

Objetivo: resolver las incógnitas que condicionan el diseño. Cada spike produce un informe corto en `docs/spikes/` con muestras de datos reales y una recomendación.

- **S0.1 Catálogo**: estructura de `catalogo/api.json`; qué campos de fecha ofrece por dataset (última modificación, frecuencia declarada); cuántos datasets hay; cuántos exponen endpoint consultable.
- **S0.2 OCDS**: volumen de contracting processes; campos disponibles; **proporción de contratos con localización utilizable** (dirección, coordenadas, ámbito territorial). Este dato decide si el cruce por barrio es viable para contratación.
- **S0.3 Open311**: endpoint real, campos, si las incidencias vienen geolocalizadas, si hay fecha de cierre (para tiempo de respuesta), taxonomía de categorías, volumen y profundidad histórica.
- **S0.4 Geo**: existencia de geometrías de barrios/juntas; existencia y granularidad del padrón por barrio.
- **S0.5 Comportamiento de la API**: latencias, límites de `rows`, comportamiento de paginación, fiabilidad de `Last-Modified`, si hay rate limiting.
- **S0.6 Inventario sistemático del catálogo**: recorrer todos los datasets del catálogo y evaluar cada uno con tres preguntas: (a) ¿tiene dimensión territorial utilizable (coordenadas, barrio, junta, dirección)?, (b) ¿tiene dimensión temporal utilizable?, (c) ¿está vivo? Solo los que responden bien a las tres pasan a candidatos. Entregable: matriz en `docs/spikes/S0.6-inventario.md` con la evaluación de cada dataset, los candidatos y, para cada candidato, el bounded context al que pertenecería. Foco especial en fuentes de inversión ejecutada y prevista: presupuestos participativos, presupuesto municipal, obras, licencias, planes de barrio, equipamientos. El inventario **no** crea módulos: alimenta la decisión de §4.3 sobre si `contracting` se mantiene como módulo propio o pasa a ser una fuente dentro de un contexto `investment` más amplio.

Criterio de salida: decisión documentada sobre (a) si el cruce territorial inversión–quejas es viable, (b) la composición definitiva del contexto de inversión y (c) el modelo de datos de cada módulo.

### Fase 1 — Núcleo: ingesta y monitor de frescura

- Módulo `catalog`: ingesta periódica del catálogo, registro de frescura por dataset, histórico de cambios, muestreo opcional de registros para detectar datasets vacíos o rotos.
- Módulo `ingestion` (infraestructura): framework genérico de jobs de ingesta con soporte `If-Modified-Since`, reintentos, circuit breaker, registro de ejecuciones (inicio, fin, registros obtenidos, errores).
- API REST de consulta del monitor.
- Entregable: monitor de frescura funcional y publicable.

### Fase 2 — Ciudadanía (quejas y sugerencias)

- Módulo `geo` (shared kernel): barrios/juntas con geometría, padrón, resolución punto → barrio.
- Módulo `citizen`: ingesta Open311, modelo de incidencia, asignación a barrio (si viene geolocalizada), agregaciones por territorio, categoría y tiempo; tiempo de respuesta cuando haya fecha de cierre.
- Normalización por población en todas las agregaciones territoriales.

### Fase 3 — Inversión y gasto público

- Módulo `investment` (o `contracting` si S0.6 no aporta más fuentes): ingesta OCDS y de las fuentes adicionales confirmadas; modelo de contrato/adjudicatario/adjudicación y, si aplica, de propuesta participativa, partida presupuestaria y obra; agregaciones por área, adjudicatario, tipo, tiempo; asignación territorial donde los datos lo permitan.
- Distinción explícita en el modelo entre **gasto ejecutado** (contratos adjudicados, ejecución presupuestaria) y **gasto previsto** (participativos aprobados pendientes, presupuesto aprobado).

### Fase 4 — Cruce territorial y frontend

- Módulo `territory` (composición de lectura, sin estado propio): ficha por barrio que compone inversión, incidencias y población a partir de las APIs públicas de los módulos de dominio.
- Frontend Angular consumiendo la API. Hasta aquí, la API REST es el único cliente.

### Fase 5 — Espacio personal

- Módulo `identity`: registro y autenticación mediante proveedores externos (OAuth2/OIDC: GitHub y Google), sesión/tokens, perfil mínimo, borrado de cuenta.
- Módulo `workspace`: búsquedas guardadas y dashboards, como **definiciones** (referencia a endpoint de dominio más parámetros y layout), no como datos.
- Segunda oleada de `workspace`, a decidir tras uso real: instantáneas (resultado persistido con su `ingestedAt`), con política de retención explícita.

Fuera de alcance inicial: email/contraseña propios, alertas y notificaciones, compartición pública de dashboards, datasets de movilidad en tiempo real.

---

## 4. Arquitectura

### 4.1 Decisión principal

**Monolito modular con Spring Modulith**, arquitectura hexagonal dentro de cada módulo. No microservicios.

Justificación: el conocimiento del dominio aún no existe; cortar en servicios antes de tenerlo produce cortes equivocados y multiplica infraestructura. Modulith fuerza fronteras verificables por test y permite extraer servicios más tarde si hay motivo. Se revisará si algún módulo desarrolla necesidades de escalado o despliegue independientes, lo que no se espera.

### 4.2 Stack

- Java 21 (LTS), Spring Boot 3.x, Spring Modulith.
- PostgreSQL con PostGIS (necesario para resolución punto → barrio y consultas espaciales). Flyway para migraciones.
- Spring Data JPA para persistencia; consultas analíticas complejas en SQL nativo o jOOQ si JPA se vuelve un obstáculo (decidir en fase 2).
- Scheduling: `@Scheduled` de Spring con ShedLock para evitar ejecuciones concurrentes; no Quartz salvo necesidad demostrada.
- HTTP client: `RestClient` de Spring 6; resiliencia con Resilience4j (retry, circuit breaker, rate limiter).
- Eventos internos: Spring Modulith `ApplicationModuleListener` con event publication registry (persistencia de eventos para garantizar entrega).
- Seguridad (fase 5): Spring Security con OAuth2 Client / Resource Server; sin almacenamiento de contraseñas.
- Observabilidad: Actuator, Micrometer, logs estructurados JSON.
- Build: Maven. Contenedores: Docker Compose para desarrollo (app + PostGIS).
- Frontend (fase 4): Angular, repositorio separado o carpeta `frontend/` — decidir en fase 4.

### 4.3 Módulos

```
observatorio/
├── shared/         # kernel compartido mínimo: tipos de valor comunes (DatasetRef, IngestionRun, UserId), eventos base
├── ingestion/      # infraestructura de ingesta: cliente API Zaragoza, jobs, registro de ejecuciones, resiliencia
├── catalog/        # dominio: datasets del catálogo, frescura, histórico
├── geo/            # shared kernel de dominio: barrios, juntas, padrón, resolución espacial
├── citizen/        # dominio: incidencias Open311 y sus agregaciones
├── investment/     # dominio: contratación OCDS y demás fuentes de gasto ejecutado y previsto (nombre y alcance definitivos tras S0.6)
├── territory/      # composición de lectura: ficha por barrio (sin estado propio)
├── identity/       # usuarios y autenticación (fase 5)
└── workspace/      # búsquedas guardadas, dashboards, instantáneas (fase 5)
```

Sobre `investment`: si S0.6 confirma fuentes adicionales con dimensión territorial (participativos, obras), el contexto se llama `investment` y contratación es una de sus fuentes. Si solo hay OCDS, el módulo se llama `contracting` y punto. No se crea un módulo por dataset.

Reglas de dependencia (verificadas con `ApplicationModules.verify()`):

- `catalog`, `citizen`, `investment` dependen de `ingestion` (a través de puertos) y de `shared`.
- `citizen` e `investment` dependen de `geo` solo a través de su API pública (paquete raíz del módulo).
- `geo` no depende de ningún módulo de dominio.
- `ingestion` no conoce ningún dominio: recibe descripciones de qué traer y devuelve payloads crudos + metadatos de ejecución.
- `territory` depende de las APIs públicas de `citizen`, `investment` y `geo`; no tiene tablas propias; ningún módulo depende de `territory`.
- `workspace` depende de `identity` (solo del concepto de usuario autenticado) y de `shared`. **No depende de ningún módulo de dominio**: guarda referencias a endpoints y parámetros, no datos de dominio.
- `identity` no depende de nada salvo `shared`. Expone hacia el resto un único concepto: el usuario autenticado actual.
- Ningún módulo accede a las tablas de otro. Comunicación entre dominios por eventos.

### 4.4 Hexagonal dentro de cada módulo

```
<modulo>/
├── <Modulo>Api.java / eventos publicados     # superficie pública del módulo (Modulith)
├── domain/        # entidades, value objects, servicios de dominio, puertos (interfaces)
├── application/   # casos de uso, orquestación, transacciones
└── infrastructure/
    ├── persistence/   # adaptadores JPA / SQL
    ├── zaragoza/      # adaptadores hacia la API municipal (anti-corruption layer)
    ├── security/      # solo en identity: configuración Spring Security, proveedores OAuth2
    └── web/           # controladores REST
```

El anti-corruption layer es obligatorio: el modelo de dominio nunca refleja la forma del JSON municipal. Si el esquema upstream cambia, cambia solo el adaptador.

La configuración de Spring Security vive dentro de `identity` como su capa de infraestructura. No existe un módulo `security` separado.

### 4.5 Flujo de ingesta (genérico)

1. El scheduler dispara un job para un `DatasetRef`.
2. `ingestion` consulta la fuente con `If-Modified-Since`/`If-None-Match` del último run exitoso. Si `304`, registra run "sin cambios" y termina.
3. Si hay datos, pagina con `start`/`rows`, aplica retry y circuit breaker, y entrega los payloads crudos al adaptador del módulo de dominio correspondiente.
4. El adaptador traduce y persiste (upsert idempotente por identificador de origen).
5. Se registra `IngestionRun` (dataset, inicio, fin, registros, estado, error) y se publica un evento `DatasetIngested`.
6. `catalog` escucha todos los `DatasetIngested` para alimentar el monitor de frescura, además de su propia ingesta del catálogo.

Idempotencia: cada ingesta debe poder repetirse sin duplicar datos. Los payloads crudos de cada run pueden conservarse un tiempo (tabla `raw_payload` con retención) para depuración y reprocesado.

### 4.6 Modelo de dominio inicial (a refinar tras spikes)

**catalog**: `Dataset` (id municipal, título, descripción, frecuencia declarada, endpoint, formatos), `FreshnessSnapshot` (dataset, fecha de observación, última modificación declarada, última modificación observada, número de registros muestreado, estado: vivo / dudoso / estancado / roto). Los umbrales de estado son configurables y se muestran en la API; no se hardcodean juicios.

**geo**: `District` (junta municipal), `Neighbourhood` (barrio, geometría, junta), `PopulationRecord` (barrio, año, total y desagregaciones que ofrezca el padrón). Servicio `locate(point) -> Neighbourhood`.

**citizen**: `ServiceRequest` (id origen, categoría, subcategoría, descripción, estado, fechas de apertura/cierre, punto, barrio resuelto, canal si existe), `Category` (taxonomía municipal tal cual, sin reagrupar hasta ver los datos).

**investment**: `ContractingProcess` (ocid, título, tipo, procedimiento, órgano, fechas de licitación y adjudicación, importe estimado/adjudicado, localización si existe, barrio resuelto si aplica), `Supplier`, `Award`. Condicionales a S0.6: `ParticipatoryProposal` (junta, estado, importe, año), `BudgetLine` (clasificación, año, previsto/ejecutado), `PublicWork` (dirección/punto, estado, fechas). Todo registro de gasto lleva un atributo `stage` con valores `planned` / `committed` / `executed` para no mezclar previsto y ejecutado.

**identity**: `User` (id interno, proveedor, id en el proveedor, correo, fecha de alta, fecha de último acceso). Nada más.

**workspace**: `SavedQuery` (usuario, nombre, endpoint de dominio, parámetros, fecha), `Dashboard` (usuario, nombre, lista de `SavedQuery` con layout). Segunda oleada: `Snapshot` (usuario, `SavedQuery`, resultado serializado, `ingestedAt` del dato, fecha de captura).

### 4.7 API REST (borrador de superficie)

Prefijo `/api/v1`. Lectura pública; escritura solo en `workspace` e `identity` y siempre autenticada. Paginación uniforme (`page`, `size`), ordenación explícita (`sort=campo,asc|desc`), filtros por query params, respuestas JSON con metadatos de origen (`sourceDataset`, `ingestedAt`).

- `GET /catalog/datasets` · `GET /catalog/datasets/{id}` · `GET /catalog/datasets/{id}/freshness-history` · `GET /catalog/summary`
- `GET /geo/neighbourhoods` · `GET /geo/neighbourhoods/{id}`
- `GET /citizen/requests` (filtros: barrio, categoría, rango de fechas, estado) · `GET /citizen/aggregations?by=neighbourhood|category|month&normalize=population`
- `GET /investment/contracts` · `GET /investment/suppliers` · `GET /investment/aggregations?by=area|supplier|neighbourhood|year&stage=planned|committed|executed`
- `GET /territory/{neighbourhoodId}` (fase 4: composición por barrio)
- `POST /auth/login/{provider}` (redirección OAuth2) · `GET /me` · `DELETE /me` (fase 5)
- `GET|POST|PUT|DELETE /workspace/queries` · `GET|POST|PUT|DELETE /workspace/dashboards` (fase 5)

Las agregaciones territoriales devuelven siempre el denominador poblacional usado y un campo `caveats` con las advertencias de sesgo aplicables.

### 4.8 Composición de lectura

Cuando un caso de uso necesita datos de varios dominios en una sola respuesta (la ficha territorial), se resuelve con un módulo de composición (`territory`) que depende de las APIs públicas de los dominios implicados, no tiene estado propio y no es dependencia de nadie. Nunca se resuelve haciendo que un módulo de dominio conozca a otro ni haciendo que `workspace` sirva datos de dominio.

### 4.9 Reparto de responsabilidades backend / frontend

Backend (cada módulo de dominio sobre sus propios datos):
- Filtrado, ordenación, paginación y agregación. Los criterios de ordenación son explícitos en la petición y documentados.
- Cálculo de métricas, normalizaciones y caveats.
- Read models diseñados para consumo directo: la API debe ser útil sin el frontend propio, porque es un producto para otros reutilizadores.

Frontend (Angular):
- Presentación: layout, gráficos, mapas.
- Interacción local sobre la página ya servida (mostrar/ocultar, reordenar visualmente una página pequeña ya recibida, resaltar).
- Rehidratación de dashboards: lee las definiciones de `workspace` y pide cada dato a su endpoint de dominio.

Regla: **el backend decide qué datos y en qué orden; el frontend decide cómo se ven.** Nunca paginación en servidor con ordenación en cliente.

---

## 5. Requisitos no funcionales

- **Carga**: uso personal/comunitario, decenas de usuarios. Sin objetivos de escalado horizontal.
- **Ingesta**: jobs diarios por defecto; catálogo cada 6 h; ajustable por dataset. Una ejecución completa nunca debe degradar la API de lectura.
- **Tolerancia a fallos upstream**: caída de la API municipal no afecta a la lectura; se sirve el último dato bueno con su fecha visible.
- **Coste**: desplegable en un VPS pequeño o PaaS gratuito/barato. Una sola instancia.
- **Licencia y atribución**: cumplir la licencia de reutilización del ayuntamiento; mostrar atribución y fecha de origen en toda respuesta.
- **Protección de datos (fase 5)**: solo los campos de `identity` listados en §4.6; borrado de cuenta completo y en cascada sobre `workspace`; aviso de privacidad publicado antes de abrir el registro.

---

## 6. Estrategia de pruebas

- **Unitarias** en dominio y aplicación, sin Spring context.
- **Arquitectura**: `ApplicationModules.verify()` de Modulith en CI; ArchUnit para hexagonal dentro de cada módulo (dominio no importa infraestructura).
- **Integración** con Testcontainers (PostGIS real). Sin H2.
- **Adaptadores upstream**: tests contra fixtures grabados de respuestas reales (WireMock), guardados en `src/test/resources/fixtures/zaragoza/`. Los fixtures se refrescan con un script, no a mano. Cada cambio de esquema detectado se convierte en un test.
- **Calidad de datos**: tests de propiedades sobre lo ingerido (no hay duplicados por id de origen, todos los puntos caen dentro del término municipal o se marcan como no resueltos, fechas coherentes, `stage` siempre informado en inversión).
- **Contrato de la API propia**: tests de controladores con documentación generada (Spring REST Docs o springdoc-openapi).
- **Seguridad (fase 5)**: tests de que ningún endpoint de lectura exige autenticación y de que ningún endpoint de escritura funciona sin ella; tests de aislamiento entre usuarios en `workspace`.

---

## 7. Riesgos

| Riesgo | Impacto | Mitigación |
|---|---|---|
| Contratos OCDS sin localización utilizable | Cae el cruce territorial para contratación | S0.2 antes de diseñar; contratación queda sin dimensión territorial y el cruce se apoya en participativos y obras si S0.6 los confirma |
| Ninguna fuente de inversión con dimensión territorial | El eje integrador se debilita | Quejas + padrón funcionan solas; inversión queda como módulo autónomo |
| Open311 sin geolocalización o sin fecha de cierre | Limita análisis territorial o de tiempo de respuesta | S0.3; degradar funcionalidad, no inventar |
| Cambios de esquema upstream sin aviso | Rotura de ingesta | ACL + fixtures + alta como reutilizador + alertas sobre runs fallidos |
| API municipal lenta o caída | Ingesta incompleta | Resiliencia, ingesta incremental, servir último dato bueno |
| Deriva hacia conclusiones editoriales | Contradice el propósito | Revisión explícita de cada vista contra los principios de §1.1 |
| Deriva de alcance por el inventario ("visión de conjunto") | Explosión de módulos y datasets | Regla: un bounded context, no un módulo por dataset; candidatos pasan las tres preguntas de S0.6 |
| `workspace` como módulo-dios | Rompe las fronteras de Modulith | Regla de dependencia explícita: `workspace` no conoce ningún dominio |
| Coste de gestionar usuarios (RGPD, cuentas) | Carga de mantenimiento desproporcionada | Solo OAuth2 externo, datos mínimos, borrado completo, fase 5 y no antes |
| Sobreingeniería temprana | Proyecto no llega a entregar | Fases estrictas; fase 1 publicable por sí sola |

---

## 8. Reglas de trabajo para Claude Code

Estas reglas deben copiarse a `CLAUDE.md` en la raíz del repositorio.

1. **No inventar endpoints, campos ni formatos de la API municipal.** Si no está confirmado en este documento o en `docs/spikes/`, se consulta con una petición real o se pregunta. Los campos se documentan con ejemplos de respuesta reales.
2. **Spikes antes de código de producción** para cualquier fuente nueva. Un spike es un script o test exploratorio más un informe en `docs/spikes/`.
3. **Respetar las fronteras de módulo.** Cada cambio debe pasar `ApplicationModules.verify()`. No se accede a tablas ni a clases internas de otro módulo. En particular: `workspace` nunca depende de un módulo de dominio; `territory` nunca tiene tablas propias.
4. **Hexagonal estricta**: el paquete `domain` no importa Spring, JPA ni nada de `infrastructure`. Los puertos son interfaces del dominio; los adaptadores viven en `infrastructure`.
5. **Idempotencia en toda ingesta.** Reejecutar un job no duplica datos.
6. **Nada de conclusiones en el código.** No se introducen etiquetas interpretativas ("degradado", "sospechoso", "anómalo") ni indicadores compuestos sin una decisión explícita documentada en `docs/decisions/` (formato ADR).
7. **Toda agregación territorial expone denominador y caveats.**
8. **El backend ordena, filtra, pagina y agrega; el frontend pinta.** Ningún endpoint devuelve listas sin criterio de ordenación explícito.
9. **No se crea un módulo por dataset.** Una fuente nueva se asigna a un bounded context existente salvo decisión documentada en ADR.
10. **Tests antes de considerar algo terminado.** Sin Testcontainers en verde, no está hecho.
11. **Decisiones de arquitectura como ADR** en `docs/decisions/`, numeradas. Este documento es la ADR-000.
12. **Idioma**: código e identificadores en inglés; documentación, ADRs y textos de usuario en español.
13. **Commits pequeños y descriptivos**; una funcionalidad por rama.
14. **Preguntar antes de asumir** cuando una decisión no esté cubierta aquí y afecte a modelo de datos, fronteras de módulo, superficie de API o datos personales.
15. **Scaffolding y dependencias a través de las herramientas de cada ecosistema, nunca de memoria.** El objetivo es que ninguna coordenada, nombre de paquete ni versión se escriba recordada: siempre resuelta por la herramienta o verificada contra el repositorio oficial.
    - **Maven**: el proyecto se genera con Spring Initializr (CLI `spring init` o `curl` a `start.spring.io`) declarando ahí todas las dependencias iniciales; se usa el wrapper `./mvnw` generado, nunca un `mvn` global. Las dependencias posteriores se añaden al `pom.xml` **sin `<version>` explícita**, delegando en el BOM del parent de Spring Boot y en el BOM de Spring Modulith. Solo se escribe una versión cuando ningún BOM la gestiona, y en ese caso se verifica antes contra Maven Central (búsqueda o `./mvnw dependency:resolve`); no se escribe de memoria.
    - **Node / Angular**: el proyecto se crea con `ng new`; los paquetes se añaden con `npm install <paquete>` (o `ng add`), nunca editando `package.json` a mano. El lockfile se versiona y se instala con `npm ci`.
    - **Verificación inmediata**: tras cualquier cambio de dependencias o de scaffolding, compilación y tests en el mismo paso (`./mvnw verify`, `npm ci && npm run build`). No se encadena un segundo cambio sobre uno que no compila.
    - Ficheros de configuración de herramientas (`pom.xml`, `package.json`, `angular.json`) se editan a mano solo para lo que la herramienta no cubre (plugins de build, perfiles, scripts), y siempre seguido de la verificación anterior.

---

## 9. Decisiones abiertas

- Nombre definitivo del proyecto y del repositorio.
- Nombre y composición definitiva del contexto de inversión (`investment` vs `contracting`): tras S0.6.
- jOOQ vs. SQL nativo con JPA para agregaciones (decidir en fase 2 con datos reales).
- Retención de `raw_payload` (¿días?, ¿solo último run?).
- Umbrales por defecto de frescura (vivo / dudoso / estancado): proponer tras S0.1 viendo la distribución real de fechas.
- Frontend en el mismo repositorio o separado.
- Si se ingestan más datasets del catálogo con fines de muestreo para el monitor (coste vs. valor).
- Instantáneas en `workspace`: formato de serialización y retención (tras uso real de la primera oleada).

---

## 10. Primeros pasos concretos

1. Alta como reutilizador en el portal municipal.
2. Crear repositorio con esqueleto Spring Boot 3 + Modulith + Maven + Docker Compose (PostGIS) + Flyway + Testcontainers. Copiar §8 a `CLAUDE.md`.
3. Ejecutar spikes S0.1–S0.6 y documentarlos. S0.6 es el más largo; puede solaparse con S0.2–S0.5.
4. Revisar este documento con los resultados de los spikes: fijar el modelo de datos de fase 1 y la composición del contexto de inversión.
5. Implementar módulo `ingestion` y módulo `catalog` (fase 1).
