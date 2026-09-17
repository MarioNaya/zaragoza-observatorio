# Auditoría de arquitectura y calidad del frontend

**Fecha**: 2026-09-17 · **Rama**: `feat/s43-frontend-completo` · **Alcance**: `frontend/` entero (7.822 líneas
antes, 8 secciones, 21 ficheros de código y 7 de plantilla).

El frontend se escribió completo en una sesión y se rediseñó completo en la misma, con dos rechazos por el
medio (ADR-020, ADR-021). Funcionaba y estaba comprobado **por fuera**; nadie lo había mirado por dentro. Este
documento contesta las siete preguntas que `docs/ESTADO.md` §4 puso por delante, en el orden de lo que más
podía doler, y dice qué se ha hecho con cada una. Las decisiones que salen de aquí están en
`docs/decisions/ADR-022-arquitectura-frontend.md`.

**Resultado en una línea**: había arquitectura en la cabeza y no en el código; la repetición era deuda; los
tipos mentían y la mentira ya estaba costando **tres columnas vacías en la pantalla publicada**; el estado de
carga no existía; y la accesibilidad, que se escribió con cuidado, incumplía el contraste en **las siete
pantallas en modo claro**. Nada de esto se veía desde fuera, que es justamente el motivo de auditar.

---

## 1. ¿Hay arquitectura o solo hay carpetas?

**Había carpetas.** `core/`, `ui/`, `map/` y `pages/` describían una intención correcta y nada la sostenía: no
existía ninguna regla que impidiese que una página importara de otra, que `ui/` pidiera datos, que un
componente se saltara `core/api.ts` o que entrara una librería de gráficos. El backend tiene
`ModularityTests` y `HexagonalArchitectureTests` corriendo en cada build; el frontend no tenía equivalente.

**Hecho**: `src/app/architecture.spec.ts`, siete comprobaciones en cada `npm test`. Lee los fuentes con
`import.meta.glob('?raw')` —sin dependencias nuevas y sin tipos de Node— y verifica:

| Regla | De dónde sale |
| --- | --- |
| `core` no importa de nadie; `ui` y `map` solo de `core`; una página compone; el armazón solo carga páginas | esta auditoría |
| Ninguna dependencia de tiempo de ejecución que no sea Angular o su rxjs | ADR-020 §3 |
| El cliente HTTP y las URL viven solo en `core/api.ts`; nadie llama a `fetch` | ADR-020 §2 |
| `ui/` y `map/` no conocen `Observatory`: reciben lo que pintan | esta auditoría |
| Ninguna sección importa otra sección —y «sección» son las rutas, no un nombre de carpeta— | esta auditoría |
| El armazón no importa ninguna página estáticamente (carga diferida real) | ADR-020 |

**Las reglas se probaron al revés antes de darlas por buenas**, porque un guardián que no muerde es peor que
ninguno: una página importando otra falla el test de secciones; `ui/stats.ts` inyectando `Observatory` falla el
de datos; un paquete instalado que no es Angular (`vitest`) falla el de dependencias. Un paquete **no**
instalado lo para antes el compilador, que también vale.

Lo que el test **no** cubre y se decide no cubrir: que un componente de `ui/` no reciba un tipo de dominio.
`Colophon` recibe `Source` y eso es deliberado (ADR-020 §10: los `caveats` se pintan tal como llegan).

## 2. ¿Cuánto se repite?

**Era deuda, no repetición sana.** Las siete secciones tenían el mismo patrón copiado siete veces: diez
señales de estado, `setFilter`, `clearFilters`, `setSort`, `setPage` y uno o dos `loadX`, más el desempaquetado
del sobre (`source`, `ingestedAt`, `caveats`) a mano en cada constructor. Los matices por página eran cuatro
—la traducción de fechas a instantes en quejas, el sobre distinto del catálogo, la serie aparte del
presupuesto, la ficha de junta del cruce— y ninguno justificaba copiar el resto.

Y la copia tenía un coste que se ve en la pregunta 4: **ninguna de las siete copias tenía estado de carga**, así
que el fallo de la pregunta 4 estaba siete veces.

**Hecho**: dos piezas en `core/state.ts` —`Loaded` para lo que se pide entero y `Explorer` para lo que se
pagina— y `ui/state.ts` para pintar las dos situaciones que faltaban. Saldo del cambio: **927 líneas fuera,
813 dentro**, y de esas 813 hay 293 de tests nuevos y 192 de las dos piezas; el código de las páginas baja de
verdad. `FilterValues` se muda de `ui/filters` a `core/state`, que es donde vive el estado de pantalla.

## 3. ¿Los tipos dicen la verdad?

