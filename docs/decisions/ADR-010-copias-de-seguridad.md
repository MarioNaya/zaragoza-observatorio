# ADR-010 — Copias de seguridad del volumen: por la API, no por IaC, y con plan Pro

- **Fecha**: 2026-09-08
- **Estado**: aceptada
- **Afecta a**: `.railway/railway.ts`, `docs/despliegue.md`, `SPEC.md` §5 y §9, `CLAUDE.md` regla 28, ADR-008, ADR-009
- **Se apoya en**: comprobaciones reales del 2026-09-08 contra el proyecto desplegado (CLI 5.49.2 y 5.49.5, API pública GraphQL, panel de Railway)

## Contexto

La serie diaria de instantáneas y observaciones es **lo único irrepetible del proyecto**: las fichas, el inventario del Swagger y la federación se reingieren de la fuente en cinco minutos, pero lo que la API municipal devolvía el 7 de septiembre no se puede volver a medir. Esta especificación lo declara irrepetible en tres sitios, y hasta hoy el volumen de producción **no tenía ninguna copia**: `volumeInstanceBackupList` devolvía `[]`.

El plan era declararlo en la infraestructura en código, como todo lo demás (ADR-008). Dos hechos lo impidieron, y ambos merecen quedar escritos porque no son evidentes ni están documentados por Railway.

**1. La IaC de Railway acepta `backupSchedules`, lo muestra en el plan y no lo aplica.** El campo existe en el tipo `VolumeMount` del SDK y en la adjunción del grafo, así que `postgis.volumeAttachments["postgis-volume"].backupSchedules = ["DAILY"]` compila y `railway config plan` lo anuncia como cambio pendiente. Pero:

| Vía | Resultado |
|---|---|
| `railway config plan` | `volumeAttachments.postgis-volume.backupSchedules (null → ["DAILY"])`, 1 cambio, 0 destrucciones |
| `railway config apply --yes` (CLI 5.49.2 y 5.49.5) | `applyResult.status: "applied"` con **`changes: []`**; el estado real no cambia |
| `railway config apply --plan <plan fijado>` | «Applied pinned Railway configuration», mismo resultado: nada |
| `railway config plan` con el calendario **ya activo** | sigue anunciando `null → ["DAILY"]` |

Es decir: la IaC ni escribe ni lee ese campo. Lo primero es un fallo silencioso —dice que ha aplicado y no aplica—; lo segundo es peor para este repositorio, porque `.railway/railway.ts` se apoya en que **un `plan` limpio significa «sin deriva»** (en IaC, lo que no está en el fichero se borra). Una línea que produzca un cambio pendiente perpetuo convierte el plan en ruido y esconde la deriva real.

**2. Las copias de volumen son de plan Pro.** Con el workspace en Hobby, la mutación `volumeInstanceBackupScheduleUpdate` respondía `Not Authorized` y el panel lo decía sin rodeos: «Backups and point-in-time recovery (PITR) are only available for customers on the Pro plan». Hobby cuesta 5 $/mes con 5 $ de uso incluido; Pro, 20 $/mes con 20 $ de uso incluido. El proyecto consume ~5,7 $/mes (ADR-009).

**3. El PITR no es una alternativa con nuestra imagen.** `railway postgres pitr status --service postgis` lo rechaza por sí solo:

> Point-in-time recovery runs in Railway's own database images, and "postgis/postgis:17-3.5" is not one of them. Supported images: `ghcr.io/railwayapp-templates/postgres-ssl`, `ghcr.io/railwayapp-templates/postgres-ha/postgres-patroni`.

Y cambiar de imagen no está sobre la mesa: ninguna de las dos trae PostGIS, y sin la extensión `V001__postgis_extension.sql` aborta el arranque (ADR-008). La copia del volumen es, por tanto, **el único mecanismo de recuperación disponible**, y su alcance es el que den sus ventanas de retención.

## Decisión

1. **Las copias no se declaran en `.railway/railway.ts`.** El fichero lleva en su lugar un comentario que explica por qué y remite aquí y a `docs/despliegue.md`. Se prefiere una nota honesta a una declaración que el `apply` ignora: lo segundo aparenta protección y además rompe el valor del `plan`.
2. **Se gobiernan por la API pública** (`railway api`), con la mutación `volumeInstanceBackupScheduleUpdate` y la consulta `volumeInstanceBackupScheduleList`. Los comandos exactos están en `docs/despliegue.md` §7. Railway elige la hora de cada calendario; no es configurable.
3. **Los tres calendarios activos**, no solo el diario: DAILY (retención 6 días), WEEKLY (27) y MONTHLY (89). Con el PITR descartado, la retención *es* la capacidad de recuperación, y un fallo silencioso —una migración que corrompe datos, un borrado que nadie mira— tarda más de seis días en notarse en un proyecto que se atiende por sesiones. Las copias son incrementales y con copia-al-escribir: se factura solo el dato exclusivo de cada una, al precio del volumen (0,15 $/GB/mes), sobre un volumen que hoy ocupa 334 MB.
4. **El workspace pasa a Pro.** Es la parte cara de esta decisión y conviene llamarla por su nombre: se paga el plan por las copias, no por capacidad.
5. **Una copia se verifica, no se declara.** La política se comprueba con una copia real: se creó una manual (`verificacion-inicial`) el mismo día para probar el mecanismo de extremo a extremo, sin esperar a la primera programada.

