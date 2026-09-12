# ADR-020 — La primera pantalla: valores por defecto nombrados, y ninguna decisión editorial escondida

- **Fecha**: 2026-09-12
- **Estado**: aceptada
- **Afecta a**: `SPEC.md` §1 (neutralidad), §3 (fase 4), §4.9 (reparto backend/frontend), §9 (cierra «frontend en el mismo repositorio o separado»)
- **Se apoya en**: ADR-019 (la superficie de cruce), ADR-015 (columnas, no ajustes), ADR-011 (sin punto no hay junta), ADR-009 (el coste se paga en RAM)
- **Funda**: el proyecto `frontend/`

## Contexto

El backend de la fase 4 quedó cerrado ayer y `SPEC.md` §3 lo dice sin rodeos: **lo único que le queda a la fase
es la pantalla**. Hasta hoy el producto solo se puede leer con `curl`, y seis módulos de API no son un
observatorio.

Lo difícil ya no es el contrato. ADR-019 lo fijó y además lo fijó *antes*, que era la condición que `SPEC.md` §3
ponía: catálogo cerrado de medidas, ventana por medida, ninguna aritmética entre columnas y la cobertura pegada
a cada una. Lo que queda abierto es de otra naturaleza, y `SPEC.md` §1 lo nombra con precisión incómoda:

> «Una interfaz de análisis nunca es del todo neutra, y las decisiones editoriales se disfrazan de neutralidad:
> qué capa aparece al entrar, qué clasificación usa un mapa (cuantiles, intervalos iguales, cortes naturales: los
> mismos datos sugieren cosas distintas), si la paleta es secuencial ("más y menos") o divergente ("bien y mal",
> que obliga a fijar un punto medio que es una opinión), y qué denominadores se ofrecen. […] Ninguna de esas
> cosas pone una etiqueta, pero todas cambian a qué etiqueta llega el usuario.»

Y añade la salida, que es la que esta ADR aplica: **no renunciar a los valores por defecto, que harían la
herramienta inusable, sino que sean visibles, nombrados y cambiables de un clic.**

Hay un hecho más que gobierna la pantalla entera y que ninguna decisión de diseño puede tapar: **las dos fuentes
territoriales tienen coberturas que se diferencian en un factor de tres**. Medido en producción:

| columna | asignadas | total | cobertura |
|---|---|---|---|
| `citizen.requests` | 26.105 | 89.515 | **29,2 %** |
| `urban.premises` | 37.829 | 42.344 | 89,4 % |
| `urban.licences` | 63.093 | 69.633 | 90,6 % |

Un mapa de quejas por junta es, literalmente, un mapa de **tres de cada diez** quejas. Pintarlo sin decirlo sería
la peor mentira que este producto puede contar, porque es la más convincente: un mapa coloreado parece completo.

## Decisión

### 1. El frontend vive en `frontend/` de este repositorio

Cierra la duda que `SPEC.md` §9 arrastra desde la fase 0. Un solo repositorio, un solo historial y, sobre todo,
**el contrato y su consumidor se mueven juntos**: cuando ADR-019 añada una medida al catálogo cerrado, la
columna y la pantalla que la pinta entran en el mismo commit y se rompen en el mismo build si no casan.

`docs/ESTADO.md` sigue siendo uno. Partirlo en dos era el coste real de separar los repositorios, y este
proyecto depende de que el traspaso entre sesiones esté en un sitio.

### 2. Se despliega aparte, como sitio estático, y por eso la API se abre

El frontend se publica en el alojamiento propio (Hostinger) y **no** se sirve desde Spring Boot. Consecuencias,
todas asumidas a sabiendas:

- La API se consume **desde otro dominio**, así que hay CORS: abierto en `/api/v1/**` y `/v3/api-docs`, solo
  `GET`/`HEAD`/`OPTIONS`, sin credenciales, estrechable con `zaragoza.web.cors.allowed-origins`. El comodín por
  defecto no relaja nada —toda la API es GET público sin cookies— y lo que lo hace inofensivo es
  `allowCredentials: false`, que es lo que impide que el navegador adjunte sesión alguna.
- **No se toca el coste de Railway** (ADR-009): ni un servicio nuevo, ni un megabyte residente más. La factura
  del observatorio son los ~474 MB de la aplicación y nada por petición; el estático no pasa por ahí.
- El despliegue del frontend **no reinicia la aplicación**, que es lo que hoy impide medir la línea base de
  memoria en reposo (`docs/ESTADO.md` §6). Publicar pantalla deja de costar una lectura de métricas.
- La URL de la API es **configuración de compilación**, no código: `src/environments/`, generado con
  `ng generate environments`.

Un sitio estático necesita además que las rutas profundas devuelvan `index.html`; va en un `.htaccess` que se
publica con el build, no en instrucciones sueltas.

### 3. Angular 21, sin zone.js y sin librería de mapas

