# ADR-021 — El frontend es una aplicación de datos, y se diseña mirándola

- **Fecha**: 2026-09-12
- **Estado**: aceptada
- **Afecta a**: ADR-020 (mantiene sus decisiones editoriales, sustituye su dirección visual), `SPEC.md` §1
- **Funda**: el sistema visual de `frontend/` y el método de trabajo para tocarlo

## Contexto

ADR-020 acertó en las decisiones editoriales —qué se enseña, qué no se esconde, qué no se ajusta— y **falló en
todo lo demás**. Su dirección visual era «broadsheet de datos»: tinta sobre papel, filetes en vez de tarjetas,
serif en los titulares. El resultado se leía como un documento impreso, y el producto no es un documento.

Dos rechazos seguidos del usuario, con sus palabras: «estéticamente es fea y con un ux malo y sin criterio», y
después «la estética y UX sigue siendo muy mala». Al pedirle que concretara, marcó **los cuatro problemas a la
vez**: parece un documento y no una aplicación, va denso y apretado, es soso y sin color, y la navegación y los
controles no se entienden.

**La causa raíz no era falta de criterio.** Ya se habían cargado dos guías de diseño antes de escribir la
primera línea. La causa era que **el frontend se diseñó a ciegas**: la extensión de navegador no conectaba en
esta máquina, así que ninguna de las dos versiones se vio nunca antes de entregarla. Un criterio que no se puede
contrastar con lo que aparece en pantalla no es criterio, es suposición.

Y había además dos defectos objetivos que solo se ven mirando, y que llevaban dos versiones sin detectarse:

| defecto | medida |
|---|---|
| La página desbordaba en horizontal en móvil | **602 px de ancho en una pantalla de 375** |
| El eje del gráfico subía demasiado | máximo 1,09 mM€ → techo 2 mM€: las cuatro líneas aplastadas y superpuestas |
| Las etiquetas del mapa se amontonaban | ocho juntas pequeñas en el centro, con los nombres unos sobre otros |
| Una clave nula rompía la pantalla | `TypeError` al ordenar el grupo «sin asignar», que la API devuelve con `key: null` |

## Decisión

### 1. No se toca la estética sin ver la pantalla

Es la decisión que sostiene a las demás. Antes de cambiar nada visual se **captura la pantalla** con Playwright,
se mira, y se itera sobre lo que aparece. Ninguna entrega visual sale sin haber sido vista.

El barrido de comprobación es parte del trabajo, no un extra: **todas las rutas × varios anchos × los dos
modos**, comprobando en cada combinación que `scrollWidth === clientWidth` y que la consola queda limpia. Eso es
lo que habría detectado el desbordamiento de móvil en la primera versión.

### 2. La forma es de aplicación: barra lateral, tarjetas, superficies

- **Barra lateral fija** en escritorio con las siete secciones **agrupadas por familia** (Dinero, Ciudad,
  Calidad del dato), cada una con su icono y su color. En móvil se convierte en cajón con fondo oscurecedor, y
  se cierra sola al navegar.
- **Tarjetas con superficie propia** sobre un fondo tintado, con borde y radio. Se acabaron los filetes como
  única separación.
- **Escala de espaciado única** (`--s1`…`--s7`) para que el aire sea coherente y no improvisado.

### 3. El modo oscuro es el primario, no una inversión

El usuario tiene el sistema en oscuro y **es lo que ha estado viendo todo el tiempo**; las dos primeras
versiones se revisaron en claro, que es lo que casi nadie miraba. Así que las variables base del sistema son las
oscuras y el claro es el bloque `@media`, no al revés.

Cada color se mide **contra su propia superficie**, no se estima: tinta principal 16,2:1, secundaria 7,6:1,
terciaria 5,6:1, interactivo 7,1:1 sobre el fondo oscuro; y sus equivalentes propios en claro.

### 4. Color con presencia, y cada uno con un oficio

- **Interactivo** (`--primary`): navegación activa, botones, enlaces, foco. Se usa con generosidad, que es lo
  que faltaba.
