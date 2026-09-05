# Observatorio de Datos Abiertos de Zaragoza — Especificación inicial

Versión 0.3 · 5 de septiembre de 2026 · Documento de arranque para trabajar con Claude Code. Revisado con los resultados de los spikes S0.1–S0.6 (`docs/spikes/`); las decisiones marcadas «ADR-003 pendiente» siguen abiertas.

Este documento fija el propósito, el alcance, la arquitectura y las reglas de trabajo del proyecto. Es una especificación viva: las decisiones marcadas como "a verificar" deben resolverse con spikes antes de construir sobre ellas, y el documento debe actualizarse cuando se resuelvan.

---

## 1. Propósito

Construir una plataforma que consuma los datos abiertos del Ayuntamiento de Zaragoza (API REST v2) para ofrecer una visión territorial de qué está pasando en la ciudad, a dónde ha ido el dinero público y a dónde está previsto que vaya, junto con una herramienta para que reutilizadores y desarrolladores sepan qué datasets municipales están vivos.

Capacidades iniciales:

1. **Monitor de frescura del catálogo**: qué datasets del catálogo municipal están vivos y cuáles llevan años sin actualizarse, con histórico. Dirigido a reutilizadores y desarrolladores.
2. **Observatorio de inversión y gasto público**: ingesta y análisis de la contratación municipal (OCDS) y, según lo que confirme el inventario de la fase 0, presupuestos participativos, presupuesto municipal y obras/licencias, con localización territorial cuando exista.
3. **Observatorio de quejas y sugerencias**: ingesta y análisis de las incidencias ciudadanas publicadas vía Open311, por territorio, temática y tiempo de respuesta municipal.
4. **Espacio personal opcional**: registro voluntario para guardar búsquedas, dashboards e instantáneas. Nunca obligatorio para usar la plataforma.

El eje que integra las capacidades 2 y 3 es el **territorio** (junta municipal o vecinal: los barrios no existen como dato abierto, S0.4): dónde invierte el ayuntamiento frente a dónde y de qué se quejan los vecinos, normalizado por padrón. **Con los datos abiertos actuales el gasto público no es territorializable** (S0.2, S0.6): el eje territorial se sostiene con incidencias, padrón y sociodemografía, y la comparación inversión–quejas queda condicionada a que el ayuntamiento publique inversión por junta.

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

