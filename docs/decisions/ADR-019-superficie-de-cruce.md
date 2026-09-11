# ADR-019 — La superficie de cruce: columnas comparables sobre el mismo eje, no vistas precocinadas

- **Fecha**: 2026-09-12
- **Estado**: aceptada
- **Afecta a**: `SPEC.md` §3 (fase 4), §4.7 (la ruta del borrador cambia), §4.8, §9 (cierra la duda «superficie de la API de cruce»)
- **Se apoya en**: ADR-011 (la junta se resuelve por geometría), ADR-015 (el producto da columnas, no ajustes), ADR-005 (eje observado), y las medidas de cobertura de S2.2 y S2.4
- **Funda**: el módulo `territory`

## Contexto

`SPEC.md` §3 pone una condición a la fase 4 que no es de implementación sino de diseño: **la ADR de la
superficie de cruce va antes de la primera pantalla**. La razón está en §1.1: si el análisis lo hace quien lee,
el backend no puede servir vistas precocinadas, porque una vista precocinada ya ha elegido el cruce. Pero la
regla 8 sigue vigente y dice lo contrario de lo que parece: agrega el backend, no el navegador. Las dos cosas a
la vez obligan a una tercera figura —**primitivas componibles**— y la pregunta que esta ADR contesta es cuánta
libertad se abre en esos parámetros y cómo se acota sin volver al punto de partida.

El proyecto llega aquí con seis módulos y con una asimetría que conviene mirar antes de diseñar nada:

| módulo | ¿tiene eje territorial? | cobertura de punto medida |
|---|---|---|
| `citizen` (quejas) | sí, por geometría (ADR-011) | **del 16 % al 45 % según el año** (S2.2) |
| `urban` (locales con licencia) | sí, por geometría (ADR-011) | **89,4 %** (S2.4) |
| `spending` (contratación, presupuesto, subvenciones) | **no, ninguna de las tres fuentes** | — |
| `geo` | es el eje | — |
| `catalog`, `ingestion` | no son dominio observable | — |

De ahí salen los dos hechos incómodos que gobiernan toda la decisión:

1. **Las dos únicas medidas territoriales tienen coberturas que se diferencian en un factor de cinco.** Poner
   89.432 quejas y 42.342 locales en la misma tabla sin decirlo sería invitar a leer como diferencia de ciudad
   lo que es diferencia de geolocalización.
2. **El dinero no se puede repartir por junta.** No es un hueco por rellenar: está medido sobre las fuentes
   enteras (S0.2, S0.6, S3.1) y ADR-011 §2 prohíbe geocodificar direcciones para taparlo. Un cruce territorial
   que ofreciera una columna de gasto estaría inventándosela.

Y un tercero, del denominador: el padrón por junta tiene **2020, 2021, 2022 y 2024, y no 2023** (S2.1). Cualquier
tasa depende de un año que hay que declarar, y hay años sin ninguno.

## Decisión

### 1. El cruce es una matriz sobre el eje territorial, no un lenguaje de consulta

Dos recursos, y ninguno admite una dimensión que no sea la junta:

- `GET /api/v1/territory/districts` — **una fila por junta** (las 29) y **una columna por medida pedida**.
- `GET /api/v1/territory/districts/{id}` — la ficha de una junta: identidad, serie de padrón y el valor de cada
  medida con su cobertura.

Esto **cambia la ruta del borrador** de `SPEC.md` §4.7, que decía `GET /territory/{districtId}`. El motivo es
que el borrador solo preveía la ficha y aquí hace falta además la matriz; con `districts` en medio las dos rutas
se leen igual que las de `geo` y queda sitio para otra unidad territorial (la sección censal, si alguna vez
existe como dato abierto) sin romper nada.

Se descarta el lenguaje de consulta genérico (`?dimension=&measure=&filter=`) por lo que está en
«Alternativas descartadas»: no se puede acotar sin acabar describiendo el modelo de datos en la URL.

### 2. El catálogo de medidas es cerrado, y cada medida se describe a sí misma

El parámetro es `measures=<id>,<id>,…`, con los identificadores de una **lista blanca cerrada**. La libertad
está en **qué medidas se combinan y sobre qué ventana**, no en qué campo se agrega: esto último es lo que
convierte una API en un motor de consultas y obliga a publicar el esquema interno.

Las medidas del arranque son **tres**, de **dos** módulos:

| id | módulo | unidad | fecha sobre la que se aplica la ventana |
|---|---|---|---|
| `citizen.requests` | `citizen` | quejas | alta de la queja (`requested_at`) |
| `urban.premises` | `urban` | locales | **alta del local**, no el año de licencia |
| `urban.licences` | `urban` | licencias | **alta del local**, no el año de licencia |