**No, y ya se estaba pagando.** `Dataset` llevaba `[key: string]: unknown`, y detrás de ese índice abierto la
pantalla del catálogo leía **tres campos que no existen**:

| La pantalla leía | La respuesta trae | Efecto |
| --- | --- | --- |
| `observationMethod` | `latestObservationMethod` | la línea «método observado» de cada ficha, vacía |
| `observedLastChange` | `latestObservedChange` | la columna «Observamos cambio», vacía |
| `declaredFreshness` | `latestFreshness` | la columna «Frescura declarada», vacía |

La trampa es que el **parámetro de ordenación sí se llama `observedLastChange`** (`sort=observedLastChange,desc`
funciona y está en CLAUDE.md), así que el nombre parecía correcto. Comprobado contra la instancia: la respuesta
de `/api/v1/catalog/datasets` trae los tres con prefijo `latest`.

Lo demás que encontró la comparación uno a uno contra los ocho `*Dtos.java` del backend:

- **Nulabilidad al revés** en seis campos: `Grant.naturalPerson` (nulo en las 2.609 concesiones sin enlace de
  beneficiario), `GrantsSummary.firstYear`/`lastYear`, `BudgetSummary.firstSnapshot`/`lastSnapshot`/
  `latestLoaded` y las fechas extremas de tres resúmenes. La portada hacía `firstSnapshot.slice(0, 4)` sobre
  una de ellas.
- **Campos que faltaban** y que el frontend suplía adivinando: `UrbanBucket.total` (el recuento en la unidad
  que la respuesta declara) y `UrbanAggregation.assignment`/`matched`/`unassigned`.
- **Mapas de JSON declarados como `Record<string, number>`**, que promete que cualquier índice es un valor.
  `values[measure.id]` y `perThousandInhabitants[measure.id]` estaban a un paso de un
  `undefined.toLocaleString()` en la matriz y en la ficha de junta, que es el mismo fallo que ya reventó la
  pantalla con `key: null` (ADR-021). Ahora son `ApiMap<V>` (`Partial<Record>`) y el compilador lo ve.

**Se probó `noUncheckedIndexedAccess` y se descartó con la medida delante**: 37 errores, de los que 30 están en
Jenks, la proyección y el gráfico de líneas, donde el índice está probado por construcción. La mentira vivía en
el borde JSON y ahí se ha corregido.

**Hecho, además de corregir**: un guardián, porque esto vuelve a pasar en cuanto alguien añada un campo.
`npm run contract` graba la **forma** de veinte respuestas reales en `src/app/core/contract.shape.json` —solo
nombres de campo y tipos, ni un valor, así que no puede colarse un dato personal (regla 22)— y
`core/contract.spec.ts` compara cada interfaz con la respuesta que la produce, sin red. Dos reglas asimétricas:
todo campo declarado tiene que existir, y si la muestra lo trajo nulo el tipo tiene que admitirlo; al contrario
no, porque tres filas sin nulo no demuestran nada. El mapa interfaz→muestra tiene que estar completo, así que
un tipo nuevo no entra sin decir de qué respuesta sale.

**En su primera ejecución encontró uno más**: `SpendingBucket.awardedAmount` llega **nulo** cuando el grupo no
tiene adjudicaciones activas, y el tipo decía `number`.

## 4. ¿Qué pasa cuando la API falla?

**Se decía que no había datos.** No había estado de carga en ninguna página, así que la condición de cada
bloque era «tengo datos o no los tengo», y eso produce tres afirmaciones falsas:

- «Leyendo la serie…» se quedaba puesto **para siempre** si la petición fallaba.
- La tabla decía «ningún registro casa con estos filtros» cuando en realidad no se había podido preguntar.
- La portada se tragaba **los seis** errores con `error: () => undefined` y pintaba un guion: desde fuera, «no
  hay dato» y «no se pudo leer» eran lo mismo, en la pantalla de entrada.

Para un producto cuya regla primera es no decir más de lo que el dato sostiene (SPEC.md §1.1), esto no es un
detalle de presentación.

**Hecho**: estado explícito de tres valores (`loading` · `ready` · `failed`), el motivo separado de la frase
—un 400 trae su `detail` de la API y se enseña tal cual, porque dice qué parámetro no acepta; un `status` 0 se
nombra aparte, que es red o CORS y no un error de datos—, botón de **volver a intentarlo** en cada bloque y
`aria-busy` en la tabla, que al recargar se queda en su sitio atenuada en vez de desaparecer o mentir. **Sin
reintento automático**: un error se enseña, no se disimula.

## 5. Accesibilidad de verdad