| Fuente | Endpoint verificado | Uso | Estado (spike) |
|---|---|---|---|
| Catálogo de datasets | `https://www.zaragoza.es/web/espacio-de-datos/servicio/catalogo.json` (436 datasets; `rows` ≤ 500, `start`, `sort`, `fl`; detalle `catalogo/{id}.json`; DCAT `catalogo.rdf`) | Monitor de frescura, inventario | Confirmado (S0.1) |
| Swagger de la API | `https://www.zaragoza.es/sede/servicio/catalogo/api.json` (496 paths, 84 tags) | Inventario de endpoints; enlace dataset → tag vía `formato[].accessURL` | Confirmado (S0.1). No es el catálogo de datasets |
| Contratación pública OCDS | `https://www.zaragoza.es/sede/servicio/contratacion-publica/ocds/contracting-process.json` (extensión obligatoria; `rows` sin tope; `start` ignorado; detalle `contracting-process/{ocid}.json`) | Gasto adjudicado | Confirmado (S0.2). **Sin localización**; 42 % de ocids sin release |
| Quejas y sugerencias | Principal: `https://www.zaragoza.es/sede/servicio/quejas-sugerencias/list.json` (histórico desde 2013, `geometry`, `district`, `sort`, FIQL por fecha). Contraste y taxonomía: `https://www.zaragoza.es/api/recurso/open311/requests.json` y `services.json` (GET público, `ETag` honrado) | Observatorio quejas | Confirmado (S0.3). ~50 % geolocalizadas, ~40 % con junta |
| Presupuesto municipal y ejecución | `https://www.zaragoza.es/sede/servicio/presupuesto/gasto-corriente.json` (+ `/fecha`, 140 snapshots), `ingreso-corriente.json`, resúmenes `gastado-resumen`, `organo-resumen`, `programa-resumen` | Gasto previsto, comprometido y ejecutado por programa/órgano | Confirmado (S0.6). Sin dimensión territorial |
| Subvenciones | `https://www.zaragoza.es/sede/servicio/ayuda-subvencion.json` (paginación propia `page`/`pageSize`) | Gasto ejecutado (ayudas) | Confirmado (S0.6). Sin dimensión territorial |
| Presupuestos participativos | Ningún dataset ni endpoint en el catálogo ni en el Swagger | Inversión prevista por junta | **No disponible** (S0.6). Solicitar al ayuntamiento |
| Obras en vía pública | `https://www.zaragoza.es/sede/servicio/via-publica/incidencia.json` (+ `/conservacion`) | Actividad en calle (solo vigentes, sin importes) | Confirmado (S0.6), utilidad limitada |
| Licencias urbanísticas | `https://www.zaragoza.es/sede/servicio/licencia-obra.json` (2.042 parcelas con `geometry`), `registro-licencia.json` (42.321 locales con `geometry` y `licencias[]`) | Actividad edificatoria y económica **privada**, geolocalizada | Confirmado (S0.6). Fuera del contexto de gasto; candidato posterior |
| Juntas municipales y vecinales (geometrías) | `https://www.zaragoza.es/sede/servicio/distrito.json?srsname=wgs84` (29 polígonos: 15 municipales, 14 vecinales); `distrito/{id}.json` | Shared kernel geográfico | Confirmado (S0.4). **No existen barrios como dato abierto** |
| Padrón / población | `distrito/{id}.json` → `indicadores[]` por año (españoles, extranjeros, menores, hogares, km²); datasets SOCIO24 (padrón 2024 por junta, sección y manzana, GeoJSON EPSG:25830) | Normalización territorial | Confirmado (S0.4) |
| Secciones censales (geometrías) | Dataset 2520 «Secciones Censales» (GeoJSON, 491 secciones, `CUSEC`) | Unidad territorial fina opcional | Confirmado (S0.4) |

### 2.1 Parámetros comunes de la API (documentados)

`fl` (campos), `srsname` (`utm30n` por defecto, `wgs84`), `start`, `rows` (**tope 500 por petición en la sede**; sin tope en OCDS y Open311), `sort=campo asc|desc`, `q` (FIQL; cada endpoint tiene su propia lista blanca de campos filtrables), `point`, `distance`. Formato **siempre por extensión** (`.json`, `.geojson`, `.csv`): la URL sin extensión devuelve HTML o 400 (S0.5). Cabeceras condicionales (S0.5): `Last-Modified` aparece solo en algunos endpoints, con zona `CET`/`CEST` (no RFC 1123), y **`If-Modified-Since` nunca se honra**; solo Open311 devuelve `ETag` y responde 304 a `If-None-Match`. La ingesta incremental se apoya en filtros de fecha (FIQL, `after`) y en comparación con lo almacenado, no en 304.

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

**Estado (2026-09-05)**: spikes ejecutados; informes en `docs/spikes/` (índice y conclusiones en `docs/spikes/README.md`). (a) El cruce territorial inversión–quejas **no es viable** con los datos abiertos actuales; (b) composición del contexto de gasto pendiente de **ADR-003** (propuesta `spending` = OCDS + presupuesto + subvenciones, sin territorio); (c) modelos revisados en §4.6.

### Fase 1 — Núcleo: ingesta y monitor de frescura

