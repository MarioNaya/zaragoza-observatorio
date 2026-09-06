# ADR-008 — Despliegue: imagen Docker propia sobre Railway con la imagen PostGIS

- **Fecha**: 2026-09-06
- **Estado**: aceptada
- **Afecta a**: `SPEC.md` §5, §9; `CLAUDE.md` regla 26; `docs/ESTADO.md` §4; `docs/despliegue.md`
- **Se apoya en**: documentación oficial de Railway consultada el 2026-09-06 (enlaces al final), ADR-004 (una sola instancia), ADR-005 (la serie observada necesita tiempo), verificación local con `compose.prod.yaml` (§ Consecuencias)

## Contexto

La fase 1 está cerrada en código, pero las dos cosas que quedan no dependen de escribir más código: la **primera serie de instantáneas observadas** (que es lo que permitirá decidir por ADR si existe una categoría observada, ADR-005 §6) y el **despliegue**. La primera depende de la segunda: la serie solo se acumula si la aplicación está arriba todos los días.

Condiciones que impone el proyecto:

- **PostGIS es obligatorio**: `V001__postgis_extension.sql` hace `CREATE EXTENSION postgis`, y `geo` (fase 2) lo necesita para la resolución punto → junta. Un Postgres gestionado sin la extensión no arranca la aplicación.
- **Una sola instancia** (ADR-004): sin ShedLock, `spring.task.scheduling.pool.size=1` y `republish-outstanding-events-on-restart`. Escalar réplicas exigiría ShedLock antes.
- **Los datos son el producto**: la serie histórica de instantáneas no se puede rehacer, así que la base de datos necesita volumen persistente y no puede ser efímera entre despliegues.
- **Coste bajo y una sola instancia** (SPEC.md §5).

Hechos verificados en la documentación de Railway el 2026-09-06 (regla 15: nada de memoria):

- el Postgres por defecto **no trae PostGIS**, y Railway declara que no piensa añadir extensiones a las plantillas por defecto; existe una **plantilla PostGIS aparte que usa `postgis/postgis:17-3.5`**, exactamente la misma imagen que `compose.yaml` y `TestcontainersConfiguration`;
- inyecta `PORT=8080` **solo en ejecución** y exige que la aplicación escuche en `0.0.0.0`; el puerto de destino del dominio público debe coincidir con él;
- el servicio Postgres publica `PGHOST`, `PGPORT`, `PGUSER`, `PGPASSWORD`, `PGDATABASE` y `DATABASE_URL`, y entre servicios se referencian con `${{Servicio.VARIABLE}}`;
- detecta y usa automáticamente un `Dockerfile` en la raíz del repositorio (con ese nombre exacto, con D mayúscula);
- **Config as Code (`railway.json` / `railway.toml`) está deprecado**: los servicios nuevos no pueden acogerse y los ficheros dejan de leerse el 2026-12-01. Lo sustituye Infrastructure as Code (`.railway/railway.ts`), que aplica la CLI.

## Decisión

