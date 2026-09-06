# CLAUDE.md — Observatorio de Datos Abiertos de Zaragoza

La especificación viva del proyecto es `SPEC.md` (ADR-000). Las decisiones de arquitectura están en `docs/decisions/`. Los hechos verificados sobre la API municipal están en `docs/spikes/`: **son la única fuente válida de endpoints, campos y formatos**; nada se escribe de memoria. **Al empezar una sesión, leer `docs/ESTADO.md`** (qué está hecho, qué toca ahora, cómo arrancar). Los diagramas de arquitectura están en `docs/arquitectura.md`.

## Reglas de trabajo (copia de SPEC.md §8)

1. **No inventar endpoints, campos ni formatos de la API municipal.** Si no está confirmado en `SPEC.md` o en `docs/spikes/`, se consulta con una petición real o se pregunta. Los campos se documentan con ejemplos de respuesta reales.
2. **Spikes antes de código de producción** para cualquier fuente nueva. Un spike es un script o test exploratorio más un informe en `docs/spikes/`.
3. **Respetar las fronteras de módulo.** Cada cambio debe pasar `ApplicationModules.verify()`. No se accede a tablas ni a clases internas de otro módulo. En particular: `workspace` nunca depende de un módulo de dominio; `territory` nunca tiene tablas propias.
4. **Hexagonal estricta**: el paquete `domain` no importa Spring, JPA ni nada de `infrastructure`. Los puertos son interfaces del dominio; los adaptadores viven en `infrastructure`.
5. **Idempotencia en toda ingesta.** Reejecutar un job no duplica datos.
6. **Nada de conclusiones en el código.** No se introducen etiquetas interpretativas ("degradado", "sospechoso", "anómalo") ni indicadores compuestos sin una decisión explícita documentada en `docs/decisions/` (formato ADR).
7. **Toda agregación territorial expone denominador y caveats.**
8. **El backend ordena, filtra, pagina y agrega; el frontend pinta.** Ningún endpoint devuelve listas sin criterio de ordenación explícito.
9. **No se crea un módulo por dataset.** Una fuente nueva se asigna a un bounded context existente salvo decisión documentada en ADR.
10. **Tests antes de considerar algo terminado.** Sin Testcontainers en verde, no está hecho.
11. **Decisiones de arquitectura como ADR** en `docs/decisions/`, numeradas. `SPEC.md` es la ADR-000.
12. **Idioma**: código e identificadores en inglés; documentación, ADRs y textos de usuario en español.
13. **Commits pequeños y descriptivos**; una funcionalidad por rama.
14. **Preguntar antes de asumir** cuando una decisión no esté cubierta aquí y afecte a modelo de datos, fronteras de módulo, superficie de API o datos personales.
15. **Scaffolding y dependencias a través de las herramientas de cada ecosistema, nunca de memoria.** El objetivo es que ninguna coordenada, nombre de paquete ni versión se escriba recordada: siempre resuelta por la herramienta o verificada contra el repositorio oficial.
    - **Maven**: el proyecto se genera con Spring Initializr (CLI `spring init` o `curl` a `start.spring.io`) declarando ahí todas las dependencias iniciales; se usa el wrapper `./mvnw` generado, nunca un `mvn` global. Las dependencias posteriores se añaden al `pom.xml` **sin `<version>` explícita**, delegando en el BOM del parent de Spring Boot y en el BOM de Spring Modulith. Solo se escribe una versión cuando ningún BOM la gestiona, y en ese caso se verifica antes contra Maven Central (búsqueda o `./mvnw dependency:resolve`); no se escribe de memoria.
    - **Node / Angular**: el proyecto se crea con `ng new`; los paquetes se añaden con `npm install <paquete>` (o `ng add`), nunca editando `package.json` a mano. El lockfile se versiona y se instala con `npm ci`.
    - **Verificación inmediata**: tras cualquier cambio de dependencias o de scaffolding, compilación y tests en el mismo paso (`./mvnw verify`, `npm ci && npm run build`). No se encadena un segundo cambio sobre uno que no compila.
    - Ficheros de configuración de herramientas (`pom.xml`, `package.json`, `angular.json`) se editan a mano solo para lo que la herramienta no cubre (plugins de build, perfiles, scripts), y siempre seguido de la verificación anterior.

## Reglas adicionales (ADR-001 a ADR-004)