Estaba escrita con cuidado —`sr-only`, `aria-sort`, `role="alert"`, salto al contenido, cajón con
`aria-expanded`— y **nunca se había medido**. Con axe-core en un navegador de verdad salieron tres clases de
fallo, todas de impacto «serio»:

| Hallazgo | Medida | Corrección |
| --- | --- | --- |
| **El modo claro incumple el contraste en las siete pantallas.** `--text-3` sobre sus tres superficies | 4,20 / 4,06 / 3,81 (mínimo 4,5) | `#606a7b`: 5,09 / 4,91 / 4,60 |
| Copete de la portada y sección activa de la barra, con color de dato como texto | 4,06:1 y peor | el color se queda en la marca y el relleno; el texto pasa a tinta y la activa gana barra |
| Enlaces dentro de texto corrido, distinguidos solo por color | 1,08:1 y 1,25:1 frente al texto (mínimo 3:1) | subrayados |
| Las cifras de cabecera no eran una lista de definiciones (`dd` antes de `dt`, `<p>` dentro del `div`) | violación estructural | `dt` + dos `dd`; el orden visual lo pone la rejilla |

ADR-021 midió los colores **de los gráficos** con cuidado (incluida la deuteranopía) y dio por bueno el resto;
el modo claro se escribió sin medir porque el oscuro es el primario. Es exactamente el hueco que una
herramienta encuentra y una lectura atenta no.

**El teclado** también se comprueba ahora, y eso axe no lo hace: 25 tabulaciones por pantalla verificando que el
foco no se pierde, que cada parada es un elemento interactivo de verdad y que **el foco se ve** (depende de
`:focus-visible`, que solo existe en un navegador real). Las 64 combinaciones pasan.

## 6. Rendimiento medido, no supuesto

Medido dentro del navegador, con la instancia real al otro lado (ancho 1440, modo oscuro):

| Pantalla | Listo en | Peticiones | Bajado | La más lenta |
| --- | --- | --- | --- | --- |
| Portada | 1,0–1,2 s | 6 | 17 KB | 490 ms |
| Presupuesto | 0,8 s | 5 | 39 KB | 130 ms |
| Contratación | 0,8 s | 3 | 42 KB | 156 ms |
| Subvenciones | 1,0 s | 3 | 25 KB | 332 ms |
| Quejas | 1,0 s | 3 | 59 KB | 380 ms |
| Actividad urbana | 1,0 s | 3 | 27 KB | 330 ms |
| Territorio | 1,7 s | 2 | **377 KB** | 985 ms |
| Catálogo | 0,8 s | 2 | 24 KB | 129 ms |

Dos lecturas:

1. **Las seis peticiones de la portada y las tres agregaciones del cruce no son el problema.** El cruce compone
   sin caché a propósito (ADR-019 §9) y tarda 0,80 s contra producción, medido aparte con `curl` en tres
   pasadas. No hace falta caché y sigue sin hacerla falta.
2. **El problema está en los ejes de ranking**, y no lo vería nadie mirando la pantalla de entrada. La
   agregación por beneficiario de subvenciones pesa **2,1 MB** y la pantalla la usa para pintar 18 filas:

   | Eje | Tamaño |
   | --- | --- |
   | `spending/grants/aggregations?by=beneficiary` | **2.117 KB** |
   | `spending/aggregations?by=supplier` | 292 KB |
   | `spending/grants/aggregations?by=call` | 243 KB |
   | `spending/aggregations?by=cpv` | 186 KB |
   | `citizen/aggregations?by=category` | 65 KB |

   El frontend ordena y recorta esos grupos **en el navegador**, lo que además roza la regla 8 (el backend
   ordena, filtra, pagina y agrega). El resultado es correcto —están todos los grupos, así que el top es el
   verdadero—, pero se descargan 20.910 beneficiarios para enseñar 18.

   **Esto no se arregla en el frontend**: pide `sort` y `limit` en los recursos de agregación, que es superficie
   de API y por tanto decisión con ADR (regla 14). Queda planteado, no hecho.

Lo que sí se corrigió aquí: los 373 KB de contornos ya se piden **una vez por sesión** (`shareReplay`), y se
mantiene sin simplificar la geometría, que ADR-020 §4 descarta a propósito.

## 7. Los tests que faltaban

Antes: 15, todos de lógica (clasificación del mapa, contrato de la tabla, armazón). **Cero** cubrían que una
pantalla pintara lo que la API devuelve, que es donde estaban los dos fallos peores.

Ahora: **31**, y los nuevos cubren justo eso, con `provideHttpClientTesting` y cuerpos reales recortados —que
las filas y el total salen de la respuesta; que el listado se pide siempre con orden, página y tamaño; que un
400 enseña el aviso con su motivo y **no** dice «ningún registro»; que el botón vuelve a pedir; que la portada
nombra el resumen que no pudo leer— más la regresión que más importa: **que el catálogo pinta método observado,
fecha observada y frescura declarada**, que era lo que salía vacío.