## Consecuencias

- **Estado verificado el 2026-09-08** sobre el volumen `postgis-volume`, adjunto al servicio `postgis` (el identificador de la instancia se obtiene con la primera consulta de `docs/despliegue.md` §7; no se escribe aquí):

  | Calendario | Cron (UTC) | Retención |
  |---|---|---|
  | `DAILY` | `51 16 * * *` | 518 400 s = 6 días |
  | `WEEKLY` | `39 11 * * 6` | 2 332 800 s = 27 días |
  | `MONTHLY` | `4 8 1 * *` | 7 689 600 s = 89 días |

  Y una copia real: `verificacion-inicial`, creada a las 08:43:22Z, `referencedMB` 334, sin fecha de caducidad. Tardó segundos y **no requirió redespliegue** ni interrumpió el servicio.
- **El suelo de coste sube de ~5,7 $/mes a 20 $/mes.** El consumo medido cabe holgadamente dentro del crédito incluido, así que la factura pasa a ser plana y previsible; el crédito es **de la cuenta, no del proyecto**, y en ella hay otra aplicación.
- **Esto no invalida ADR-009, pero sí matiza su motivo.** Bajar de 690 MB a 356 MB ya no se traduce en factura mientras el consumo quede por debajo de los 20 $ incluidos. Los topes explícitos de la JVM siguen valiendo por dos razones: mantienen el consumo dentro del crédito cuando la fase 2 añada servicios, y evitan que una regresión de memoria pase inadvertida. Lo que ya no se puede decir es que ahorren dinero hoy.
- **La restauración no es un botón inocuo**: Railway monta un volumen nuevo con los datos de la copia en la ruta original y deja el anterior desmontado y conservado; el cambio se prepara para revisarlo y hay que desplegar. Se documenta el procedimiento en `docs/despliegue.md` §7 pero **no se ha ensayado**: hacerlo sobre producción exige tiempo y una ventana, y queda como pendiente honesto.
- **Sigue habiendo un único proveedor.** Estas copias viven en Railway; protegen del borrado, de la migración desastrosa y del fallo del volumen, pero no de perder la cuenta. Si algún día eso importa, la vía es un volcado lógico periódico fuera de Railway (abajo).
- `railway config plan` vuelve a estar limpio, que era el punto.

## Alternativas descartadas

- **Declararlo en IaC**: no funciona (arriba). Se revisará si Railway lo implementa; la línea exacta que habría que descomentar está en el comentario de `.railway/railway.ts`.
- **Seguir en Hobby con volcados lógicos** (`pg_dump` periódico desde una acción de GitHub, guardado fuera de Railway): es más barato y protege también del riesgo de proveedor, pero exige exponer la base por un proxy TCP público o meter un token de Railway como secreto del repositorio, y añade una pieza que mantener y vigilar. No protege el clúster entero, solo lo que se acuerde volcar. **Sigue siendo la opción correcta si algún día se quiere una copia fuera de Railway**; hoy no se hace porque la copia gestionada cubre el riesgo real, que es local.
- **Un endpoint de serie completa versionado en Git**: publicar la serie de instantáneas y bajarla a diario a un repositorio es atractivo —la serie *es* el producto y así sería pública y reproducible—, pero es trabajo de producto, no una copia de seguridad: protege una tabla, no la base. Buena idea para la fase 4, mala sustitución de esto.
- **PITR**: imposible con la imagen PostGIS (arriba), y cambiar de imagen rompe `V001`.
- **No hacer nada y revisarlo cuando la serie valga más**: es lo que se venía haciendo, y el coste de esperar crece cada día que la serie acumula. Se rechaza explícitamente.

## Referencias

- <https://docs.railway.com/volumes/backups> — DAILY «backed up every 24 hours, kept for 6 days», WEEKLY «every 7 days, kept for 27», MONTHLY «every 30 days, kept for 89»; copias incrementales y con copia-al-escribir, facturadas por el dato exclusivo; el calendario se fija «in the service settings panel, under the Backups tab»
- <https://docs.railway.com/reference/pricing/plans> — Hobby 5 $/mes con 5 $ de uso incluido; Pro 20 $/mes con 20 $ de uso incluido
- <https://docs.railway.com/reference/pricing> — volumen 0,15 $/GB/mes
- `railway postgres pitr status --service postgis` — imágenes admitidas para PITR
