# ADR-022 — Arquitectura y calidad del frontend: las reglas van en tests

**Fecha**: 2026-09-17 · **Estado**: aceptada · **Contexto**: cierra la auditoría de `docs/ESTADO.md` §4, cuyo
informe completo está en `docs/auditoria-frontend.md`. Amplía ADR-020 (qué se enseña) y ADR-021 (cómo se ve)
con **cómo se sostiene**.

## 1. Contexto y método

El frontend se escribió entero en una sesión y se rediseñó entero en la misma. Funcionaba, estaba comprobado
por fuera y **no lo había mirado nadie por dentro**. La auditoría se hizo antes de publicarlo y antes de que
creciera, que es cuando corregir la estructura sigue siendo barato.

El método fue el del proyecto y no otro: comparar contra la fuente real —los ocho `*Dtos.java` del backend y
respuestas de la instancia— en vez de razonar de memoria, medir antes de decidir, y convertir cada conclusión
en algo que corra solo. De ahí sale la decisión que ordena a las demás:

> **Toda regla del frontend que importe se escribe como test.** Una regla que solo vive en una ADR o en un
> comentario se incumple en la siguiente sesión sin que nadie se entere, y este frontend ya tenía tres columnas
> vacías en producción por una regla que nadie vigilaba.

## 2. Las fronteras del frontend son un test

`core/` · `ui/` · `map/` · `pages/` eran cuatro carpetas sin ninguna regla que las sostuviera.
`src/app/architecture.spec.ts` las vigila en cada `npm test`, con el mismo papel que `ModularityTests` y
`HexagonalArchitectureTests` en el backend:

1. **Capas**: `core` no importa de nadie; `ui` y `map` solo de `core`; una página compone; el armazón solo
   carga páginas.
2. **Ninguna dependencia de tiempo de ejecución** que no sea Angular o su rxjs (ADR-020 §3). Playwright,
   axe-core, vitest y prettier son de desarrollo y este test es lo que impide que una de ellas se cuele.
3. **El cliente HTTP y las URL viven solo en `core/api.ts`**, y nadie llama a `fetch` por su cuenta.
4. **`ui/` y `map/` no conocen `Observatory`**: reciben lo que pintan.
5. **Ninguna sección importa otra sección.** La definición de sección son las **rutas**, no la carpeta: lo que
   `app.routes.ts` carga con `loadComponent` es una sección, y `matrix` o `district-card` son piezas de la suya.
6. **Carga diferida real**: el armazón no importa ninguna página estáticamente.

Se lee el código con `import.meta.glob('?raw')` y no con `node:fs` para no depender de los tipos de Node en los
tests. **Las reglas se prueban al revés antes de darlas por buenas**: una violación deliberada de cada una tiene
que poner el test en rojo, y las cuatro lo hicieron.

## 3. Una pieza de estado en vez de siete copias

La repetición entre secciones era deuda, no variedad: diez señales, cinco métodos y el desempaquetado del sobre,
copiados siete veces, con cuatro matices reales en total. Se extraen dos piezas a `core/state.ts`:

- **`Loaded<T>`** para lo que se pide entero: resumen, agregación, cruce, ficha de junta. Guarda el sobre
  completo, porque `source`, `ingestedAt` y `caveats` **son producto** y no metadatos (ADR-020 §10).
- **`Explorer<T, R>`** para lo que se pagina: página, orden, filtros y última respuesta. Cambiar un filtro
  vuelve a la página 0, porque la página 7 de otro filtro no existe, y cada cambio **vuelve a pedir** (regla 8).

Quien compone sigue siendo la página: elige qué pide, cómo traduce sus filtros y qué texto pone. Lo que deja de
repetirse es el mecanismo. `FilterValues` se muda a `core/state`, que es donde vive el estado de pantalla, y el
**resumen del catálogo** se mete en el sobre común dentro de `core/api.ts` —es el único que llega sin sobre— para
que ninguna pantalla lleve estado propio por una rareza de la API.

## 4. Los tipos no pueden mentir

