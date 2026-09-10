# Despliegue

**Instancia en marcha**: <https://observatorio-production-ed20.up.railway.app> (proyecto `zaragoza-observatorio`, entorno `production`, región `europe-west4`), desplegada el 2026-09-07.

Procedimiento para poner en marcha una instancia del observatorio. Las decisiones que lo sostienen están en [ADR-008](decisions/ADR-008-despliegue.md); aquí solo están los pasos y las comprobaciones.

Plataforma: **Railway**, con dos servicios en el mismo proyecto — la aplicación (imagen construida desde el `Dockerfile` del repositorio) y una base de datos **PostGIS**. La aplicación **no arranca contra un PostgreSQL sin PostGIS**: la primera migración hace `CREATE EXTENSION postgis`.

## 1. Antes de desplegar

```powershell
.\mvnw.cmd verify                     # build completo con Testcontainers; los tests NO corren en el builder
git grep -i -E '\b[0-9]{8}[A-Z]\b|atentamente|set-cookie' -- src/test/resources/fixtures   # regla 22
```

Prueba de la imagen de producción en local, con el mismo perfil y las mismas variables que en Railway:

```powershell
docker compose -f compose.prod.yaml up --build -d
curl.exe http://localhost:8085/actuator/health          # {"status":"UP"}
curl.exe http://localhost:8085/api/v1/catalog/summary
docker compose -f compose.prod.yaml down -v
```

`compose.prod.yaml` usa nombre de proyecto propio y base de datos efímera: no toca el Postgres de desarrollo ni su volumen. Conviene pararlo antes de dos minutos desde el arranque, cuando empieza el muestreo observado, para no cargar la API municipal sin necesidad.

## 2. La infraestructura, en código

Los dos servicios están declarados en [`.railway/railway.ts`](../.railway/railway.ts): la base de datos con la imagen PostGIS y la aplicación desde el repositorio, con sus cinco variables como referencias, el healthcheck y la réplica única. No hay secretos en ese fichero (ADR-008 §8).

Requisitos: CLI de Railway (`npm install -g @railway/cli`), sesión iniciada (`railway login`) y el SDK del repositorio (`npm ci` en la raíz).

```powershell
# En Windows, el SDK comprueba la versión de la CLI de una forma que no funciona con el .cmd:
# hay que apuntar esta variable al binario nativo o fallará con un engañoso "requires CLI 5.42.1".
$env:_ = "$env:APPDATA\npm\node_modules\@railway\cli\bin\railway.exe"

railway link                    # vincular el directorio al proyecto (solo la primera vez)
railway config plan --verbose   # previsualiza; no toca nada
railway config apply            # aplica tras confirmar
```

`plan` redacta los valores de las variables, así que se puede pegar en cualquier sitio. **Nunca usar `railway config pull --include-variables`**: descifra los secretos y los escribe en el fichero.

Cuatro cosas que conviene tener presentes, tres de ellas aprendidas a base de fallos en el primer despliegue:

- **En IaC, lo que no está en el fichero se borra**, variables incluidas. Un servicio nuevo se añade ahí, no por el panel.
- **El volumen de la base de datos lo aprovisiona Railway** con sus valores por defecto; que el primer plan muestre `volumeAttachments: null` es lo normal, no una base de datos efímera.
- **La base de datos se declara como `service` con `image(...)`, nunca con `database(...)`.** El ayudante `database()` acepta una imagen propia, pero al aplicarla Railway convierte el recurso en servicio y el plan entra en un bucle de borrar y recrear. Si `railway config plan` propone «Create database X / Delete service X», es esto.
- **`PGDATA` va en un directorio propio.** Railway inicializa el volumen con su Postgres 18; PostgreSQL 17 no arranca sobre ese clúster y se queda en bucle con `unrecognized configuration parameter "autovacuum_worker_slots"`. Si la base no levanta, mirar `railway logs --service postgis --deployment`.

Tras el primer `apply`, el dominio público:

```powershell
railway domain --service observatorio
```

