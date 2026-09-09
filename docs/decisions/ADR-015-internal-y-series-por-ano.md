# ADR-015 — Los `INTERNAL` se cuentan aparte y las series por año se publican como cruce: columnas, no ajustes

- **Fecha**: 2026-09-09
- **Estado**: aceptada
- **Afecta a**: `SPEC.md` §4.6 (`citizen`), §4.7, §9; `CLAUDE.md` regla 33; `docs/ESTADO.md` §4
- **Se apoya en**: `docs/spikes/S2.2-quejas-ingesta.md` y su adenda, `docs/spikes/S2.3-contraste-open311.md`, `docs/spikes/S0.3-open311.md`, `docs/decisions/ADR-011-resolucion-territorial.md`, `docs/decisions/ADR-014-open311-no-se-ingiere.md`

## Contexto

Dos hilos de `citizen` quedaron abiertos desde la octava sesión, y los dos son la misma pregunta con dos caras: **qué hace el producto con una cifra que no se puede leer tal cual**. La regla 6 prohíbe conclusiones; su segunda mitad exige dar cruces, denominadores y series para que el usuario saque las suyas. Entre las dos está el margen de esta ADR.

**1. Los servicios `INTERNAL`.** Son el 2,7 % de lo ingerido (2.449 de 89.432) y no son quejas ciudadanas. Lo que se sabe, todo medido:

- llevan siempre `service_code` **2** (en 2025, 247 registros con ese nombre y 247 con ese código, correspondencia exacta en los dos sentidos; sobre los 89.432, la agregación por categoría devuelve una sola categoría con ese nombre y su clave es el 2);
- `open311/services.json` **no documenta** la categoría, ni por nombre ni por código;
- **Open311 no publica ninguno**, ni en su listado ni en su detalle, que responde 404 (S2.3): es el propio ayuntamiento quien los deja fuera de su API ciudadana.

Nada de eso decide por sí solo. Hasta ahora contaban y solo se podían quitar sabiendo de antemano que existían y cuál era su código: quien leyera «quejas por junta» se llevaba un 2,7 % que no son quejas sin enterarse, salvo que leyera el `caveats`.

**2. Las series por junta y año.** La cobertura de punto va del **16 % al 45 % según el año** (S2.2), así que dos años no son comparables tal cual. Hoy cada grupo publica su cobertura al lado, que es lo mínimo honesto, pero construir una serie por junta obligaba a una petición por año y a las cuentas a mano, y el denominador que devolvía la agregación era siempre el del **año más reciente** del padrón, el mismo para toda la serie.

## Decisión

### Los `INTERNAL` se cuentan aparte, no se esconden

1. **Siguen contando por defecto.** Ninguna cifra cambia de valor por esta ADR. Excluirlos en silencio sería que el observatorio decidiese qué es una queja (regla 6).
2. **Cada grupo de cada agregación publica cuántos son**, en el campo `internal`, y la agregación entera publica su suma. El resumen de `citizen` publica el total. Así la resta la puede hacer quien lee, sobre la cifra que esté mirando y sin conocer el código de antemano.
3. **Se pueden quitar o aislar a petición**: el parámetro `internal` acepta `include` (por defecto), `exclude` y `only`, en el listado y en las agregaciones. Es una elección de quien lee, explícita en la URL, no una política del producto.
4. **El criterio es el código, no el nombre.** `service_code = 2`. El código es la clave de la taxonomía del origen; el nombre es texto libre que en esta misma fuente ya ha llegado con caracteres de control (ADR-011 §5). La evidencia y el riesgo residual —que el origen empiece a publicar `INTERNAL` bajo otro código— quedan escritos en `InternalServices`, y el campo `internal` los haría visibles antes que cualquier alerta.
5. **El `caveats` lo dice entero**: qué son, que cuentan, cómo quitarlos y qué se sabe de ellos.

### Las series por año se publican como cruce, con el denominador de cada año