1. **Imagen propia con `Dockerfile` multietapa en la raíz**, no el detector automático (Railpack). El build queda fijado y reproducible: se compila con el wrapper del propio proyecto (regla 15) sobre `eclipse-temurin:21-jdk-noble` y se ejecuta sobre `eclipse-temurin:21-jre-noble`, sin herramientas de compilación ni usuario root. Railway lo detecta sin configuración añadida, y la misma imagen se puede construir y probar en local.
2. **Los tests no se ejecutan en el builder.** Dependen de Testcontainers, que necesita un Docker del que el builder no dispone. La puerta sigue siendo `.\mvnw.cmd verify` antes de publicar (regla 10). Se compila con `-Dmaven.test.skip=true` y `src/test/` queda fuera del contexto de build (`.dockerignore`), lo que además impide que ningún fixture de la API municipal viaje a la imagen (regla 22).
3. **Extracción por capas de Spring Boot** (`java -Djarmode=tools -jar … extract --layers --launcher`): una capa de imagen por capa de Boot, de la que menos cambia a la que más. Las 118 dependencias no se vuelven a subir cuando solo cambia el código propio.
4. **La base de datos usa la imagen PostGIS `postgis/postgis:17-3.5`**, no el Postgres por defecto (la plantilla del catálogo de Railway usa esa misma imagen; aquí se declara directamente, ver §8). Con ello la misma imagen de PostgreSQL corre en desarrollo (`compose.yaml`), en los tests (Testcontainers) y en producción: las diferencias de versión o de extensiones dejan de ser una fuente posible de fallo.
5. **La conexión se configura con las variables `PG*` estándar, no con `DATABASE_URL`.** Spring Boot necesita una URL `jdbc:` y no interpreta `postgresql://`; parsearla a mano sería código propio para algo que la fuente ya publica descompuesto. El perfil `prod` las declara **sin valor por defecto** (`${PGHOST}`, `${PGDATABASE}`, `${PGUSER}`, `${PGPASSWORD}`; solo `PGPORT` cae a 5432): si falta una, la aplicación no arranca en vez de caer silenciosamente a un valor de desarrollo.
6. **Actuator en producción: solo `health` e `info`.** `health` lo necesita el healthcheck de la plataforma. **`/actuator/modulith` no se expone**: publica la estructura interna de módulos, no aporta nada a quien consume la API y esa información ya está en `docs/arquitectura.md` y validada por `ModularityTests` en cada build. Sigue disponible en local. Esto cierra la duda abierta de `SPEC.md` §9.
7. **springdoc sí se expone** (`/v3/api-docs`, `/swagger-ui.html`): el contrato de la API propia es parte del producto para reutilizadores (SPEC.md §6). Se declara explícitamente porque Boot avisa de que por defecto queda abierto, y así la decisión es una elección y no un descuido.
8. **La infraestructura se declara en `.railway/railway.ts` (Infrastructure as Code).** `railway.json` está deprecado y cerrado a servicios nuevos, así que escribirlo sería introducir algo que deja de leerse el 2026-12-01; el sucesor soportado es el fichero TypeScript que evalúa la CLI. Se eligió esta vía sobre los clics en el panel por la misma razón que todo lo demás en este proyecto: la definición del despliegue queda versionada, revisable en diff y reproducible, y `railway config plan` enseña exactamente qué se va a crear antes de tocar nada.

   - **No contiene secretos y por eso se versiona**: la contraseña la generó Railway al crear la base de datos y en el fichero aparece como `preserve()` («conserva el valor que ya está en Railway»); la aplicación recibe `PGHOST`…`PGPASSWORD` como **referencias** (`ref(postgis, "PGPASSWORD")`), no como valores. El plan las muestra redactadas (`«hidden»`). **Nunca usar `railway config pull --include-variables`**, que descifra los valores y los escribe en el fichero.
   - **La base de datos se declara como `service` con `image(...)` y volumen explícito, no con el ayudante `database(...)`.** `database()` acepta una imagen propia, pero al aplicarla Railway convierte el recurso en servicio; entonces el fichero, que lo declaraba como base de datos, planifica borrarlo y recrearlo con la imagen por defecto, y se entra en un bucle destructivo. Declararlo como servicio describe lo que Railway hace de verdad y el plan queda estable. Con ello hay que declarar también **todas** sus variables (en IaC lo omitido se borra), la mayoría como `preserve()`.
   - **En IaC, lo que no está en el fichero se borra**: cualquier servicio futuro se añade ahí, no por el panel.
   - **El volumen de la base de datos lo aprovisiona Railway**, con los mismos valores por defecto que desde el panel; vía IaC solo se puede ajustar la región, así que `volumeAttachments: null` en el primer plan es lo esperado y no una base de datos efímera.
   - **`PGDATA` apunta a un directorio propio** (`/var/lib/postgresql/data/pgdata-postgis17`) y no al `pgdata` heredado. Al crear el servicio, Railway inicializó el volumen con su **Postgres 18** por defecto, y PostgreSQL 17 no arranca sobre ese clúster: falla con `unrecognized configuration parameter "autovacuum_worker_slots"` y entra en bucle de reinicio. Con un directorio nuevo, la imagen PostGIS inicializa el suyo (ejecutando `10_postgis.sh`) y el clúster anterior se queda intacto en el volumen. Se prefirió esto a borrar el volumen: no se destruye nada y es reversible. La alternativa —usar una imagen PostGIS basada en PostgreSQL 18— se descartó porque rompería la igualdad de imagen entre desarrollo, tests y producción, que es el motivo de elegirla (§4).
   - Consecuencia menor: el repositorio gana un `package.json` con una única dependencia de desarrollo (`railway`, el SDK que el fichero importa) y su lockfile, más `node_modules/` ignorado. Si el frontend acaba viviendo en este mismo repositorio (SPEC.md §9), habrá que decidir si ese `package.json` de raíz se comparte o se separa.
   - **Peculiaridad de Windows**: el SDK comprueba la versión de la CLI ejecutando `process.env._` (o `railway`) con `execFileSync`, que en Windows no puede lanzar el `.cmd` ni el shim de Git Bash y falla con un engañoso «requires Railway CLI 5.42.1 or newer» aun teniendo la 5.49.2. Se resuelve apuntando esa variable al binario nativo: `$env:_ = "$env:APPDATA\npm\node_modules\@railway\cli\bin\railway.exe"`.

   El panel sigue documentado en `docs/despliegue.md` como alternativa manual para quien no tenga la CLI.