Y el barrido visual **deja de lanzarse a mano**: `npm run sweep` compila, sirve el build con la misma caída a
`index.html` que el `.htaccess` del alojamiento y recorre 8 rutas × 4 anchos × 2 modos capturando pantalla,
comprobando que nada desborda en horizontal, que la consola está limpia, que **no se pide nada a ningún
tercero**, el recorrido del tabulador y axe-core. Playwright y axe-core entran como dependencias de desarrollo;
el test de fronteras comprueba que no se colaron en el bundle.

## 8. Lo que apareció sin estar en la lista

Seis cosas que la auditoría no iba buscando y que estaban rotas o mal:

1. **El panel de cobertura por año de quejas no se pintaba nunca.** Leía `coverageByYear` del eje por junta,
   donde la API manda lista vacía a propósito: solo viene en el eje de serie `district_year`. La pantalla lo
   prometía dos párrafos antes («mira la cobertura por año debajo») y es la cifra que ADR-015 declara necesaria
   para comparar dos años. Ahora se pide aparte, solo al entrar en ese eje.
2. **La serie de actividad urbana cambiaba de unidad sin avisar**: `bucket.licences || bucket.premises` caía a
   locales en cuanto un año tenía cero licencias. Se usa `total`, que es el recuento en la unidad declarada.
3. **El ranking de contratación mezclaba licitado y adjudicado** en la misma barra
   (`awardedAmount || tenderedAmount`), que es justo lo que ADR-017 y la regla 33 prohíben con estos dos
   importes. Ahora hay selector de cifra y la barra dice cuál mide.
4. **Los filtros ofrecían menos estados que la API**: faltaba `AMBIGUOUS` en quejas y en actividad urbana —13
   quejas caen en dos juntas a la vez y ADR-011 obliga a registrar esa ambigüedad, no a esconderla— y
   `REJECTED`/`UNKNOWN` en quejas. Los cuatro comprobados contra la instancia antes de ofrecerlos.
5. **Los rótulos del eje de tiempo se pisaban.** En la serie de 165 meses el último se forzaba siempre y caía a
   media distancia del anterior: «septiembre de 2026» encima de «febrero de 2025». Se mide el ancho del rótulo
   y se pinta solo el que cabe, como el mapa ya hacía con los nombres de las juntas.
6. **Código que nadie llamaba**: cuatro métodos de `core/api.ts` (`process`, `budgetSnapshots`, `dataset`,
   `freshnessHistory`), los `pickable`/`picked` de la tabla, el ranking y el gráfico de barras —la capacidad de
   «abrir una ficha» que el propio comentario anunciaba y ninguna página conectó nunca—, `countShort` y un campo
   del mapa. Fuera: una capacidad que no se usa no se prueba y engaña a quien lee el código.

Y una trampa del entorno, para quien vuelva a tocar esto: **`/api/v1/geo/boundaries` se sirve como
`application/geo+json` y responde 406 a un `accept: application/json`**. El `HttpClient` del navegador manda
`*/*` y por eso la pantalla nunca lo notó; el grabador del contrato sí.

## 9. Estado al cerrar la auditoría

- **31 tests** en verde (15 antes), build sin avisos.
- **64 combinaciones** del barrido sin un aviso: ni desborde horizontal, ni consola sucia, ni petición a
  terceros, ni violación seria de axe, ni problema de teclado.
- Cuatro guardianes nuevos que corren solos: fronteras, contrato de tipos, pruebas de pantalla y barrido.

**Lo que queda abierto y por qué**:

1. **El peso de las agregaciones de ranking** (§6): necesita `sort` y `limit` en la API, que es superficie y
   pide decisión. Es lo único de la auditoría que no se puede cerrar solo en el frontend.
2. **Rutas profundas compartibles y exportación a CSV**: ya estaban en la lista de pendientes del frontend y la
   auditoría no las toca. La segunda además no es solo frontend (¿genera el CSV el backend, con su `source` y
   sus `caveats` dentro?).
3. **El detalle de un registro**: la capacidad estaba insinuada en el código y se ha quitado. Cuando entre,
   entra con su ruta, no con un `pickable`.
4. **El movimiento sí estaba bien** y conviene dejarlo escrito para no «arreglarlo» dos veces: la animación de
   entrada vive dentro de `@media (prefers-reduced-motion: no-preference)` y el cajón desactiva su transición
   bajo `reduce`. Lo único que queda son transiciones de 120 ms en el hover, que no se tocan.