6. **Eje nuevo `district_year`** en `GET /citizen/aggregations?by=district_year`: un grupo por junta **y año de alta**, con el total, las cerradas, las que tienen punto, **su propia cobertura**, la mediana de respuesta, los `internal` y el padrón. Es una sola petición para toda la serie, y el usuario normaliza como quiera.
7. **El padrón de cada grupo es el de su propio año.** Usar el más reciente para toda la serie mezcla dos cosas distintas y produce una tasa que no es de ningún año. `geo` gana para eso una operación pública, `populations()`, que devuelve el padrón por junta y año.
8. **Los años sin padrón salen sin denominador.** La serie municipal tiene 2020, 2021, 2022 y 2024 —falta 2023 (S2.1)—, así que los demás años traen `population`, `populationYear` y `perThousandInhabitants` en `null`. **El hueco se ve; no se interpola, ni se rellena con el año vecino.** Rellenarlo sería inventar un dato que el ayuntamiento no publica.
9. **La cobertura que permite comparar años va aparte, en `coverageByYear`.** Dentro de un grupo por junta la cobertura es **siempre del 100 % por construcción** —sin punto no hay junta (ADR-011 §2), así que lo que no se pudo situar no aparece en ningún grupo—, y publicar ese 1,00 como «la cobertura del grupo» invita justo al error que esta ADR quiere evitar. `coverageByYear` cuenta **todas** las quejas de cada año, con y sin punto, y dice cuántas se situaron: del 16 % al 45 % según el año. Es lo que dice de qué está hecha cada columna de la serie, y se descubrió mirando la respuesta real: la primera versión publicaba un 1,00 por grupo que no significaba nada.
10. **Nada se ajusta por cobertura.** Ni en este eje ni en ningún otro. Un ajuste supondría que lo no geolocalizado se reparte como lo geolocalizado, y eso **no está comprobado**; sería exactamente la clase de conclusión que la regla 6 prohíbe al producto. Lo que se da son las columnas para que quien quiera hacerlo lo haga y responda de ello.
11. **El eje territorial sigue agrupando solo lo que tiene junta**, y la respuesta sigue trayendo `unassigned` y el reparto por `assignment` (regla 7, ADR-011 §7). El cruce no cambia esa frontera: la añade una dimensión.

## Consecuencias

- La superficie de la API crece en un eje (`district_year`), un parámetro (`internal`) y dos campos por grupo (`internal`, `year`), más `internal` en el resumen y en la agregación. Nada deja de funcionar como antes: los valores por defecto son los de ayer.
- `geo` gana una operación pública (`populations()`) y `citizen` la consume por la superficie del módulo, sin tocar sus tablas (regla 3).
- Un grupo del cruce puede tener muy pocos registros: 29 juntas × 14 años con la cobertura que hay deja celdas de un dígito. Eso no se oculta ni se agrupa: la cifra pequeña es un hecho, y `withPoint` y `pointCoverage` viajan al lado para que se vea de qué está hecha.
- El `caveats` del cruce añade tres advertencias propias: la cobertura por año, el padrón que falta y que 2013 y 2014 son un régimen distinto (casi todo con punto, nada con junta declarada).
- Si algún día se quisiera publicar una serie **ajustada**, esta ADR no lo impide: exige una ADR nueva que diga qué se supone y por qué, y que lo publique como lo que sería, una estimación del producto y no un dato de la fuente.

## Alternativas descartadas

- **Excluir los `INTERNAL` por defecto.** Es lo que hace el ayuntamiento en Open311 y deja las cifras más limpias de leer, pero convierte al observatorio en el que decide qué es una queja. Con el recuento al lado, quien lea puede hacer esa misma exclusión y sabe que la ha hecho.
- **Dejar los `INTERNAL` como estaban** (contando y filtrables por su código). Sin trabajo, pero exige saber de antemano que existen y cuál es su código: la información estaba, pero no donde se lee la cifra.
- **Publicar series ya normalizadas por cobertura.** Descartada por la regla 6 y porque el supuesto que necesita no está comprobado. Es además el error más difícil de deshacer: una vez publicada la cifra ajustada, se cita sin el supuesto.
- **Rellenar el padrón que falta** (interpolar 2023, o usar el año más cercano). Descartada: la discontinuidad de la serie es un hecho de la fuente que ADR-011 obliga a declarar, no un defecto que el observatorio deba tapar.
- **Un endpoint aparte para las series.** Habría duplicado los filtros, los `caveats` y el reparto por asignación. Un eje más en la agregación que ya existe deja una sola superficie que aprender.