9. **Una sola instancia con volumen persistente.** ADR-004 sigue en pie: sin ShedLock, el planificador no puede correr en dos réplicas a la vez. El servicio se mantiene con réplica única y el volumen del Postgres es lo único que no se puede perder.
10. **Zona horaria del contenedor `Europe/Madrid`.** No afecta a ningún dato —el dominio usa `Clock.systemUTC()` y `ZaragozaTime.ZONE` explícito, y no hay un solo `systemDefault()` en `src/main`—, pero alinea el cron de purga de `raw_payload` (`0 30 4 * * *`) y las marcas de los logs con la hora local, de modo que local y producción se comportan igual.
11. **`compose.prod.yaml` para verificar en local** la misma imagen y el mismo perfil antes de publicar, con las mismas variables `PG*`. Lleva nombre de proyecto propio (`zaragoza-observatorio-prod`) porque, si no, Compose lo derivaría del directorio y coincidiría con el de desarrollo: mismo nombre de contenedor y mismo volumen, con riesgo de pisar los datos ya ingeridos. Su base de datos es efímera a propósito: es una comprobación, no un entorno.

## Consecuencias

- Ficheros nuevos: `Dockerfile`, `.dockerignore`, `compose.prod.yaml`, `docs/despliegue.md`. El perfil `prod` de `application.yaml` pasa de tres líneas (logs ECS) a declarar datasource, puerto, apagado ordenado, actuator y springdoc.
- **Verificación real del 2026-09-06, 23:12 CEST**, con `docker compose -f compose.prod.yaml up`: imagen construida desde cero; Flyway aplicó las 7 migraciones incluida la extensión PostGIS; la aplicación arrancó en 14,6 s y respondió `health=UP` a los 8 s de levantar el contenedor; el proceso corre como `uid=10001(observatory)`, no root, con `TZ=Europe/Madrid`; `/actuator` expone solo `health` e `info` y `/actuator/modulith` devuelve **404**; `/v3/api-docs` sirve OpenAPI 3.1.0 con 7 rutas; los logs salen en ECS JSON. A los 30 s los tres jobs ingirieron contra las fuentes reales: **436 fichas, 497 operaciones en 84 tags del Swagger y 369 datasets federados (261 con ficha, 108 sin ella)** — las mismas cifras que registró la comprobación de la cuarta sesión, ahora desde la imagen de producción.
- **Instancia desplegada el 2026-09-07 a las 00:26 CEST** en <https://observatorio-production-ed20.up.railway.app>, región `europe-west4`. `health` UP, `/actuator/modulith` 404, `/v3/api-docs` con OpenAPI 3.1.0 y 7 rutas, y las tres ingestas contra las fuentes reales: 436 fichas, 497 operaciones en 84 tags y 369 federados (261 con ficha, 108 sin ella). El muestreo observado arrancó en el primer lote (60 fichas) y completa el catálogo en unas 12 h.
- El MCP de Railway configurado no conectaba (`CONNECTION_CLOSED`) porque **el login de la CLI había caducado**: el servidor remoto se autentica con esa sesión. Se resolvió con `railway login` y `railway setup agent --remote`. El despliegue en sí se hizo con la CLI, sin necesidad del MCP.
- La primera serie de instantáneas empieza a acumularse desde ya, que es la condición para abrir la ADR de la categoría observada (ADR-005 §6) y para afinar `zaragoza.catalog.freshness.*` y la retención de `raw_payload`.
- Carga diaria sobre las fuentes desde el servicio desplegado: el catálogo cada 6 h, el Swagger y la federación una vez al día, y una distribución por ficha al día en el muestreo observado. Es lo que hay que declarar en el alta como reutilizador (`docs/ESTADO.md` §6).