- Módulo `catalog`: ingesta periódica del catálogo, registro de frescura por dataset, histórico de cambios, muestreo de registros para observar la frescura real (necesario: el 59 % del catálogo no tiene periodicidad evaluable, S0.1) y detectar datasets vacíos o rotos.
- Módulo `ingestion` (infraestructura): framework genérico de jobs de ingesta con soporte `If-Modified-Since`, reintentos, circuit breaker, registro de ejecuciones (inicio, fin, registros obtenidos, errores).
- API REST de consulta del monitor.
- Entregable: monitor de frescura funcional y publicable.

### Fase 2 — Ciudadanía (quejas y sugerencias)

- Módulo `geo` (shared kernel): juntas municipales y vecinales (29) con geometría, secciones censales (491) opcionales, padrón por junta y año, resolución punto → junta (S0.4: no existen barrios como dato abierto).
- Módulo `citizen`: ingesta del endpoint de sede `quejas-sugerencias/list` (Open311 como contraste), modelo de incidencia, asignación a junta (solo ~50 % vienen geolocalizadas, S0.3), agregaciones por territorio, categoría y tiempo, siempre con el número de incidencias sin asignar; tiempo de respuesta solo en cerradas (`updated_datetime` = cierre).
- Normalización por población en todas las agregaciones territoriales.

### Fase 3 — Inversión y gasto público

- Módulo de gasto público (nombre pendiente de ADR-003; propuesta `spending`): ingesta OCDS, presupuesto (snapshots de ejecución) y subvenciones; modelo de contrato/adjudicatario/adjudicación, partida presupuestaria con snapshot y subvención; agregaciones por área, órgano, adjudicatario, tipo y tiempo. **Sin asignación territorial**: ninguna fuente de gasto la tiene (S0.2, S0.6); no hay propuestas participativas ni obras con importe en datos abiertos.
- Distinción explícita en el modelo entre **gasto ejecutado** (contratos adjudicados, ejecución presupuestaria) y **gasto previsto** (participativos aprobados pendientes, presupuesto aprobado).

### Fase 4 — Cruce territorial y frontend

- Módulo `territory` (composición de lectura, sin estado propio): ficha por junta que compone incidencias, población y sociodemografía a partir de las APIs públicas de los módulos de dominio (el gasto público no es territorializable con los datos actuales; se incorporará si el ayuntamiento publica inversión por junta).
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