- **Familia**: cada grupo de secciones tiene su color, repetido en la barra, en la cabecera de su página y en su
  tarjeta de la portada. Da orientación sin obligar a leer.
- **Categórica** (`--cat-*`): identidad de serie en gráficos. Validada para daltonismo con el script de la guía.
- **Secuencial** (`--seq-*`): magnitud. Es la rampa del mapa, un solo tono con luminosidad monótona.
- **Ordenada** (`--ord-*`): **nueva en esta ADR**. Los cuatro importes del ciclo presupuestario son un orden
  (crédito ≥ comprometido ≥ obligación ≥ pago), no cuatro identidades. Con cuatro tonos categóricos la
  comprobación de **todos los pares** sacó que azul y ciruela caen a **ΔE 4,6 en deuteranopía**: indistinguibles.
  La rampa ordenada resuelve las dos cosas —es lo semánticamente correcto y es perceptualmente segura— y sus
  cuatro pasos están **todos por encima de 3:1** contra su superficie.
- **Estado** (`--good`, `--warn`, `--bad`): reservado. Nunca es «la serie 4».

### 5. Con cuatro series o menos, etiqueta directa al final de la línea

La identidad no puede depender solo del color, y menos con una rampa de un solo tono. Cada línea lleva su nombre
al final, **con separación automática** cuando las series convergen: en 2026 la obligación neta y el pago neto
acaban casi en el mismo punto y sus rótulos se pisaban.

### 6. Nada desplaza la página en horizontal

Lo ancho —tablas de 29 filas por cuatro columnas, gráficos— desplaza **dentro de su propia caja**. El `body`
lleva `overflow-x: hidden` como red de seguridad, pero la regla real es que cada tabla tenga su contenedor.
Era exactamente lo que le faltaba a la matriz del cruce.

### 7. Lo que ADR-020 decidió sigue en pie

El rediseño **no toca ninguna decisión editorial**: al entrar salen las tres medidas, el mapa entra por la mejor
cubierta y lo dice, la clasificación se nombra con sus cortes, la paleta del mapa es secuencial, la cobertura va
en la leyenda sin la cual el mapa no se dibuja, los `caveats` se pintan todos y tal como llegan, y no se divide
una medida por otra. Lo que cambia es cómo se ve, no qué se dice.

Una sí se corrige, y era un defecto de fondo: **la ventana temporal ahora se escribe en la pantalla**. Sin ella
las cifras se leían como del año del padrón, que es exactamente lo que le pasó al usuario.

## Consecuencias

- **La serif se cae.** Era lo que más empujaba hacia el aspecto de documento. Quedan dos familias: Public Sans,
  diseñada para administración pública, e IBM Plex Mono para las cifras. Las dos siguen empaquetadas, sin pedir
  nada a terceros (ADR-020 §13).
- **Playwright pasa a ser parte del trabajo de frontend**, no una herramienta de depuración ocasional.
- **Los tipos de la API dejan de mentir**: los grupos de agregación declaran `key: string | null`, porque el
  nulo es un grupo real. Declararlo no nulo escondió un fallo hasta que reventó en pantalla.
- **El coste de comprobación sube**: cada cambio visual pide un barrido de rutas × anchos × modos. Tarda un par
  de minutos y ha encontrado cosas en cada pasada.

## Alternativas descartadas

- **Mantener la dirección editorial y solo darle color.** Era pintar por encima del problema: lo que fallaba era
  la forma, no la saturación.
- **Cuatro tonos categóricos para el ciclo presupuestario.** Descartado por el validador, no por gusto: ΔE 4,6
  en deuteranopía entre dos de ellos.
- **Esconder los `caveats` tras un desplegable** para ganar aire. Es justo lo que ADR-020 §10 prohíbe. Se
  rediseñaron como rejilla de fichas cortas: se lee mejor y siguen todos a la vista.
- **Una librería de componentes.** Habría resuelto el aspecto de aplicación a cambio de una dependencia grande y
  de un aspecto genérico, que es de lo que huye `SPEC.md` §1.