## Alternativas descartadas

- **Railpack / detección automática de Java**: el build dependería de lo que la plataforma decida sobre JDK y comando de arranque, no del wrapper del proyecto, y no se puede reproducir en local. Un `Dockerfile` explícito cuesta 48 líneas y elimina la clase entera de problemas.
- **`spring-boot:build-image` (Cloud Native Buildpacks)**: produce una imagen excelente sin escribir `Dockerfile`, pero exige un Docker con red durante el build y publicar en un registro propio; con Railway construyendo desde el repositorio es una pieza de más.
- **Postgres gestionado por defecto, renunciando a PostGIS**: no arranca (`V001`) y dejaría a `geo` sin base para la fase 2. La plantilla PostGIS existe y usa exactamente nuestra imagen.
- **Base de datos externa (Neon, Supabase) con PostGIS**: viable, pero añade un proveedor, latencia entre servicios y un secreto más, sin ganar nada frente a la plantilla de la misma plataforma.
- **`DATABASE_URL` con un parser propio**: código propio para deshacer una URL que la fuente ya publica descompuesta en `PG*`, y una pieza más que mantener.
- **`railway.json` en el repositorio**: deprecado, cerrado a servicios nuevos y con fecha de caducidad (2026-12-01). Escribir configuración muerta no es una opción; el sucesor es `.railway/railway.ts` (§8).
- **Montar los servicios a mano por el panel**: fue la decisión original de esta ADR, tomada cuando no había CLI en la máquina y la única alternativa era un `railway.json` deprecado. Se revisó el mismo día, al instalarse la CLI: con `railway config` disponible, dejar la definición del despliegue solo en la nube contradice cómo se trabaja aquí todo lo demás. El panel queda como alternativa documentada, no como vía principal.
- **Exponer `/actuator/modulith` como documentación viva de la arquitectura**: no gana nada que no esté ya en `docs/arquitectura.md` y en `ModularityTests`, y en un servicio público es superficie sin contrapartida.
- **Varias réplicas**: exigiría ShedLock (ADR-004) y no hay carga que lo justifique (SPEC.md §5: decenas de usuarios).

## Referencias

- <https://docs.railway.com/databases/postgresql> — variables del servicio Postgres; el template por defecto no incluye extensiones
- <https://railway.com/deploy/postgis-spatial-database> — plantilla PostGIS (`postgis/postgis:17-3.5`)
- <https://docs.railway.com/builds/dockerfiles> — detección automática del `Dockerfile` de la raíz
- <https://docs.railway.com/networking/troubleshooting/application-failed-to-respond> — `PORT` inyectado en ejecución, `0.0.0.0`, puerto de destino
- <https://docs.railway.com/reference/variables> — referencias entre servicios `${{Servicio.VARIABLE}}`
- <https://docs.railway.com/infrastructure-as-code> — sustituto de Config as Code, deprecado con corte el 2026-12-01
