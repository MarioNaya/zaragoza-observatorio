# ADR-016 — `urban`: la actividad urbana privada como contexto propio, y sin una sola columna de texto libre

- **Fecha**: 2026-09-09
- **Estado**: aceptada
- **Afecta a**: `SPEC.md` §2, §4.3, §4.6, §4.7, §9; `CLAUDE.md` reglas 9, 20 y 22
- **Se apoya en**: `docs/spikes/S2.4-registro-licencia.md`, `docs/spikes/S2.1-resolucion-territorial.md`, ADR-003 §6, ADR-011, ADR-012

## Contexto

ADR-003 §6 dejó `licencia-obra`, `registro-licencia`, `locales-vacios` y `via-publica` **fuera de `spending`** —miden actividad privada, no inversión pública— y anotó que serían candidatos a un contexto posterior «que requerirá su propia ADR». Esta es esa ADR, y llega porque la fase 2 cerró con **una sola fuente territorial** y un eje que aguanta mejor con dos.

S2.4 midió la fuente entera. Lo que cambia el diseño:

- **89,4 % de los locales tienen punto**, no el 99,0 % que S2.1 midió sobre el primer lote. Sigue siendo tres veces la cobertura de las quejas.
- **La fuente no declara junta** por ninguna vía: ni el local ni su portal.
- **El texto libre no se puede no descargar.** `fl` vacía los objetos anidados (una licencia proyectada llega sin año, sin expediente y sin tipo) y `removeproperties` se acepta sin aplicarse. La jugada de ADR-012 —no pedirlo— no está disponible.
- Ese texto contiene **15 DNI con letra de control válida** en los comentarios de las licencias y **2 dentro del propio nombre de la actividad**.
- **`(año, expediente)` identifica la licencia** dentro de su local sin una sola colisión en 69.631; `orden` colisiona 950 veces.
- Un barrido por `lastUpdated` pierde **2.928 registros**: 22.044 comparten un mismo instante.

## Decisión

### 1. El contexto se llama `urban`

Bounded context nuevo, paquete `es.zaragoza.observatory.urban`, módulo de Modulith con `allowedDependencies = { shared, ingestion, geo }`. Es el nombre `urban-activity` que ADR-003 §6 y S0.6 dejaron apuntado, reducido a una palabra por coherencia con `catalog`, `citizen`, `geo` y `spending`.

Su lenguaje es **la actividad urbana privada que el ayuntamiento registra**: locales con licencia hoy; `licencia-obra`, `locales-vacios` y `via-publica/incidencia` caben en el mismo contexto y **no necesitan ADR nueva**, solo su spike, porque lo que esta ADR funda es el contexto, no el dataset (regla 9).

`urban` **no depende de `citizen`** ni al revés. Que las dos fuentes sean territoriales no las hace vecinas: comparten `geo`, y cruzarlas es trabajo de quien lee.

### 2. Dos entidades: el local y sus licencias

`LicensedPremises` (el local, 42.342) y `Licence` (la licencia, 69.631, media 1,64 por local). Tablas `urban_premises` y `urban_premises_licence`, con clave `(premises_id, year, file_number)` en la segunda, que es la única medida como única. `orden` se guarda como dato del origen, nunca como clave.

### 3. Ninguna columna de texto libre

No se guardan `comments` (del local ni de la licencia), ni `emplazamiento`, ni `actividad`. Cada una por su razón, y las tres razones son distintas:

- **`comments`**: contiene datos de personas físicas (15 DNI válidos). No se puede evitar descargarlo, así que la garantía es la otra mitad de ADR-012: **no hay columna donde guardarlo y el traductor no lo lee**, probado en un test.
- **`emplazamiento`**: es la dirección textual. ADR-011 §2 prohíbe geocodificar por dirección, así que no tiene uso, igual que `address_string` en ADR-012.
- **`actividad`**: es texto libre con 2 DNI dentro, y **el epígrafe IAE hace su trabajo mejor**: viene en los 42.342 registros con código y título de una taxonomía cerrada de 965 entradas. Se guarda el epígrafe y se descarta el texto.

Los fixtures se graban con `saveRedacted` sobre `comments` (regla 22).

Y hay una tercera puerta que había que cerrar: **la página cruda tampoco se guarda**. `ingestion` conserva cada respuesta en `raw_payload` catorce días para poder reprocesarla (SPEC.md §4.5), y en esta fuente esa página lleva el texto con los DNI. Guardarla sería tener el dato por la puerta de atrás, así que `IngestionJob` gana un `keepsRawPayload()` —`true` por defecto, `false` aquí— y el job lo desactiva diciendo por qué. De paso se ahorran los 40 MB por barrido completo. El precio, consciente, es no poder reprocesar una página sin volver a pedirla.

Esto **no recorta capacidad de análisis** (regla 6, segunda mitad): lo que se quita es una descripción escrita a mano, y lo que queda es la misma actividad codificada, agrupable y comparable.

### 4. Un solo job, que barre por `id asc` y filtra por fecha en `q`

El eje de recorrido es **siempre `sort=id asc`**, tanto en la carga completa (85 páginas) como en el incremental; lo que cambia es que el incremental añade `q=lastUpdated=ge=<marca de agua>`. Filtro y orden en campos distintos: la ventana es exacta porque el filtro la recorta de verdad, y la paginación es exacta porque el identificador no empata.