`core/types.ts` es la lectura en TypeScript del contrato de `/v3/api-docs`, escrita a mano contra respuestas
reales. La regla que faltaba, y que ahora está escrita en el propio fichero:

> **Un tipo puede declarar menos campos que la respuesta, nunca otros ni con otra nulabilidad.** Elegir qué se
> usa es legítimo; declarar un campo que no existe es una mentira que el compilador no puede ver.

Tres consecuencias concretas:

1. **Ninguna interfaz tiene índice abierto.** El `[key: string]: unknown` de `Dataset` es lo que escondió que
   la pantalla leía `observationMethod`, `observedLastChange` y `declaredFreshness` mientras la respuesta trae
   `latestObservationMethod`, `latestObservedChange` y `latestFreshness`. Tres columnas vacías en producción, y
   nada fallaba. El parámetro de ordenación sí se llama `observedLastChange`, que es la trampa.
2. **Los mapas que llegan en JSON son `ApiMap<V>`** (`Partial<Record<string, V>>`): la clave que se pide puede
   no estar. `Record<string, V>` promete lo contrario y dejó `values[measure.id].toLocaleString()` a un paso del
   mismo `TypeError` que ya reventó la pantalla con `key: null` (ADR-021).
   Se **probó `noUncheckedIndexedAccess` y se descartó midiendo**: 37 errores, 30 de ellos en Jenks, la
   proyección y el gráfico de líneas, donde el índice está probado por construcción. La bandera habría cambiado
   ruido por señal; la mentira estaba en el borde JSON y se corrige ahí.
3. **Hay un guardián**: `npm run contract` graba la **forma** de veinte respuestas reales —nombres de campo y
   tipos, **ni un valor**, así que no puede entrar un dato personal en el repositorio (regla 22)— y
   `core/contract.spec.ts` compara cada interfaz con la suya, sin red. Dos reglas asimétricas: todo campo
   declarado tiene que existir; y si la muestra lo trajo nulo, el tipo tiene que admitirlo —al revés no, porque
   tres filas sin nulo no demuestran nada—. El mapa interfaz→muestra tiene que estar completo: un tipo nuevo no
   entra sin decir de qué respuesta sale. En su primera ejecución encontró que
   `SpendingBucket.awardedAmount` llega nulo.

La forma grabada se vuelve a grabar a mano, como los fixtures de los spikes (regla 16), y su fichero dice contra
qué instancia y en qué fecha se grabó.

## 5. Tres estados, y el fallo se dice

Ninguna de las siete copias del patrón tenía estado de carga, y de ahí salían tres afirmaciones falsas: un
«Leyendo la serie…» eterno cuando la petición fallaba, un «ningún registro casa con estos filtros» cuando no se
había podido preguntar, y una portada que se tragaba seis errores y pintaba un guion. Para un producto cuya
regla primera es no decir más de lo que el dato sostiene, eso no es presentación: es una afirmación sobre el
dato.

- **El estado es explícito y tiene tres valores**: `loading`, `ready`, `failed`. Vacío y cargando **no** son lo
  mismo, y fallo y vacío tampoco.
- **El motivo va separado de la frase**: un 400 trae su `detail` y se enseña tal cual —dice qué parámetro no
  acepta—; un `status` 0 se nombra aparte, porque en un frontend servido desde otro dominio suele ser la red o
  el CORS y no un error de datos.
- **Botón de volver a intentarlo** en cada bloque, y **sin reintento automático**: un error se enseña, no se
  disimula, y quien mira decide si insiste. Tampoco hay caché de respuestas (ADR-019 §9): enseñar cifras de
  antes sin poder decir de cuándo sería peor que esperar.
- **La tabla no desaparece al recargar**: se queda con `aria-busy` y atenuada, y su fila de vacío dice
  «Leyendo…» mientras se pregunta.

## 6. La accesibilidad se mide con herramientas, y el barrido es del proyecto

