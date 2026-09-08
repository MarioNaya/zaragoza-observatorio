# ADR-011 — La junta se resuelve por geometría propia, no por la API municipal

- **Fecha**: 2026-09-08
- **Estado**: aceptada
- **Afecta a**: `SPEC.md` §2, §3 (fase 2), §4.6 (`geo`, `citizen`), §4.7, §9; `CLAUDE.md` regla 29; `docs/ESTADO.md` §4
- **Se apoya en**: `docs/spikes/S2.1-resolucion-territorial.md` (peticiones reales del 2026-09-08), `docs/spikes/S0.4-geo.md`, `docs/spikes/S0.3-open311.md`, `docs/spikes/S0.6-inventario.md`

## Contexto

S0.4 y S0.6 dejaron escrito que «el ayuntamiento resuelve dirección → junta a través del recurso `portal`», y SPEC.md §2 y §3 lo daban por bueno. Nunca se comprobó. Como la asignación territorial es la llave de todo el eje del producto (SPEC.md §1), el primer paso de la fase 2 era verificarlo (S2.1). Lo medido:

- **`portalero/v2/list` es un buscador de direcciones, no un resolutor.** Sin filtro no devuelve nada; `totalCount` es siempre `-1`; la `junta` llega **sin `id`**, solo con un título corto que en `CASCO HISTÓRICO` incluye un carácter no imprimible (U+0093); el filtro FIQL `q=junta.id==6` devuelve portales de otra junta y `q=junta.id==99` devuelve «AVENIDA NAVARRA, 99»; `junta.title=DELICIAS` no devuelve nada. Una calle inexistente con un número plausible devuelve **otra calle** con ese número.
- Sobre 19 direcciones reales con junta oficial conocida, la búsqueda **acierta la junta en 17 (89,5 %) y falla en 2, sin que la respuesta permita distinguir un caso del otro**.
- **`point` + `distance` no filtra.** Dos puntos separados 12 km devuelven el mismo registro con radio de 50 m; `point` sin `distance` responde 400 (`"mensaje":"500 "`), y `registro-licencia/portal` con `point` responde 400 filtrando un `SQLGrammarException` de Hibernate. No hay consulta espacial en la API.
- **La resolución geométrica sí funciona.** Punto-en-polígono sobre los 29 polígonos de `distrito.json?srsname=wgs84`, contrastado con la junta que el ayuntamiento asigna en `locales-vacios.portal.junta`: **1.304 de 1.308 coinciden (99,69 %)**, 0 puntos fuera de las 29 juntas y 0 en más de una. Las 4 discrepancias están en un mismo tramo del borde Delicias/La Almozara. Segunda opinión con `edificio-historico`: 491 de 496 (99,0 %).
- **La correspondencia de numeraciones la publica la propia API**: cada año de `distrito/{id}.indicadores` trae `iddatosab` (= `distrito.id` en las 29) e `idpadron` (otra numeración, estable, la de SOCIO24).
- **Los nombres no casan siempre**: el callejero y las quejas usan `DISTRITO SUR` (oficialmente «Junta Municipal Sur») y `SAN JUAN DE MOZARRIFAR` («Junta Vecinal San Juan Mozarrifar»).
- **Los 29 polígonos no son una partición**: 114 de 39.878 puntos de una rejilla (0,29 %) caen en dos juntas, siempre con Juslibol (Alfocea, Monzalbarba, Actur).
- **La cobertura territorial de cada fuente es muy desigual**: 99–100 % con punto en `licencia-obra`, `registro-licencia` y `via-publica`; **49 %** en quejas y en locales vacíos; y `asociacion` no tiene geometría pero declara junta en el 99,6 %.

## Decisión