## 3. Alternativa manual, por el panel

Si no hay CLI. En el proyecto de Railway, añadir un servicio desde la **plantilla PostGIS** (`postgis/postgis:17-3.5`, la misma imagen que usan `compose.yaml` y los tests) — **no** el PostgreSQL por defecto, que no trae la extensión — con **volumen persistente**: la serie histórica de instantáneas no se puede rehacer.

Después, un servicio desde el repositorio de GitHub (`MarioNaya/zaragoza-observatorio`, rama `main`). Railway detecta solo el `Dockerfile` de la raíz; no hay que elegir builder.

**Variables** del servicio de la aplicación (sustituir `Postgres` por el nombre real del servicio de base de datos):

| Variable | Valor |
|---|---|
| `PGHOST` | `${{Postgres.PGHOST}}` |
| `PGPORT` | `${{Postgres.PGPORT}}` |
| `PGDATABASE` | `${{Postgres.PGDATABASE}}` |
| `PGUSER` | `${{Postgres.PGUSER}}` |
| `PGPASSWORD` | `${{Postgres.PGPASSWORD}}` |

No hace falta declarar `SPRING_PROFILES_ACTIVE=prod` ni `PORT`: el perfil lo fija el `Dockerfile` y `PORT` lo inyecta Railway en ejecución. Si falta cualquiera de las cinco variables, la aplicación **no arranca** — es deliberado (ADR-008 §5): mejor un fallo claro que una conexión a un valor de desarrollo.

**Ajustes del servicio**:

- Healthcheck: `/actuator/health`.
- Réplicas: **1**. Más de una rompería el planificador de ingesta, que no tiene ShedLock (ADR-004).
- Dominio público: el puerto de destino debe ser **8080**, el que inyecta Railway y en el que escucha la aplicación.

## 4. Comprobación tras el despliegue

```bash
curl https://<dominio>/actuator/health            # {"status":"UP"}
curl https://<dominio>/actuator/modulith          # 404: no se expone en producción (ADR-008 §6)
curl https://<dominio>/v3/api-docs                # contrato OpenAPI 3.1.0
curl https://<dominio>/api/v1/catalog/summary     # a los ~40 s del arranque: fichas del catálogo
curl https://<dominio>/api/v1/geo/districts       # a los ~45 s: las 29 juntas con su padrón
```

En los logs (ECS JSON) debe verse Flyway aplicando las 11 migraciones la primera vez, y a los 30 s las cinco ingestas:

```
ingestion … succeeded for sede:catalogo/api: 1 records in 1 pages
ingestion … succeeded for data-space:catalogo: 434 records in 1 pages
ingestion … succeeded for datos-gob-es:publisher/L01502973: 369 records in 2 pages
ingestion … succeeded for sede:distrito: 29 records in 1 pages
geo profiles: 29 districts read, 29 with padronId, 116 population records, 0 failed
```

**Un despliegue que trae una migración nueva** (el primero fue `V008`, el 2026-09-08) se reconoce por `Successfully validated N migrations` seguido de `Migrating schema "public" to version "…"` y `Successfully applied 1 migration`. Con `ddl-auto=validate`, un fallo ahí aborta el arranque: conviene mirar los logs mientras redespliega, no después.

A los 2 minutos empieza el muestreo observado, en lotes de 60 fichas cada 10 minutos: el catálogo completo queda observado en unas 12 horas y a partir de ahí una vez al día. Esa es la serie que hace falta para la ADR de la categoría observada (ADR-005 §6).

## 5. Ritmo de peticiones a las fuentes

Lo que el servicio consume al día, y lo que hay que declarar en el alta como reutilizador (`ESTADO.md` §6):