Tres medidas es poco y es la verdad: **es lo que hay territorializable en los datos abiertos de Zaragoza hoy**,
no una limitación del producto. El catálogo crece **añadiendo una entrada**, no abriendo un parámetro, y cada
entrada nueva llega con la fuente que la respalda.

Cada medida publica en la respuesta, junto a sus valores: su módulo, su **unidad** (`unit`, como ya hacen
`urban` y `spending`: un local con doce licencias es un local y son doce licencias), el campo de fecha sobre el
que se le aplica la ventana, la referencia de su dataset de origen y su `ingestedAt`.

### 3. El denominador es otro catálogo, cerrado y separado del de medidas

`denominator=population|none` (por defecto `population`), más `populationYear` para fijarlo. La distinción entre
una medida y un denominador **no es cosmética**: un denominador es una base de exposición que la regla 7 exige
publicar, y por eso el producto sí divide por él. Otra medida no lo es.

El año del padrón **nunca es implícito**: viaja en la respuesta junta por junta, porque la serie no es continua
y la junta que no lo tenga sale **sin tasa y sin denominador**, con el hueco a la vista. No se interpola
(ADR-015).

### 4. Ninguna aritmética entre medidas

El backend **no divide una medida por otra**, ni publica ninguna columna derivada de dos medidas. Quien quiera
quejas por local tiene las dos columnas y divide.

No es recortar capacidad de análisis —las dos cifras están ahí, y la regla 6 advierte expresamente contra usarla
para eso—: es que para publicar ese cociente el producto tendría que **nombrarlo**, y nombrarlo es afirmar que
las dos columnas son comparables. No lo son: tienen unidades distintas, ventanas que se aplican sobre fechas
distintas y coberturas que se diferencian en un factor de cinco. La división la puede hacer quien lee, que sabe
lo que está dividiendo porque la respuesta se lo ha dicho; el producto no puede hacerla sin opinar.

### 5. La ventana se aplica por medida, sobre la fecha que la medida declara

`from` y `to` son instantes y valen para todas las medidas pedidas, pero **cada medida los aplica sobre su
propia fecha** y lo dice. El caso que obliga a decirlo es `urban`: sus dos medidas filtran por el **alta del
local**, no por el año del expediente de licencia, así que una ventana de 2024 sobre `urban.licences` son las
licencias de los locales dados de alta en 2024, que no son las licencias de 2024. Esto no se arregla en silencio
eligiendo otra fecha: se publica en `dateField` y en `caveats`.

### 6. La cobertura viaja pegada a cada columna, siempre

Cada medida publica, **para la ventana pedida y sobre la ciudad entera**: su total, cuántos registros traen
punto, cuántos cayeron dentro de una junta y cuántos quedaron sin asignar. Es la traducción al cruce de lo que
ADR-015 aprendió en las series por año: dentro de una fila territorial la cobertura vale siempre 1 por
construcción —sin punto no hay junta—, así que la cobertura que significa algo es la del conjunto, y sin ella
una columna de 29 filas parece el total y no lo es.

En consecuencia, **la suma de las 29 filas de una columna no es el total de esa medida**, y la respuesta lo dice
en vez de dejar que se note.

### 7. Nada se ajusta por cobertura

Se arrastra ADR-015 tal cual, y aquí pesa más: ajustar una columna al 16 % de cobertura para compararla con otra
al 89 % supone que lo no geolocalizado se reparte como lo geolocalizado, y eso no está comprobado en ninguna de
las dos fuentes.

### 8. `spending` no aporta ninguna medida, y pedirla es un 400 explicado

No se publica una columna de gasto vacía ni se admite el parámetro para devolver ceros. `measures=spending.*`
responde `400` diciendo que ninguna fuente de gasto municipal publicada tiene dimensión territorial y remitiendo
a `/api/v1/spending`. La ausencia se afirma; no se deja notar.

### 9. `territory` no tiene tablas, ni caché, ni dependientes

Como manda `SPEC.md` §4.8 y la regla 3: compone llamando a las **superficies públicas** de `geo`, `citizen` y
`urban`, no toca una sola tabla ajena y **nadie depende de él**. Para eso, `citizen` y `urban` estrenan su
paquete raíz como API de módulo —hasta hoy solo tenían `*Sources`—, con lo justo para el cruce y nada más.

La consecuencia de no cachear es que una matriz de tres medidas son tres agregaciones más una consulta de
padrón. Con 29 filas y las tablas ya indexadas es barato, y un caché aquí sería estado propio de un módulo que
`SPEC.md` define sin estado.

### 10. La credibilidad que viaja hoy es la nuestra; la del origen espera a que exista el enlace

`SPEC.md` §3 quiere que el eje observado de la fase 1 sea «la capa de credibilidad de todo análisis». Va en dos
tiempos, y esta ADR solo cierra el primero:

