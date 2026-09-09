# ADR-014 — Open311 no se ingiere: publica un subconjunto estricto del listado de sede

- **Fecha**: 2026-09-09
- **Estado**: aceptada
- **Afecta a**: `SPEC.md` §2 (tabla de fuentes), §4.6 (`citizen`), §9; `CLAUDE.md` regla 18; `docs/ESTADO.md` §4 y §6
- **Se apoya en**: `docs/spikes/S2.3-contraste-open311.md` (peticiones reales del 2026-09-09), `docs/spikes/S0.3-open311.md`, `docs/spikes/S2.2-quejas-ingesta.md`

## Contexto

S0.3 eligió el listado de sede (`quejas-sugerencias/list.json`) como fuente de `citizen` y dejó Open311 **«para contrastar»**, sin cerrar la pregunta. S2.2 midió lo que hacía incómoda esa elección: el listado publica **89.432 registros desde 2013** mientras `statistics.json` cuenta del orden de **40.000 incidencias cerradas al año**. Es un subconjunto y no se conoce el criterio con el que se publica.

Quedaba una hipótesis con consecuencias: que el hueco fuera una **diferencia entre las dos API** y que Open311 publicase registros que la sede omite. Si fuera así, ingerir Open311 recuperaría parte de lo que falta. Y una segunda posibilidad, de producto: que Open311 trajera coordenadas donde la sede no las trae, lo que mejoraría la cobertura de punto (29,1 %), que es el límite del eje territorial.

S2.3 comparó **conjuntos de identificadores** entre las dos fuentes en cuatro meses cerrados de 2017, 2025 y 2026, y sobre 2025 entero (11.895 registros):

- **Cero registros que Open311 publique y la sede omita**, en los cuatro meses y en el año completo. La relación es de subconjunto estricto en un solo sentido.
- Lo que la sede tiene y Open311 no son las **`INTERNAL`** (247 de 11.895 en 2025, `service_code` 2, no documentadas en `services.json`, **404** en el detalle de Open311) y **cinco registros** (0,04 %) que responden 404 en Open311 y **400 en el propio detalle de la sede**, que sí los sirve en el listado.
- **Cero quejas que Open311 sitúe y la sede no.** En los 1.027 identificadores comunes de agosto de 2026, las coordenadas coinciden hasta el último decimal (0 discrepancias) y la junta declarada también (0 discrepancias).
- Open311 empieza en **2017**; la sede, en **2013-01-08**.
- Sus marcas de tiempo están **mal**: `requested_datetime` lleva la hora local convertida dos veces y marcada con `Z`, así que leída como UTC cae 1 h antes del instante real en invierno y 2 h antes en verano (medido: desfase de exactamente el doble del huso, sin excepciones, en 1.892 registros).
- Su ventana temporal **se recorta a tres meses en silencio**: una petición de un año responde 200 con un primer trimestre que parece completo.

## Decisión

1. **Open311 no se ingiere.** No se declara ningún `IngestionJob` sobre `open311/requests.json`. La fuente de `citizen` sigue siendo únicamente el listado de sede (ADR-012).
2. **`open311/services.json` se mantiene** como taxonomía de categorías, que es lo único de Open311 que el producto usa y no depende de `requests.json`.
3. **El hecho medido se publica en los `caveats` de `citizen`**: la otra API pública de las mismas quejas publica un subconjunto estricto de este listado. Es lo que impide leer la diferencia con `statistics.json` como «la sede filtra y Open311 no». Es un hecho fechado y medido, no una interpretación (regla 6): no dice por qué falta lo que falta.
4. **La duda sobre el criterio de publicación sigue abierta** (SPEC.md §9), y así se declara. Lo que cambia es que **ya no se puede cerrar consultando otra API**: si alguna vez se cierra, será preguntando al ayuntamiento.
5. **Los `INTERNAL` no se tocan en esta ADR.** S2.3 aporta evidencia relevante —el ayuntamiento los excluye de su propia API ciudadana y su categoría no está documentada— pero excluirlos o etiquetarlos sigue siendo una decisión editorial que exige su propia ADR (regla 6). Hoy cuentan y se pueden filtrar por su código.
6. **Si alguna vez hay que revisar esto**, la comprobación barata es la de S2.3 §1 sobre un mes cerrado: dos barridos y una diferencia de conjuntos. No hace falta ingerir nada para vigilarlo.

## Consecuencias

- `citizen` no gana ninguna dependencia nueva, ni tabla, ni job, ni fuente que vigilar. La única fuente de quejas sigue siendo una.
- Se evita un problema que la ingesta habría traído: dos marcas de tiempo distintas para el mismo registro, sin forma de decidir cuál guardar salvo descartando la de Open311, que es la mala.
- La cobertura de punto **no mejora** por esta vía. Sigue siendo del 29,1 % y sigue publicándose al lado de cada agregación (ADR-011, S2.2).
- Los defectos observados en Open311 se suman a la lista para comunicar al ayuntamiento (`docs/ESTADO.md` §6): recorte silencioso de la ventana, `totalCount` siempre 0, `fl` que devuelve `lat` sin `long`, doble conversión de las marcas de tiempo y los cinco registros con detalle roto.
- `CLAUDE.md` regla 18 dice «sin tope en OCDS y Open311»: **es falso** para Open311 (tope de 1.000) y se corrige.

## Alternativas descartadas

- **Ingerir Open311 como segunda fuente de `citizen`.** Descartada por medición, no por criterio: no aporta ni un registro, ni una coordenada, ni un campo útil. Habría añadido una fuente que mantener, un barrido por trimestres y unas fechas equivocadas, a cambio de nada.
- **Ingerir solo Open311** en lugar del listado de sede. Peor en todo: empieza cuatro años más tarde, no trae `geometry`, tiene las fechas mal y obliga a trocear el histórico en ventanas de tres meses.
- **Usar Open311 como control periódico** de que el listado de sede no encoge. Tentador y barato, pero sería un job más y una tabla más para vigilar una fuente que hoy es un subconjunto de la otra; y el observatorio ya vigila lo que publica el catálogo por otra vía. Si alguna vez importa, se hace con la comprobación manual de S2.3 §1.
- **Cruzar las dos fuentes por fecha** para detectar huecos. Imposible tal cual: las marcas de tiempo difieren en el doble del huso (§5 del spike). Solo se pueden cruzar por identificador.