| Fuente | Frecuencia | Volumen |
|---|---|---|
| `catalogo.json` (espacio de datos) | cada 6 h | 1 petición, ~2 MB |
| `catalogo/api.json` (Swagger de la sede) | 1 vez al día | 1 petición, ~1,3 MB |
| datos.gob.es, publicador `L01502973` | 1 vez al día | 2 peticiones, ~1 MB |
| `distrito.json?srsname=wgs84` (juntas con geometría) | 1 vez al día | 1 petición, ~1,4 MB |
| `distrito/{id}.json` (padrón de cada junta) | 1 vez al día | 29 peticiones ligeras, espaciadas 0,3 s |
| Muestreo observado | 1 vez al día por ficha | ~436 peticiones ligeras (`rows=1`, `HEAD`, `resultType=hits`), espaciadas 0,5 s |

Todas salen con el `User-Agent` de `zaragoza.http.user-agent`, que identifica el proyecto y enlaza al repositorio.

## 6. Coste

Railway factura **RAM residente a 10 $/GB/mes** y CPU a 20 $/vCPU/mes, **nada por petición**, egress a 0,05 $/GB y volumen a 0,15 $/GB/mes. En un servicio con poco tráfico la factura es casi toda memoria ocupada las 24 horas (ADR-009).

| Concepto | Consumo | Coste aproximado |
|---|---|---|
| `observatorio` (JVM acotada) | ~425 MB con cinco módulos (**sin medir con seis**, ver abajo) | ~4,25 $/mes |
| `postgis` | ~125 MB de media | ~1,25 $/mes |
| Volumen | 5 GB | 0,75 $/mes |
| Egress | medido: 0 MB | ~0 |
| **Total** | | **~5,7 $/mes** |

Sobre ese consumo va el plan. El workspace está en **Pro: 20 $/mes con 20 $ de uso incluido**, y se paga por las copias de seguridad del volumen, que Hobby (5 $/mes con 5 $ incluidos) no ofrece (ADR-010, §7). Con el consumo actual la factura es plana: 20 $/mes, con el uso dentro del crédito. **El crédito es de la cuenta, no del proyecto**, y en esta cuenta hay otra aplicación.

Comprobarlo con `railway metrics --service observatorio`. **Lo que se vigila es un salto respecto de la línea base de 425 MB** (cinco módulos, medida el 2026-09-09; eran 416 MB con cuatro), no el umbral de 400 MB de la primera versión de ADR-009: se fijó para una aplicación de dos módulos y hoy solo daría falsos positivos. Ante un salto, mirar si alguien tocó `JAVA_TOOL_OPTIONS` en el `Dockerfile`.

**Con seis módulos la línea base está sin medir** (2026-09-10, `spending`). No es un descuido: la carga inicial de la contratación son ~8.000 peticiones de detalle repartidas en unas 7 h por su propio planificador, así que una medida tomada antes de que termine describe la carga, no el estado de reposo. Se mide cuando `GET /api/v1/spending/summary` deje de tener procesos en `PENDING`, y a partir de ahí la cifra nueva sustituye a los 425 MB como referencia.

Dos cosas que **no** reducen esta factura, por si tienta:

- **Filtrar bots**: el rastreo de vulnerabilidades genera 4xx, pero Railway no cobra por petición y el egress medido es 0 MB. Diez mil respuestas de error al mes son céntimos. Es higiene, no ahorro — y requeriría un dominio propio delante (Cloudflare) que este proyecto no tiene.
- **Modo Serverless**: no aplica. El servicio mantiene el pool a `postgis` y sale a la API municipal cada 10 minutos, así que no se dormiría; y si se durmiera, el planificador no correría y la serie de instantáneas dejaría de acumularse (ADR-009).

## 7. Copias de seguridad del volumen

Activas desde el 2026-09-08 sobre `postgis-volume`: **DAILY** (6 días de retención), **WEEKLY** (27) y **MONTHLY** (89). Protegen lo único irrepetible del proyecto, la serie de instantáneas y observaciones. Las razones y lo que se probó están en [ADR-010](decisions/ADR-010-copias-de-seguridad.md); aquí, los comandos.