ADR-021 §1 puso por regla que nada visual se entrega sin haberlo visto. Faltaba la otra mitad: **lo que no se ve
mirando**. El barrido pasa a ser `npm run sweep`, del repositorio y no del editor —en esta máquina el MCP de
Playwright no conecta, y eso no puede dejar el proyecto sin poder mirar—, y añade a las comprobaciones de
ADR-021 tres cosas nuevas:

- **axe-core en un navegador de verdad**, reglas WCAG 2.1 A y AA, solo impacto serio o crítico. En jsdom no se
  puede medir contraste, así que en jsdom no vale.
- **El recorrido del tabulador**: 25 paradas por pantalla comprobando que el foco no se pierde, que cada parada
  es interactiva y que **el foco se ve** (depende de `:focus-visible`, que necesita navegador real).
- **Que no se pide nada a ningún tercero** (ADR-020 §11), vigilando las peticiones en vez de confiando en que no
  haya URL remotas en el código.

Lo que encontró la primera medición está en el informe; la corrección que vale como regla es esta: **los
`--cat-*` son colores de dato y no se usan como texto pequeño**, y **el modo claro se mide igual que el oscuro**
aunque el oscuro sea el primario. `--text-3` en claro daba 4,20 / 4,06 / 3,81 sobre sus tres superficies, con
4,5 de mínimo, y eso afectaba a la letra pequeña de las siete pantallas.

## 7. Lo que no se usa, se borra

Se quitan cuatro métodos de `core/api.ts` que nadie llamaba, los `pickable`/`picked` de la tabla, el ranking y
el gráfico de barras —una capacidad de «abrir la ficha de un registro» que el comentario anunciaba y ninguna
página conectó nunca—, una función de formato y un campo del mapa.

**Una capacidad que no se usa no se prueba**, y encima engaña a quien lee el código: hace pensar que existe algo
que no funciona. Cuando el detalle de un registro entre de verdad, entrará con su ruta.

## 8. El rendimiento se mide, y lo que queda abierto es de la API

Medido en el navegador contra la instancia real: la portada lanza seis resúmenes y está lista en ~1 s con 17 KB;
el cruce compone tres agregaciones sin caché y tarda 0,80 s; la pantalla más pesada es territorio con 377 KB, y
373 de ellos son los contornos, que se piden **una vez por sesión**. **No hace falta caché en ningún sitio**, y
la de ADR-019 §9 sigue descartada con más razón que antes: ahora hay número.

Lo que sí es un problema está en los ejes de ranking: `spending/grants/aggregations?by=beneficiary` pesa
**2,1 MB** y la pantalla lo usa para 18 filas; `by=supplier` 292 KB; `by=call` 243 KB; `by=cpv` 186 KB. El
frontend ordena y recorta en el navegador, lo que además roza la regla 8. El resultado es correcto —están todos
los grupos, así que el top es el verdadero—, pero se bajan 20.910 beneficiarios para enseñar 18.

**Esa corrección no es de esta ADR**: exige `sort` y `limit` en los recursos de agregación, que es superficie de
API y pide su propia decisión (regla 14). Queda planteada en el informe y en `docs/ESTADO.md`, con la medida al
lado para que se decida con datos y no con impresión.

## 9. Consecuencias

- Cuatro guardianes corren en cada `npm test`: fronteras, contrato de tipos, pruebas de pantalla y los quince
  de lógica que ya había. Total **31** tests, de 15.
- `npm run sweep` es parte del trabajo de frontend, como lo era mirar la pantalla: 64 combinaciones, y sale con
  código de error si alguna tiene aviso.
- `npm run contract` hay que ejecutarlo cuando el backend cambie una respuesta, y el fichero grabado dice
  cuándo se grabó. Si alguien cambia un DTO y no lo regraba, el test avisa de que el tipo declara algo que la
  forma grabada no tiene, que es exactamente lo que se quiere.
- Añadir una dependencia de tiempo de ejecución al frontend ahora **falla el build de los tests**. Si alguna vez
  hace falta una de verdad, se decide y se cambia la regla en el test, que es donde está.