- Java 21 (LTS), Spring Boot 4.1.x, Spring Modulith 2.1.x vía BOM (ADR-001). Starters modulares de Boot 4 (`spring-boot-starter-webmvc`, `-restclient`, `-flyway`), Jackson 3, Testcontainers 2.x.
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
├── spending/       # dominio: gasto público (OCDS, presupuesto, subvenciones); nombre definitivo pendiente de ADR-003
├── territory/      # composición de lectura: ficha por barrio (sin estado propio)
├── identity/       # usuarios y autenticación (fase 5)
└── workspace/      # búsquedas guardadas, dashboards, instantáneas (fase 5)
```

Sobre el contexto de gasto: S0.6 confirmó fuentes adicionales de gasto (presupuesto con ejecución, subvenciones) pero **ninguna con dimensión territorial**, y no existen presupuestos participativos ni obras con importe en datos abiertos. Propuesta: contexto `spending` con OCDS + presupuesto + subvenciones y sin entidades territoriales; decisión en ADR-003. No se crea un módulo por dataset.

Reglas de dependencia (verificadas con `ApplicationModules.verify()`):

- `catalog`, `citizen`, `spending` dependen de `ingestion` (a través de puertos) y de `shared`.
- `citizen` depende de `geo` solo a través de su API pública (paquete raíz del módulo); `spending` no depende de `geo` mientras ninguna fuente de gasto tenga territorio.
- `geo` no depende de ningún módulo de dominio.
- `ingestion` no conoce ningún dominio: recibe descripciones de qué traer y devuelve payloads crudos + metadatos de ejecución.
- `territory` depende de las APIs públicas de `citizen` y `geo` (y de `spending` solo si algún día hay gasto territorializado); no tiene tablas propias; ningún módulo depende de `territory`.
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

**catalog** (S0.1): `Dataset` (id municipal, título, descripción, `issued`, `declaredModified` = `modified`, `metadataUpdated` = `lastUpdated`, periodicidad declarada ISO 8601 y días derivados, estado de publicación, `hasGeo`, `open`, `explorable`, `federated`, tag del Swagger, distribuciones con `mediaType`/`accessURL`), `FreshnessSnapshot` (dataset, fecha de observación, frescura *declarada* = ratio antigüedad/periodicidad, frescura *observada* = último cambio detectado en la distribución, número de registros muestreado, estado). Umbrales configurables sobre el ratio (propuesta S0.1: ≤1 en plazo, ≤2 retraso leve, ≤5 retraso, >5 sin actualizar) y categoría explícita «no evaluable por periodicidad» para `NEVER`/`IRREG`/`P0DT1S`/vacío. No se hardcodean juicios.

**geo** (S0.4): `District` (junta municipal o vecinal: id de origen, `padronId`, geometría WGS84), `CensusSection` (`CUSEC`, geometría, junta), `PopulationRecord` (unidad territorial, año, total, españoles, extranjeros, menores, hogares y las desagregaciones que ofrezca la fuente). Servicios `locate(point) -> District` y `locate(point) -> CensusSection`. No existe `Neighbourhood`: los barrios no son dato abierto.

**citizen** (S0.3): `ServiceRequest` (id origen, código y nombre de servicio, título, descripción, estado, `requestedAt`, `closedAt` = `updated_datetime` solo en cerradas, punto WGS84 nullable, dirección textual nullable, nombre de junta de origen nullable, junta resuelta nullable), `Category` (taxonomía `services.json` tal cual más la jerarquía `parent` de `statistics`). No hay canal de entrada en los datos.

**spending** (S0.2, S0.6; nombre pendiente de ADR-003): `ContractingProcess` (ocid, fechas de publicación y release, tags, licitación con importe estimado, procedimiento, categoría, CPV, órgano; **sin localización ni junta**), `Award` (importe, fecha, estado, adjudicatarios), `Supplier`, `Contract` (importe, fecha de firma, periodo, estado), `BudgetSnapshot` (fecha `yyyyMMdd`) con `BudgetLine` (área, programa, órgano, capítulo, partida; crédito inicial, modificaciones, definitivo, comprometido, obligación neta, pago neto), `Grant` (adjudicatario, importe, aplicación presupuestaria, área, línea, instrumento, fecha). Todo registro de gasto lleva `stage` con valores `planned` / `committed` / `executed`: en OCDS solo `planned` (licitación activa) y `committed` (adjudicación/contrato); `executed` sale del presupuesto (obligaciones/pagos) y de subvenciones. Eliminados `ParticipatoryProposal` y `PublicWork` (fuentes inexistentes en datos abiertos).

**identity**: `User` (id interno, proveedor, id en el proveedor, correo, fecha de alta, fecha de último acceso). Nada más.

**workspace**: `SavedQuery` (usuario, nombre, endpoint de dominio, parámetros, fecha), `Dashboard` (usuario, nombre, lista de `SavedQuery` con layout). Segunda oleada: `Snapshot` (usuario, `SavedQuery`, resultado serializado, `ingestedAt` del dato, fecha de captura).

### 4.7 API REST (borrador de superficie)

Prefijo `/api/v1`. Lectura pública; escritura solo en `workspace` e `identity` y siempre autenticada. Paginación uniforme (`page`, `size`), ordenación explícita (`sort=campo,asc|desc`), filtros por query params, respuestas JSON con metadatos de origen (`sourceDataset`, `ingestedAt`).

- `GET /catalog/datasets` · `GET /catalog/datasets/{id}` · `GET /catalog/datasets/{id}/freshness-history` · `GET /catalog/summary`
- `GET /geo/districts` · `GET /geo/districts/{id}` · `GET /geo/census-sections`
- `GET /citizen/requests` (filtros: junta, categoría, rango de fechas, estado) · `GET /citizen/aggregations?by=district|census-section|category|month&normalize=population` (devuelve además `unassigned`: incidencias sin punto)
- `GET /spending/contracts` · `GET /spending/suppliers` · `GET /spending/budget-lines` · `GET /spending/grants` · `GET /spending/aggregations?by=area|organ|supplier|year&stage=planned|committed|executed` (sin dimensión territorial)
- `GET /territory/{districtId}` (fase 4: composición por junta)
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
| Contratos OCDS sin localización utilizable | Cae el cruce territorial para contratación | **Materializado (S0.2): 0 campos de localización en 139 release packages.** Contratación queda sin dimensión territorial |
| Ninguna fuente de inversión con dimensión territorial | El eje integrador se debilita | **Materializado (S0.6): ni presupuesto, ni subvenciones, ni participativos (inexistentes), ni obras con importe.** Quejas + padrón + sociodemografía sostienen el eje territorial; el gasto queda como módulo autónomo |
| Open311 sin geolocalización o sin fecha de cierre | Limita análisis territorial o de tiempo de respuesta | **Resuelto parcialmente (S0.3): ~50 % geolocalizadas, ~40 % con junta, cierre = `updated_datetime`.** Toda agregación expone `unassigned` |
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

- ~~Nombre definitivo del proyecto y del repositorio~~ → `observatorio-zaragoza`, groupId y paquete base `es.zaragoza.observatory` (2026-09-05).
- **ADR-003 pendiente**: nombre y composición del contexto de gasto. Propuesta S0.6: `spending` = OCDS + presupuesto + subvenciones, sin territorio; `licencia-obra`, `registro-licencia`, `locales-vacios` y `via-publica` como candidato posterior `urban-activity` (actividad privada, no inversión).
- Correspondencia exacta entre `distrito.id` e `idpadron`/`id_padron` de los datasets de población (S0.4): verificar en fase 2.
- Tamaño real y criterio de publicación del listado de quejas de sede (50.000–100.000 registros frente a ~40.000 cerradas/año en `statistics`, S0.3): entender antes de publicar volúmenes.
- jOOQ vs. SQL nativo con JPA para agregaciones (decidir en fase 2 con datos reales).
- Retención de `raw_payload` (¿días?, ¿solo último run?).
- Umbrales por defecto de frescura: propuesta en S0.1 (ratio ≤1 / ≤2 / ≤5 / >5 y «no evaluable»); confirmar tras el primer muestreo observado.
- Frontend en el mismo repositorio o separado.
- ~~Si se ingestan más datasets del catálogo con fines de muestreo~~ → necesario para el 59 % no evaluable (S0.1); decidir alcance y coste por dataset en fase 1.
- Instantáneas en `workspace`: formato de serialización y retención (tras uso real de la primera oleada).

---

## 10. Primeros pasos concretos

1. Alta como reutilizador en el portal municipal (pendiente; aprovechar para solicitar datos de inversión por junta y presupuestos participativos).
2. ~~Crear repositorio con esqueleto Spring Boot + Modulith + Maven + Docker Compose (PostGIS) + Flyway + Testcontainers. Copiar §8 a `CLAUDE.md`.~~ Hecho el 2026-09-05 (Boot 4.1.1, ADR-001).
3. ~~Ejecutar spikes S0.1–S0.6 y documentarlos.~~ Hecho el 2026-09-05 (`docs/spikes/`, ADR-002).
4. Revisar este documento con los resultados de los spikes: **v0.3 (este documento)**; queda fijar ADR-003 (contexto de gasto) y confirmar el modelo de fase 1 (§4.6 `catalog`).
5. Implementar módulo `ingestion` y módulo `catalog` (fase 1), con las reglas de cliente HTTP de S0.5.