**No se declaran en `.railway/railway.ts`.** La IaC acepta el campo y lo muestra en el `plan`, pero el `apply` responde `changes: []` y no hace nada, ni tampoco lee el estado real: la línea dejaría un cambio pendiente perpetuo. Se gobiernan por la API pública. Hace falta plan **Pro**; en Hobby la mutación responde `Not Authorized`.

El identificador que piden todas las llamadas es el de la **instancia** del volumen, no el del volumen:

```powershell
$env:_ = "$env:APPDATA\npm\node_modules\@railway\cli\bin\railway.exe"

railway api 'query V($id: String!) { project(id: $id) { volumes { edges { node { name volumeInstances { edges { node { id mountPath } } } } } } } }' --raw-var id=<projectId>
```

Consultar el estado (esto es lo que hay que mirar de vez en cuando; una política declarada no es una copia):

```powershell
railway api 'query S($v: String!) { volumeInstanceBackupScheduleList(volumeInstanceId: $v) { kind cron retentionSeconds } volumeInstanceBackupList(volumeInstanceId: $v) { id name createdAt expiresAt referencedMB } }' --raw-var v=<volumeInstanceId>
```

Fijar los calendarios (la lista es completa: lo que no se pase, se quita). Como los argumentos son una lista de enumerados, van en un fichero JSON de variables — las comillas dobles no sobreviven al paso por PowerShell:

```powershell
# vars.json → { "v": "<volumeInstanceId>", "kinds": ["DAILY", "WEEKLY", "MONTHLY"] }
railway api 'mutation M($v: String!, $kinds: [VolumeInstanceBackupScheduleKind!]!) { volumeInstanceBackupScheduleUpdate(volumeInstanceId: $v, kinds: $kinds) }' --variables "@vars.json"
```

Copia manual inmediata, que es como se comprueba que el mecanismo funciona sin esperar a la programada (tarda segundos, no interrumpe el servicio y no requiere redespliegue):

```powershell
railway api 'mutation B($v: String!, $n: String) { volumeInstanceBackupCreate(volumeInstanceId: $v, name: $n) { workflowId } }' --raw-var v=<volumeInstanceId> --raw-var n=verificacion
```

Las copias manuales **no caducan** (`expiresAt: null`); las programadas sí, según la retención de su calendario. Restaurar (`volumeInstanceBackupRestore`, o el panel) monta un volumen nuevo con los datos de la copia en la ruta original y deja el anterior desmontado pero conservado; el cambio se prepara para revisarlo y hay que desplegar. **No se ha ensayado una restauración**: hacerlo exige una ventana sobre producción.

**El PITR no está disponible aquí**, y no por el plan: `railway postgres pitr status --service postgis` responde que corre sobre las imágenes de base de datos de Railway y que `postgis/postgis:17-3.5` no es una de ellas. Cambiar de imagen rompería `V001` (ADR-008). Las copias del volumen son el único mecanismo de recuperación.

## 8. Notas

- **Config as Code de Railway (`railway.json`, `railway.toml`) está deprecado**: cerrado a servicios nuevos y sin lectura a partir del 2026-12-01. Por eso el repositorio no lleva ninguno y la infraestructura se declara en `.railway/railway.ts`, que es su sustituto soportado (§2).
- **Migraciones**: Flyway corre al arrancar y `ddl-auto=validate` comprueba que las entidades casan. Un despliegue con una entidad nueva sin su migración falla al arrancar, no en caliente (ADR-004).
- **Redespliegues**: `server.shutdown=graceful`, así que una ingesta en curso termina antes de cerrar. Las publicaciones de eventos incompletas se reintentan al arrancar (`republish-outstanding-events-on-restart`, ADR-004).
- **Zona horaria**: el contenedor va en `Europe/Madrid`. No afecta a los datos (el dominio usa `Clock.systemUTC()` y `ZaragozaTime` explícito), solo al cron de purga y a la lectura de los logs.
- **Si se quiere escalar a más de una instancia**, antes hay que implementar ShedLock: hoy el planificador correría duplicado en cada réplica (ADR-004).
