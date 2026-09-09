# ADR-013 — Las fichas que dejan de aparecer en el catálogo se marcan, no se borran

- **Fecha**: 2026-09-09
- **Estado**: aceptada
- **Afecta a**: `SPEC.md` §4.6, §4.7, §9; `CLAUDE.md` regla 31
- **Se apoya en**: `docs/spikes/S0.1-catalogo.md`, `docs/spikes/S1.3-federacion.md`, y la comprobación real del 2026-09-09 que se describe abajo

## Contexto

`RegisterDatasets` hace *upsert* por `sourceId` y nunca da de baja nada. El listado municipal, en cambio, encoge: la ingesta que grabó el fixture el 2026-09-06 trajo **436 fichas** y el 2026-09-09 el mismo `catalogo.json?rows=500` devuelve **`totalCount` 434**. La instancia desplegada seguía publicando 436 en `/api/v1/catalog/summary`, es decir, **dos fichas que ya no existen contaban como si existieran**, y ninguna respuesta permitía notarlo.

Comparando el fixture con la respuesta real del 2026-09-09, las dos que faltan son:

| id | title | `catalogo/{id}.json` el 2026-09-09 |
|---|---|---|
| 4008 | Paradas Bus | 404 |
| 4010 | Paradas Tranvía | 404 |

No hay ninguna ficha nueva: 434 = 436 − 2. El detalle también responde 404, así que en este caso la ficha ha desaparecido del catálogo entera, no solo del listado. **Eso no se puede generalizar**: el listado y el detalle son dos recursos distintos y ya hemos visto a esta API servir un registro por un camino y fallar por otro (`quejas-sugerencias/412792.json` responde 400 mientras el listado sirve ese mismo registro, ADR-012). Lo único que la ingesta observa es el listado.

La tabla ya guarda el hecho: `first_seen_at` y `last_seen_at`. Lo que falta es exponerlo y darle una marca estable con la que filtrar y contar.

El módulo tiene un precedente que **no** sirve aquí. La federación (S1.3, ADR-007) **borra** lo que datos.gob.es deja de listar, y está bien: de un dataset federado solo guardamos su URL y sus marcas de ingesta, nada que perder. De una ficha del catálogo guardamos su histórico de frescura, que es justo lo valioso y lo irrepetible: nadie más lo tiene y no se puede reconstruir a posteriori.

## Decisión

1. **La ficha no se borra nunca.** Se conserva con todas sus instantáneas de frescura y su histórico de observación.
2. `catalog_dataset` gana una columna **`delisted_at timestamptz`** (V010): el instante de inicio de la **primera** ingesta completa en la que la ficha no apareció. `NULL` = apareció en la última ingesta completa.
3. La marca la pone el **listener de `DatasetIngested`** al cerrar cada ingesta del catálogo, con la misma frontera que la federación —el `startedAt` de esa ejecución— y en las dos direcciones: marca lo que no se ha visto (`last_seen_at < startedAt` y `delisted_at is null`) y **quita la marca a lo que ha vuelto a aparecer**. Una ficha que reaparece vuelve a estar listada sin rastro de excepción.
4. **Solo se marca si la ejecución trajo registros.** Una ingesta que termine con éxito y 0 registros no da de baja el catálogo entero.
5. La API lo publica como un **hecho fechado, no como una categoría**: `listed` (booleano) y `delistedAt` en el listado y en el detalle, filtro `listed=true|false` en `GET /catalog/datasets` y recuento `notListed` en `GET /catalog/summary`. `summary.datasets` **sigue contando todas las fichas**, listadas o no: cambiar ese número en silencio rompería la serie de quien ya lo consume.
6. El `caveats` de toda respuesta del monitor dice exactamente qué significa la marca: **que la ficha no apareció en el último listado ingerido**. No dice que el ayuntamiento la haya retirado, ni que el dato haya desaparecido, ni que sea un error del publicador (regla 6). Esas son tres explicaciones distintas y compatibles con lo observado, y el observatorio no elige entre ellas.
7. **Las fichas no listadas se siguen observando** y siguen tomando instantánea diaria. Dejar de observarlas sería decidir que ya no interesan; además, que la distribución de una ficha retirada siga respondiendo (o deje de hacerlo) es precisamente un hecho que merece quedar registrado.

## Consecuencias

- El primer despliegue con `V010` marcará 4008 y 4010 en la siguiente ingesta del catálogo, y `summary` pasará a decir `datasets` 436, `notListed` 2. La diferencia con el `totalCount` 434 de la fuente deja de ser un descuadre invisible y pasa a ser una cifra publicada.
- La marca es **provisional y se recalcula en cada ingesta completa**: no es un estado que haya que administrar ni que pueda quedar obsoleto. Si el listado municipal fallara de forma parcial pero exitosa (menos registros de los que hay, sin error), habría un marcado masivo espurio; se corrige solo en la siguiente ingesta buena, y un salto de dos a docenas en `notListed` es en sí mismo la señal de que lo que falla es la fuente.
- `firstSeenAt`/`lastSeenAt` siguen siendo el dato crudo y no cambian de significado. `delistedAt` es una lectura derivada de ellos, y se puede recomputar.
- Cuando haya varias semanas de instantáneas (ESTADO §4), estas fichas serán el primer caso de serie que se corta sin que el dato se degrade: conviene mirarlas al decidir la categoría observada (ADR-005 §6).

## Alternativas descartadas

- **Borrar la ficha**, como en la federación: destruiría el histórico de frescura, que es el producto.
- **No añadir columna y derivar «listada» al leer** comparando `last_seen_at` con el inicio de la última ingesta correcta: obliga a que el modelo de lectura del catálogo consulte el registro de ejecuciones de `ingestion` en cada consulta, y hace que el número dependa de cuándo se pregunta y no de lo que se observó.
- **Excluir por defecto las no listadas del listado y del recuento**: cambia en silencio las cifras que ya se publican y esconde justo lo que se quiere enseñar.
- **Llamarlo `retirada`, `eliminada` o `rota`**: son afirmaciones sobre lo que hizo el publicador; lo observado es únicamente que no apareció en el listado (regla 6).
- **Guardar un histórico de apariciones** (una fila por ingesta y ficha): mucho volumen para una señal que hoy son dos filas; `firstSeenAt`, `lastSeenAt` y `delistedAt` bastan para lo que se publica.