Lo que produjo `ng new` (regla 15, nada escrito de memoria): Angular **21.2**, TypeScript 5.9, componentes
standalone, **zoneless**, vitest. No es la última línea: la CLI 22 exige Node `^22.22.3 || ^24.15.0` y esta
máquina tiene 24.13.0. Subir a 22 es subir Node antes, y eso es un cambio de máquina, no del proyecto.

**Dependencias de tiempo de ejecución: ninguna más que Angular.** Ni librería de mapas, ni de gráficos, ni de
componentes. La razón está en el punto 4.

### 4. El mapa son los 29 polígonos y **no hay teselas de fondo**

El mapa se dibuja en SVG con la geometría oficial que publica `GET /api/v1/geo/boundaries`, y **sin ningún mapa
base**. No es minimalismo: un fondo de teselas mandaría la dirección IP de cada visitante a un tercero cada vez
que mueve el mapa. Un proyecto que ha dedicado seis ADR a no republicar el nombre de un vecino no puede abrir el
producto enviando a sus lectores a un servidor ajeno sin decirlo.

Lo que además se gana: cero dependencias de mapa, un bundle pequeño y ningún servicio externo del que dependa
que la pantalla se vea.

La geometría se publica **sin simplificar** —es el polígono oficial, 16.462 vértices y 373 KB— y la proyección
corrige la longitud por `cos(latitud)`, sin lo cual el término sale un **34 % más ancho** de lo que es. Medido
sobre los 29 polígonos reales, con la corrección la escala queda isótropa: 17,169 px/km en los dos ejes, para
un término de 41,0 × 53,2 km.

### 5. Al entrar no se elige ningún cruce: salen las tres columnas

La tabla abre con **las tres medidas del catálogo**, que es exactamente lo que hace la API cuando no se le pide
ninguna. El `TerritoryController` ya lo dice en su javadoc y aquí se hereda tal cual: *sin `measures` salen todas
las del catálogo, que no es elegir un cruce sino no elegir ninguno.*

El **mapa sí obliga a elegir**, porque solo puede pintar una columna a la vez. Así que la elección se hace con
una regla, no a dedo: **el mapa entra por la medida mejor cubierta**, y lo dice en la leyenda. Hoy eso es
`urban.licences` (90,6 %). Entrar por `citizen.requests` sería abrir el producto con su columna más frágil; y
entrar por una elegida a mano sería la voz del producto (regla 6). La regla es visible y el selector está al
lado.

### 6. La clasificación del mapa se nombra, se enseña y se cambia de un clic

Los tres métodos que `SPEC.md` §1 pone sobre la mesa, y los tres disponibles:

- **Cuantiles** (por defecto): cada clase con el mismo número de juntas. Enseña el orden.
- **Intervalos iguales**: cada clase del mismo ancho. Enseña la magnitud.
- **Cortes naturales** (Jenks): cortes donde el dato se agrupa solo.

Cuantiles por defecto porque con 29 unidades garantiza que ninguna clase queda vacía. Y porque es el que menos
depende de un valor extremo, no porque sea «el neutro»: **no lo hay**, y por eso el nombre del método y **los
cortes reales** van siempre escritos en la leyenda. Quien quiera ver cómo cambia el mapa con otro método tiene
el selector al lado; que cambie es el punto, no un defecto.

### 7. Solo paletas secuenciales. No hay divergente

Ninguna de las tres medidas tiene un punto medio que el dato defina. Una paleta divergente obligaría a fijarlo
—¿la media?, ¿la mediana?, ¿el valor de la ciudad?— y cada una de esas respuestas convierte el mapa en «juntas
por encima» y «juntas por debajo», que es una etiqueta interpretativa con forma de color (regla 6).

Así que la paleta es **secuencial, de un solo tono**, legible en claro y en oscuro, y con una rampa que funciona
con daltonismo. Que más oscuro sea más no es una opinión: es la escala.

### 8. El denominador es el de la API, y las dos cifras se ven a la vez

Se hereda el de ADR-019 (`population` por defecto, `none` de un clic), y la tabla enseña **siempre las dos
cifras**: el recuento absoluto y la tasa por mil habitantes. Una tasa sin su numerador esconde que una junta con
tres quejas y otra con trescientas pueden acabar del mismo color.

Los años que el padrón no tiene —todos menos 2020, 2021, 2022 y 2024— salen **sin denominador**, y eso se ve:
las filas aparecen con el hueco, no con el año más cercano. Es la misma decisión que ADR-015 tomó en la API,
sostenida en la pantalla, que es donde se rompería si alguien quisiera «que no quede feo».

### 9. La cobertura va en la leyenda, y el mapa no se dibuja sin ella

Es la decisión que `SPEC.md` §3 dejaba por tomar y merece su razonamiento entero.

**La cobertura es una propiedad de la columna, no del polígono.** Dentro de una junta vale siempre 1 por
construcción: sin punto no hay junta, así que todo lo que entra en una fila estaba situado (ADR-015, ADR-019
§6). Eso descarta de raíz la solución que primero apetece —tramar o aclarar cada polígono según su cobertura—,
porque exigiría una cifra por junta que **no existe**; fabricarla sería inventar dato.