1. **La junta se resuelve en casa, con geometría, siempre.** El puerto de `geo` es `locate(Point) -> Optional<DistrictId>`, implementado con `ST_Contains` en PostGIS sobre los 29 polígonos en EPSG:4326 cargados de `distrito.json?srsname=wgs84`. Ningún módulo pregunta a la API municipal por la junta de un punto o de una dirección.
2. **No se geocodifica por dirección.** `portalero/v2` no se usa como resolutor: acierta el 89,5 % y no distingue el acierto del fallo, así que produciría juntas equivocadas en silencio, que es la peor forma de equivocarse en un producto cuyo argumento es la honestidad sobre el dato (SPEC.md §1). Un registro sin punto queda **sin asignar**, no asignado a ojo.
3. **Dos campos, no uno.** Toda fuente territorial guarda `districtId` (resuelto por geometría, nulo si no hay punto o el punto cae fuera) **y** `districtDeclared` (el texto o el id que trae el origen, tal cual, nulo si no lo trae). El declarado nunca sustituye al resuelto: es dato de contraste y, en fuentes sin geometría como `asociacion`, la única vía. La discrepancia entre ambos es información publicable sobre la calidad del dato, no un error que se corrige.
4. **`District` lleva las dos numeraciones**: `id` (= `distrito.id` = `iddatosab`) y `padronId` (= `idpadron`), poblados desde `distrito/{id}.indicadores`, que es donde la API las publica juntas. Sin `padronId` no se cruza con SOCIO24 ni con los CSV de indicadores por junta.
5. **Los nombres se casan por clave normalizada** (mayúsculas, sin tildes, sin signos, sin el prefijo «Junta Municipal/Vecinal» ni artículo inicial) **más una tabla explícita de sinónimos** en `geo` para las variantes conocidas (`DISTRITO SUR`, `SAN JUAN DE MOZARRIFAR`). La tabla se documenta y crece con evidencia: no se añade un sinónimo sin haberlo visto en una respuesta real.
6. **La ambigüedad se registra, no se resuelve en silencio.** Donde los polígonos se solapan, la consulta devuelve la junta de menor `id` por orden determinista y marca el registro como ambiguo. Un punto fuera de los 29 polígonos es `outside`, distinto de «sin punto»: son tres estados (`resuelto`, `fuera`, `sin punto`), no dos.
7. **Toda agregación territorial publica el denominador y el número de registros sin asignar** (regla 7). Con la mitad de las quejas sin punto, un mapa que no lo diga es una mentira por omisión.

## Consecuencias

- `geo` es un shared kernel con tablas propias (`geo_district`, `geo_population_record`) y una migración Flyway con columna `geometry(Polygon, 4326)` e índice GiST; PostGIS ya está en `V001`.
- La ingesta de `geo` son dos fuentes: el listado de juntas con geometría (una petición) y los indicadores por junta (29 peticiones), ambas con `IngestionJob` propio en el módulo dueño (ADR-004).
- La serie de padrón tiene **2020, 2021, 2022 y 2024**: falta 2023. Las normalizaciones declaran el año usado y la serie no se presenta como continua.
- `citizen` (y cualquier fuente territorial futura) depende de `geo` para `districtId`, y guarda además el declarado. `geo` no depende de nadie.
- La resolución es un `JOIN` espacial en base de datos, no una llamada por registro: ingerir 42.321 locales no son 42.321 peticiones a nadie.
- Hallazgos para comunicar al ayuntamiento (`docs/ESTADO.md` §6): el `point`/`distance` que no filtra, el 400 con el error de Hibernate en `registro-licencia/portal`, el carácter no imprimible en `CASCO HISTÓRICO`, el filtro FIQL por `junta.id` que no filtra, `DISTRITO SUR` frente al nombre oficial y el solape de los polígonos en el entorno de Juslibol.

## Alternativas descartadas

- **Resolver por la API con `portalero/v2`**: 89,5 % de acierto sin forma de detectar el 11,5 % restante, sin id de junta, sin recuento y con títulos que no casan. Además serían miles de peticiones a la API municipal por ingesta.
- **Geocodificar las direcciones que no traen punto** (la mitad de las quejas) para no perderlas: es exactamente lo que la regla 6 prohíbe, una conclusión inventada por el producto. Se pierde cobertura a cambio de no publicar territorio falso.
- **Usar el `district` textual de las quejas como junta**: solo lo trae el 38 % y discrepa del punto en el 8,8 % de los casos comparables; describe de dónde dice el ciudadano que es la incidencia, no dónde está.
- **Usar `idpadron` como identificador de `District`**: es la numeración de los datasets de población, no la de la API que sirve las geometrías y los equipamientos; se guarda como segunda clave, no como principal.
- **Secciones censales (491) como unidad principal**: más finas, pero ninguna de las fuentes de la fase 2 trae `CUSEC`, y con la mitad de las quejas sin punto la desagregación fina no se sostiene. Quedan como unidad opcional para normalizar (S0.4).
- **Corregir el solape recortando los polígonos**: sería editar el dato oficial. Se registra la ambigüedad y se comunica al ayuntamiento.