Esto **corrige el patrón de `citizen`**, donde filtro y orden compartían campo y por eso hizo falta repartir el trabajo entre dos jobs, con la carga del histórico prohibida en uno de ellos (S2.2 §10). Aquí basta un job y una marca de agua.

Se piden los **registros completos**, sin `fl`: son 40 MB una vez, y la proyección rompería las licencias.

### 5. Territorio: como ADR-011, y sin fingir un contraste que no existe

La junta se resuelve con `ST_Contains` sobre los polígonos de `geo` (`Geo.locateAll`, una consulta por página). Sin punto no hay junta: 4.499 locales quedan `NO_POINT` y se cuentan como tales.

ADR-011 §3 exige guardar la junta resuelta **y** la declarada. Aquí el origen **no declara ninguna**, así que **no se crea la columna**: una columna siempre nula no es un contraste, es ruido que aparenta uno. Los `caveats` de la API lo dicen con esas palabras. La regla sigue en pie para las fuentes que sí declaran.

El vocabulario de los cuatro estados (`RESOLVED`, `AMBIGUOUS`, `OUTSIDE`, `NO_POINT`) **se muda de `citizen.domain` a `geo`**, que es el módulo dueño del territorio: los tres primeros son los de `DistrictLocation` y el cuarto es el caso que no llega a preguntarse. Con una sola fuente territorial vivía bien donde estaba; con dos, la alternativa era copiarlo.

### 6. Los códigos del origen se guardan como códigos

`estado` (0, 1, 2, 3) **no tiene taxonomía publicada** y se guarda como número, sin etiqueta. Ponerle nombre —«activo», «de baja»— sería inventar la taxonomía que la fuente no publica (regla 6). Igual con `zonaSaturada`, cuyos códigos `O` y `P` usan 17 locales y no aparecen en el catálogo de 15 zonas que publica la propia API.

Y ninguna cifra del producto se llama «locales abiertos» ni «negocios activos»: el registro guarda licencias concedidas, no actividad en marcha.

### 7. La API publica unidades separadas

`/api/v1/urban/premises` cuenta **locales**; `/api/v1/urban/licences` cuenta **licencias**. Cada agregación dice qué unidad cuenta, porque un local con 12 licencias es un local y son doce licencias, y mezclarlos en un mismo «total» sería una cifra sin significado. Toda agregación territorial lleva su denominador de padrón, su cobertura de punto y sus registros sin asignar (regla 7).

## Consecuencias

- El eje territorial pasa de una fuente a dos, con **coberturas muy distintas** (89,4 % frente a 29,1 %). Comparar las dos series por junta sin mirar las dos coberturas engaña, y los `caveats` lo dicen en las dos.
- Una migración nueva (`V011`) con dos tablas. Con `ddl-auto=validate`, toda entidad necesita su migración y su `columnDefinition` (regla 21).
- El barrido completo son **40 MB y ~85 páginas**; con el retardo de cortesía de `ingestion` (`PT0.5S`), unos 45 s de peticiones. El incremental diario son 54 registros en una página.
- La memoria de producción sube algo (ADR-009): **medido el mismo día**, la línea base pasa de 416 MB con cuatro módulos a **425 MB con cinco**, con un pico de 480 MB durante el barrido completo. No se ha tocado ninguna bandera de la JVM.
- `SPEC.md` §9 pierde la duda del contexto de actividad urbanística y gana la de si el `estado` llega a poder interpretarse.
- Quedan **tres fuentes más** para este contexto, cada una con su spike: `licencia-obra` (2.042 parcelas, 100 % con punto), `via-publica/incidencia` (100 %) y `locales-vacios` (3.824, 48,8 %).

## Alternativas descartadas

- **Meterlo en `citizen`**: `citizen` es lo que la ciudadanía pide al ayuntamiento; una licencia de local es lo contrario, lo que un particular pide y el ayuntamiento concede. Compartir el eje territorial no es compartir lenguaje.
- **Meterlo en `spending`**: ya lo descartó ADR-003 §6. No hay importes y la actividad es privada.
- **Un módulo por dataset** (`premises`, `works`, `vacant`): contradice la regla 9 y las cuatro fuentes comparten lenguaje (un emplazamiento, una fecha, un expediente municipal, un punto).
- **Guardar el texto libre y filtrarlo al publicar**: deja el dato personal en la base de datos y hace depender la protección de que nadie se equivoque en la capa de arriba. ADR-012 ya eligió la ausencia estructural, y aquí se puede conseguir igual aunque la descarga no se pueda evitar.
- **Redactar el texto por patrones antes de guardarlo**: S2.2 ya midió que la redacción por expresión regular no reconoce lo que de verdad hay. Aquí, además, sería trabajo para conservar un campo que el producto no necesita.
- **Guardar `actividad` y descartar solo los dos registros con DNI**: conserva un texto libre redundante con el epígrafe IAE a cambio de una regla de borrado que alguien puede desactivar.
- **Rellenar la junta cruzando `codPortal` con el callejero**: es resolver por dirección con otro nombre, y S2.1 midió que el callejero acierta el 89,5 % **sin que se pueda distinguir el acierto del fallo**.
- **Barrer por `lastUpdated` como en `citizen`**: perdería 2.928 registros de 42.342.
- **Llamar al contexto `urban-activity`**: el paquete Java no admite el guion y `urbanactivity` no se lee. La ADR fija el nombre corto y qué significa.