16. **Spikes = tests JUnit etiquetados.** Cada spike es una clase `S0xNombreSpike` con `@Tag("spike")` en `src/test/java/es/zaragoza/observatory/spikes/`, excluida del build por defecto y ejecutada con `.\mvnw.cmd test -Pspikes`. Guarda respuestas crudas en `src/test/resources/fixtures/zaragoza/` y su informe va en `docs/spikes/`.
17. **Stack real**: Spring Boot 4.1.x (starters modulares: `spring-boot-starter-webmvc`, `-restclient`, `-flyway`…), Spring Modulith 2.1.x vía BOM, Java 21, Jackson 3 (`tools.jackson`). Cuando dudes de un paquete o artefacto, mira `pom.xml` y `.\mvnw.cmd dependency:tree`, no la memoria.
18. **API municipal, reglas verificadas (S0.5)**: URL siempre con extensión `.json`/`.geojson` y `srsname=wgs84`; `rows` tope 500 en la sede (sin tope en OCDS y Open311); `start` ignorado en OCDS; `If-Modified-Since` nunca se honra y solo Open311 honra `ETag`; `Last-Modified` llega con zona `CET/CEST` (no RFC 1123); fechas sin zona son hora local, presupuesto usa `yyyyMMdd`; FIQL (`q`) tiene lista blanca de campos por endpoint. Los detalles y excepciones están en `docs/spikes/`.
19. **Unidad territorial = junta municipal o vecinal (29)**, opcionalmente sección censal (491). No existen barrios como dato abierto (S0.4). El gasto público (OCDS, presupuesto, subvenciones) **no tiene dimensión territorial** (S0.2, S0.6): no se inventa geocodificación.
20. **El contexto de gasto se llama `spending`** (ADR-003): OCDS + presupuesto + subvenciones, sin entidades territoriales; `spending` no depende de `geo` y `territory` no depende de `spending`. Licencias, locales y obras en vía pública quedan fuera (candidato posterior `urban-activity`, con ADR propia).
21. **Infraestructura de fase 1 (ADR-004)**: el registro de eventos de Modulith va sobre JDBC y su tabla la crea Flyway (V002); `spring.jpa.hibernate.ddl-auto=validate`, así que **toda entidad JPA nueva necesita su migración** y declara `columnDefinition` (`text`, `timestamptz`, `timestamp`, `boolean`) para que la validación case con PostgreSQL. Resilience4j se usa de forma programática dentro de `ZaragozaHttpClient` (retry solo en 5xx/timeout/E-S/HTML, circuit breaker por dataset, semáforo de 4); timeouts y redirecciones por `spring.http.clients.*`. Cada fuente nueva se declara como bean `IngestionJob` en el módulo dueño (descriptor + `interval()` + `handle(RawPage)`) y su adaptador se prueba con `MockRestServiceServer` sobre fixtures reales grabados con cabeceras. El contrato de la API propia lo genera springdoc (`/v3/api-docs`). Al cerrar un run se publica `DatasetIngested`; los listeners de otros módulos son `@ApplicationModuleListener`.
22. **Ningún dato personal de terceros entra en el repositorio.** El ayuntamiento publica el texto libre de quejas y sugerencias sin anonimizar (nombres y DNI dentro de `title`/`description`, comprobado el 2026-09-06). Los fixtures de fuentes con texto ciudadano se guardan con `SpikeFixtures.saveRedacted(...)` (campos sustituidos por un marcador); las cabeceras grabadas nunca incluyen `Set-Cookie`; antes de subir a GitHub se pasa `git grep` a los fixtures buscando DNI (`\b[0-9]{8}[A-Z]\b`), correos y firmas («atentamente»). El historial se limpió con `git filter-repo` el 2026-09-06; no volver a introducir esos datos. En el producto (fase 2), el texto libre de las quejas no se republica tal cual (SPEC.md §4.6, §9).

## Contexto operativo (máquina de desarrollo)

- Windows 10 con PowerShell 5.1. Usar `.\mvnw.cmd` (nunca `mvn` global). En PowerShell 5.1 no existen `&&` ni `||`: encadenar con `;` o `if ($?) { ... }`.
- La herramienta Bash de Claude Code recibe el PATH de Windows con separadores `;` y no encuentra ningún binario. Si se usa, anteponer: `export PATH="/c/Program Files/Git/usr/bin:/c/Program Files/Git/cmd:/c/Program Files/Docker/Docker/resources/bin:/c/Program Files/Java/jdk-21.0.10/bin:/c/WINDOWS/system32:$PATH"`. Por defecto, usar PowerShell.
- `JAVA_HOME` apunta al JDK 21 (lo usa el wrapper). El `java` del PATH es Java 8: `java -version` engaña; fiarse de `.\mvnw.cmd -v`.
- Docker Desktop debe estar arrancado antes de `.\mvnw.cmd verify` (Testcontainers) y de `.\mvnw.cmd spring-boot:run` (Docker Compose). Comprobar con `docker info`; se puede arrancar con `Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"` y esperar.
- El puerto 8080 suele estar ocupado por un contenedor phpMyAdmin de otro proyecto: arrancar la app con `"-Dspring-boot.run.arguments=--server.port=8085"`. Para pararla, matar el proceso Java que escucha en el puerto y `docker compose -f compose.yaml stop`.
- Los commits con mensaje multilínea se hacen desde la herramienta Bash (`git commit -F - <<'MSG' … MSG` con el PATH de arriba); en PowerShell 5.1 los heredocs `<<` no existen y las here-strings fallan con `git commit -F -`.
- Base de datos: PostgreSQL con PostGIS, imagen `postgis/postgis:17-3.5` tanto en `compose.yaml` como en Testcontainers.
- No hay `spring` CLI ni `jq`. Hay `curl.exe`, `tar`, Python 3.12 y Node 22.
- Ficheros de texto en UTF-8 sin BOM (`Out-File`/`Set-Content` de PowerShell añaden BOM o usan ANSI: evitar para escribir fuentes).

## Comandos habituales

```powershell
.\mvnw.cmd verify                 # build completo con Testcontainers (Docker arrancado), ~2 min
.\mvnw.cmd test "-Dtest=CatalogIntegrationTests"   # una clase
.\mvnw.cmd test -Pspikes          # solo spikes (red real, lentos)
.\mvnw.cmd test -Pspikes "-Dtest=S01*"
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=8085"   # app + PostGIS vía Docker Compose
```

Con la app arrancada: `/actuator/health`, `/actuator/modulith`, `/api/v1/catalog/summary`, `/v3/api-docs`, `/swagger-ui.html`. El planificador ingiere el catálogo real 30 s después de arrancar.