Así que va donde sí es cierta: **pegada a la medida que se está pintando, en la leyenda, siempre visible**,
escrita con su número y en palabras («de las 89.515 quejas del periodo se pudieron situar 26.105: el 29,2 %»).
No en un icono de ayuda, no en un desplegable, no en un pie de página. Y el componente que pinta el mapa
**recibe la cobertura como dato obligatorio**: no hay forma de dibujar el mapa sin ella, porque no hay una firma
que lo permita.

### 10. Los `caveats` se pintan tal como llegan: ni se inventa uno ni se quita uno

La API devuelve hasta diez advertencias por respuesta, escritas para que las lea una persona. La pantalla las
**muestra literalmente**, y no tiene ninguna suya. Es lo que hace que la regla 6 sobreviva al navegador: la voz
del producto ya está escrita y revisada en el backend, y el frontend no la edita.

### 11. La pantalla tampoco divide una medida por otra

ADR-019 lo prohíbe en la API y aquí podría colarse de vuelta con dos líneas de JavaScript: «locales por queja»
está a un `/` de distancia. No se hace. Las columnas van completas y divide quien lee, que era el punto entero
de ADR-019 §7; hacerlo en el navegador sería la misma conclusión con otro traje.

### 12. Ordena el backend, siempre

La cabecera de columna **vuelve a pedir** a la API con `sort=<medida>,desc`. No se ordena el array en memoria,
ni siquiera teniendo las 29 filas delante. Es la regla 8 y `SPEC.md` §4.9, y el día que el eje territorial tenga
491 secciones censales en vez de 29 juntas, la pantalla ya estará del lado correcto.

### 13. Ni analítica, ni fuentes remotas, ni CDN

Cero peticiones a terceros desde el navegador del visitante. Tipografías del sistema, sin Google Fonts, sin
etiqueta de medición. Un observatorio de transparencia que perfilase a quien lo consulta sería una contradicción
que además no hace falta explicar dos veces.

## Consecuencias

- **La fase 4 queda completa** cuando esta pantalla esté publicada: cruce en la API (ADR-019) y cruce a la
  vista.
- **`geo` publica geometría por primera vez.** `GET /api/v1/geo/boundaries` es superficie nueva y pública, y
  como tal contrato: sale en `/v3/api-docs` y cualquier reutilizador puede pintar las juntas sin volver a la API
  municipal.
- **La API pasa a ser legible desde el navegador por cualquiera**, no solo por este frontend. Es un efecto
  buscado: `SPEC.md` §6 dice que la API es un producto para otros reutilizadores, y hasta hoy no lo era desde
  una página web.
- **El coste no se mueve** (ADR-009). Y el despliegue de pantalla deja de reiniciar la aplicación, lo que
  desbloquea la medida de memoria en reposo que lleva tres sesiones pendiente.
- **Hay que publicar a mano** mientras no haya automatización: el build sale de `frontend/` y se sube al
  alojamiento. Queda anotado como trabajo, no como decisión.
- **El mapa sin fondo se ve raro la primera vez.** Es el precio de no mandar a nadie a un tercero, y se compensa
  con las etiquetas de junta y la ficha al hacer clic.

## Alternativas descartadas

- **Servir el frontend desde Spring Boot** (`src/main/resources/static`). Es más barato de operar —un despliegue,
  un dominio, cero CORS— y fue la recomendación inicial. Se descarta porque el alojamiento ya existe y porque
  ata el ciclo de vida de la pantalla al de la API: cada corrección de un color reiniciaría la aplicación y su
  planificador de ingesta.
- **Repositorio separado**. Duplicaría reglas de trabajo, documentación y traspaso de sesión, que es justo lo
  que este proyecto no puede permitirse.
- **Un mapa con teselas** (Leaflet, MapLibre y un proveedor de fondo). Mejor aspecto y contexto urbano a cambio
  de mandar la IP de cada visitante a un tercero y de una dependencia de tiempo de ejecución. No compensa: el
  dato del producto son las juntas, no las calles.
- **Pintar la cobertura sobre cada polígono** (trama, opacidad). Exigiría una cifra por junta que no existe
  (§9).
- **Una paleta divergente** con el valor medio de la ciudad en el centro. Es la forma más elegante de publicar
  un juicio sin escribirlo (§7).
- **Ajustar las cifras por cobertura** para «hacerlas comparables». Ya descartado en ADR-015 y en ADR-019, y se
  vuelve a descartar aquí porque es en la pantalla donde la tentación aparece: supondría que lo no geolocalizado
  se reparte como lo geolocalizado, y eso no está comprobado en ninguna de las dos fuentes.
- **Vistas por tema** («movilidad», «comercio»). Cada una es una pregunta ya elegida; es lo mismo que ADR-019
  descartó en la API y no entra por la puerta de atrás.