- **Ahora**: cada medida publica su `ingestedAt`, que es **cuándo miramos el origen por última vez**. Es nuestro,
  lo sabemos con certeza y no hay que inventar nada para tenerlo.
- **Todavía no**: *cuándo cambió el origen*, que es lo que mide el eje observado (ADR-005). Eso vive en
  `catalog`, indexado por el **id de la ficha municipal**, y **no existe enlace entre una ficha y el
  `DatasetRef` que ingiere un módulo de dominio**. Ese enlace no se escribe de memoria (regla 1).

Lo que sí quedó medido el 2026-09-12 contra la instancia, y es lo que hace que el segundo tiempo merezca la pena:

| ficha | título | método observado | último cambio observado |
|---|---|---|---|
| 1062 | Servicio de Quejas y Sugerencias | `API_MAX_DATE` | 2026-09-10T12:41:34Z |
| 1420 | Licencias urbanísticas de locales | `API_MAX_DATE` | 2026-09-10T07:43:58Z |
| 147 | Contratación Pública | `API_COUNT` | — (el método no mide fecha) |
| 1400 | Ayudas y Subvenciones | `API_COUNT` | — |
| 3962 | Distritos Municipales | `WFS_HITS` | — |

O sea: **las dos fuentes que sí tienen eje territorial son justamente las dos que se observan con fecha**, así
que el enlace daría una capa de credibilidad real y no un adorno. Y a la vez el enlace **no es uno a uno**: el
presupuesto tiene **una ficha por ejercicio** (`Presupuesto Municipal 2010`, `2011`, …) frente a una sola fuente
ingerida. Por eso el segundo tiempo necesita su propio trabajo —establecer y probar la correspondencia— y no un
diccionario escrito a mano dentro de esta ADR.

## Consecuencias

- El frontend de la fase 4 tiene ya el contrato que necesita para su primera pantalla, y es un contrato que
  **no le deja precocinar**: para pintar un mapa tiene que pedir medida, ventana y denominador, y recibe con
  ellos la cobertura que obliga a matizar lo que pinta.
- Las decisiones editoriales que `SPEC.md` §1.1 y la regla 6 dejan abiertas —valores por defecto, clasificación
  de los mapas, paletas— siguen abiertas y **son de la pantalla**, no de esta ADR. Lo que esta ADR garantiza es
  que ninguna de ellas puede esconder la cobertura ni el denominador, porque vienen en la misma respuesta.
- `citizen` y `urban` ganan superficie pública. Es la primera vez que un módulo de dominio la tiene (hasta hoy
  solo `geo`, que es shared kernel, e `ingestion`), y crea una obligación: lo que entra ahí es contrato, y
  ampliarlo es una decisión, no un `public` más.
- La matriz nace con tres columnas. Cada fuente territorial nueva —`licencia-obra`, `via-publica`,
  `locales-vacios` son las candidatas de `urban`— añade medidas sin tocar la superficie.
- **No se cierra** la pregunta de si el eje observado se engancha al cruce: queda con la evidencia recogida y el
  trabajo acotado (§10).

## Alternativas descartadas

**Un lenguaje de consulta genérico** (`?dimension=district&measure=citizen.requests&groupBy=&filter=`). Es la
lectura literal de «primitivas componibles» y es la trampa. Para acotarlo hay que publicar qué campos admite
cada filtro, que es publicar el modelo interno de cada módulo; y en cuanto se admite un `groupBy` libre, el
backend es un motor OLAP que tiene que defenderse de consultas caras. La libertad que hacía falta era la de
**combinar medidas y ventana**, no la de agregar por cualquier campo, y esa cabe en una lista blanca.

**Vistas precocinadas por tema** (`/territory/quejas-vs-locales`). Rápido de pintar y expresamente prohibido por
`SPEC.md` §3: cada vista es una pregunta ya elegida, y elegir la pregunta es la voz del producto que la regla 6
limita.

**Publicar el cociente entre medidas** como columna derivada. Descartado en §4. La capacidad de análisis no se
pierde: las dos columnas van en la misma respuesta.

**Cachear la matriz** (o materializarla en una tabla de `territory`). Habría dado latencia constante a costa de
darle estado propio a un módulo que `SPEC.md` §4.8 define sin estado, y de tener que decidir cuándo invalidarlo
—que con seis fuentes de cadencias distintas no es una decisión pequeña—. Si alguna vez hace falta, es una ADR
suya y se mide antes.

**Rellenar la columna de gasto repartiendo por población.** Sería inventar una dimensión territorial que la
fuente no tiene, que es exactamente lo que ADR-011 §2 prohíbe para las direcciones. Un reparto por padrón no es
un dato: es una hipótesis con aspecto de mapa.
