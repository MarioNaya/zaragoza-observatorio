# Auditoría de arquitectura

**Fecha**: 2026-09-17 · **Alcance**: el sistema entero, con el **frontend como parte larga** porque es la que
menos supervisión ha tenido.

Esto **no** es el informe de `docs/auditoria-frontend.md`. Ese es una auditoría de código: campos mal escritos,
nulabilidad, estados de carga, contraste, código muerto. Legítima, pero contesta «¿está bien escrito?». Aquí se
contesta otra cosa: **qué arquitectura hay, si las piezas están encapsuladas de verdad o solo repartidas en
carpetas, qué capas existen, y qué deuda estructural arrastramos**.

Todo lo que se afirma está medido o leído hoy. Cuando algo es una decisión deliberada lo digo, y cuando es deuda
también, con su coste.

---

## 1. La vista de arriba: dos artefactos y un contrato

```
  navegador                              Railway                          zaragoza.es + datos.gob.es
 ┌──────────────┐   HTTPS GET       ┌────────────────────┐   HTTPS GET    ┌────────────────────────┐
 │ sitio        │ ────────────────► │ monolito modular   │ ─────────────► │ 6 APIs municipales     │
 │ estático     │  CORS de solo     │ Spring Boot 4.1    │  ~50 URL con   │ + federación estatal   │
 │ (Angular 21) │  lectura, sin     │ 8 módulos          │  reglas medidas└────────────────────────┘
 └──────────────┘  credenciales     │ PostgreSQL+PostGIS │
                                    └────────────────────┘
```

Dos cosas importan de este dibujo, y las dos son decisiones:

1. **No comparten nada más que el contrato HTTP.** No hay sesión, ni estado servidor por usuario, ni render en
   servidor, ni módulo común compilado en los dos lados. El frontend es un cliente más de una API pública, y eso
   lo demuestra el hecho de que el frontend se sirva desde **otro dominio** (ADR-020 §2).
2. **El sentido de las dependencias no tiene ciclos en ninguna parte**: el navegador conoce la API, la API
   conoce las fuentes municipales, y nadie conoce hacia atrás. El acoplamiento con el ayuntamiento está
   confinado en un sitio por módulo (`infrastructure/zaragoza`), que es donde vive lo que la fuente hace mal.

---

## 2. Backend: monolito modular con hexagonal dentro de cada módulo

### 2.1 Lo que está implementado, no lo que se pretendía

**Dos arquitecturas superpuestas, y las dos con verificación automática**, que es lo que las convierte en
arquitectura y no en convención:

| Nivel | Estilo | Qué lo sostiene | Estado hoy |
| --- | --- | --- | --- |
| Entre módulos | **Monolito modular** (Spring Modulith) con dependencias **declaradas** | `ModularityTests` → `ApplicationModules.verify()` | verde, ejecutado hoy (2 tests, 4,5 s) |
| Dentro del módulo | **Hexagonal / puertos y adaptadores** | `HexagonalArchitectureTests` (ArchUnit, 3 reglas) | verde |

La diferencia con «tener carpetas» es que cada módulo **declara a quién puede conocer** en su
`package-info.java`, y Modulith falla el build si alguien se salta la declaración **o si toca un paquete interno
de otro módulo**. Las ocho declaraciones son reales:

```
shared      → {}                              (no conoce a nadie)
ingestion   → {shared}
geo         → {shared, ingestion}
catalog     → {shared, ingestion}
citizen     → {shared, ingestion, geo}
urban       → {shared, ingestion, geo}
spending    → {shared, ingestion}
territory   → {shared, geo, citizen, urban}   (y nadie lo conoce a él)
```

Ese grafo es acíclico y tiene una forma reconocible: **cinco papeles de módulo distintos**, no ocho módulos
iguales.

| Papel | Módulos | Qué significa |
| --- | --- | --- |
| **Kernel compartido** | `shared` (9 ficheros, 339 líneas) | tipos de valor y el evento base. No depende de nada y es deliberadamente diminuto |
| **Plataforma** | `ingestion` (30 ficheros) | el cliente HTTP con las reglas medidas de la API municipal, los runs, la resiliencia y el planificador. **No conoce ningún dominio**: recibe un `SourceDescriptor` y devuelve `RawPage` |
| **Kernel de dominio** | `geo` (39 ficheros) | las 29 juntas y la resolución punto→junta. Lo consumen las fuentes territoriales; él no consume a nadie |
| **Dominios** | `catalog`, `citizen`, `urban`, `spending` | cada uno con su fuente, su modelo y su API. **No se conocen entre sí** |
| **Composición** | `territory` (10 ficheros, 840 líneas) | pone medidas de tres módulos en la misma tabla. Sin tablas, sin caché, sin ingesta, y **sumidero del grafo** |

La inversión que hace que esto funcione está en `ingestion`: los módulos de dominio **implementan** su puerto
`IngestionJob` y la plataforma los descubre como beans. Es decir, la plataforma no conoce a sus clientes; sus
clientes se enchufan. Eso es lo que permite que `ingestion` no dependa de ningún dominio teniendo el control del
ciclo de ejecución.

### 2.2 Encapsulación: ¿de verdad o solo carpetas?

**De verdad, y es lo mejor que tiene el proyecto.** Modulith expone por defecto **solo el paquete raíz** de cada
módulo, y las superficies son diminutas y están escritas a propósito:

| Módulo | Superficie pública | Tamaño |
| --- | --- | --- |
| `geo` | `Geo` (5 métodos), `GeoPoint`, `DistrictLocation`, `Assignment`, `DistrictNames`, `DistrictSummary`, `DistrictPopulation`, `GeoSources` | 8 tipos |
| `citizen` | `Citizen` (2 métodos: `requestsByDistrict`, `ingestedAt`), `CitizenSources` | 2 tipos |
| `urban` | `Urban` (3 métodos), `UrbanSources` | 2 tipos |
| `catalog` | `CatalogSources` | 1 tipo |
| `spending` | `SpendingSources` | 1 tipo |
| `territory` | nada | 0 tipos |

Lo que **no** sale por esas puertas dice más que lo que sale: por `Citizen` no sale una queja, sale un recuento
por junta con su cobertura; por `Geo` no salen geometrías ni tablas; `catalog` y `spending` no publican nada
porque nadie necesita nada de ellos. Las 47 clases de dominio de `spending` y las tablas de todos son
**invisibles** desde fuera, y eso lo comprueba el build, no la buena voluntad.

Las tres reglas de ArchUnit cierran el interior:

- `domain` no importa Spring, JPA, Hibernate, Jackson ni Resilience4j, **ni `application` ni `infrastructure`**.
- `application` no importa `infrastructure`, JPA, Hibernate, Spring Web ni Spring Data.
- el paquete raíz de un módulo (su superficie) no importa `application` ni `infrastructure`.

Y la comprobación de cómo está cableado: en `application` hay **cero** `@Service`, `@Component` y `@Autowired`;
los casos de uso son clases planas con constructor y los beans se declaran en
`<módulo>/infrastructure/<Módulo>Configuration`. La única concesión al framework en esa capa son **18
`@Transactional`**, y hay una clase que documenta por qué *no* lo lleva (hace una petición HTTP y no quiere una
transacción abierta mientras espera). Eso no es deuda: es la frontera puesta donde se puede defender.

### 2.3 Las capas dentro de un módulo

```
<módulo>/                     ← superficie pública: interfaces y tipos de valor. Lo único que ven los demás
  domain/                     ← modelo + PUERTOS (interfaces). Sin framework. 23 interfaces de dominio en total
  application/                ← casos de uso: clases planas, un cometido cada una (RegisterX, ReadX, ComposeX)
  infrastructure/
    persistence/              ← adaptadores de salida: JPA o JDBC
    zaragoza/                 ← adaptadores de entrada: traductores de la API municipal + IngestionJob
    web/                      ← adaptadores de entrada: controlador + DTO + caveats
    events/                   ← listeners de otros módulos (@ApplicationModuleListener)
    scheduling/               ← planificadores propios del módulo (detalle OCDS, instantáneas, observación)
    <Módulo>Configuration     ← el cableado
    <Módulo>Properties        ← la configuración tipada
```

Las cinco carpetas de `infrastructure` no son decoración: separan **cuatro tipos de adaptador distintos** por
dirección y por protocolo. Un traductor de JSON municipal y un controlador REST son los dos «entrada», pero
cambian por motivos distintos —uno cuando el ayuntamiento cambia su respuesta, otro cuando cambia nuestro
contrato— y están separados.

La comunicación entre módulos tiene **dos vías, las dos explícitas**:

1. **Llamada a la superficie pública**, sincrónica: `territory` → `geo`/`citizen`/`urban`; `citizen`/`urban` →
   `geo`.
2. **Evento de dominio**, asíncrono: `DatasetIngested` (en `shared`) con tres `@ApplicationModuleListener`
   —`catalog`, `geo`, `spending`— sobre el registro JDBC de Modulith. Esto es lo que permite que la ficha de
   una junta se lea *después* de que su capa base entre, sin que el job de la capa base conozca al lector.

### 2.4 Deuda estructural del backend

Ordenada por lo que más puede doler, con lo que cuesta arreglarla.

**B1. El sobre de respuesta está duplicado seis veces y ya ha derivado.** `Source`, `ApiItem`, `ApiPage` y
`ApiList` se declaran **en cada módulo** (`CatalogDtos`, `CitizenDtos`, `GeoDtos`, `SpendingDtos`, `UrbanDtos`,
`TerritoryDtos`). Empezó como independencia de módulo —que es defendible— pero el resultado es que **la misma
idea tiene tres formas distintas en la API pública**:

| Módulo | Cómo pagina | Consecuencia en el cliente |
| --- | --- | --- |
| `citizen`, `urban`, `spending` | `total`, `page`, `size` sueltos | `ApiPage<T>` |
| `catalog` | objeto `page { number, size, totalElements, totalPages, sort }` | `CatalogPage<T>`, un tipo aparte |
| `geo` | `count` + `items` (`ApiList`) | otro |
| `territory` | sin `source` ni `ingestedAt` en la raíz | deliberado (ADR-019 §2), pero es una cuarta forma |

Eso lo paga el consumidor: el frontend necesita tres tipos de sobre, y la pantalla del catálogo fue la que se
salió del patrón en el código (llevaba estado propio hasta hoy). **Coste de arreglarlo**: un módulo `webapi`
compartido con el sobre y su paginación, o aceptar la divergencia y documentarla como contrato. Lo primero toca
seis módulos y su OpenAPI; lo segundo es gratis y honesto. **No es urgente, pero hay que elegir**, porque cada
módulo nuevo replica la decisión por inercia.

**B2. Dos estilos de persistencia sin decisión escrita.** `catalog` (4 entidades), `geo` (2) e `ingestion` (2)
usan **JPA**; `citizen`, `urban` y `spending` usan **JDBC** con SQL a mano (5 repositorios, entre 373 y 625
líneas). La correlación con el volumen es evidente —437 fichas y 29 juntas frente a 89.743 quejas, 42.346
locales y ~200.000 filas de gasto— y el SQL de agregación (`FILTER`, `percentile_cont`, upsert por lotes) es la
razón real. Pero **eso no está escrito en ninguna ADR**, así que el próximo módulo lo elegirá a cara o cruz, y
`JdbcContractingProcessRepository` (625 líneas) es ya la clase más grande del proyecto. **Coste**: una ADR corta
con el criterio (volumen y agregación → JDBC; catálogo y maestros → JPA) y, si se quiere, partir los dos
repositorios mayores por agregado.

**B3. `spending` tiene tres dominios y ninguna frontera interna.** Es el módulo mayor con diferencia —85
ficheros, 7.622 líneas— y su `domain` son **47 clases planas** donde conviven contratación (`ContractingProcess`,
`Award`, `Contract`, `Cpv`, `Tender`, `Stage`…), presupuesto (`BudgetSnapshot`, `BudgetLine`, `BudgetAmounts`,
`BudgetHeading`…) y subvenciones (`Grant`, `GrantCall`, `GrantBeneficiary`, `GrantIdentity`…). Solo las separa
el prefijo del nombre. ADR-003 decidió bien que el **contexto** es uno —el gasto público—, pero dentro hay tres
agregados que no se hablan y nada impide que empiecen a hablarse. **Coste**: subpaquetes
`domain/contracting`, `domain/budget`, `domain/grants` (renombrado mecánico, sin cambio de comportamiento) y, si
se quiere apretar, `@NamedInterface` de Modulith para exponer solo lo que toque. Es la deuda con mejor relación
daño/coste del backend.

**B4. `territory` conoce nombres de columna de otros módulos.** Su enum `Measure` publica `dateField` con los
valores `requested_at` y `created_at`, que son **columnas de las tablas de `citizen` y `urban`**. Viaja a la
respuesta como metadato honesto (ADR-019 lo quiere: decir sobre qué fecha cae la ventana), pero es conocimiento
duplicado: si `citizen` renombra su columna, `territory` sigue publicando el nombre viejo y **nada falla**.
**Coste**: que cada superficie pública declare su propio `dateField` y `territory` lo pregunte. Media hora, y
convierte un acoplamiento por convención en uno por contrato.

**B5. La voz del producto vive en `infrastructure/web`.** Los `caveats` —que son producto, no presentación: son
la mitad del valor del observatorio— están en `CitizenCaveats`, `UrbanCaveats`, `SpendingCaveats`,
`BudgetCaveats`, `GrantCaveats`, `TerritoryCaveats`, `GeoCaveats`, `Caveats`. Que sean texto los pone en el
adaptador; que sean **afirmaciones sobre el dato** los pondría en el dominio. Hoy es discutible y sin daño; se
vuelve deuda el día que un `caveat` dependa de una regla de dominio (por ejemplo, avisar solo si la cobertura
baja de un umbral), porque entonces la regla vivirá en el controlador. **Coste**: nada hoy; vigilarlo.

**B6. Controladores que calculan.** `CitizenController` (247 líneas) y `UrbanController` (224) suman `matched` y
`unassigned` y montan los grupos con su padrón. Es composición de respuesta, defendible en el adaptador, pero
son los dos ficheros web más grandes y ya tienen aritmética dentro. **Coste**: mover esas sumas al read model
cuando toque tocarlos; no antes.

**B7. Configuración como clase grande.** `SpendingProperties`, 225 líneas, con tres fuentes y sus planificadores
dentro. Es consecuencia de B3 y se arregla con B3.

Y una cosa que **no** es deuda aunque lo parezca: que el proyecto sea un **monolito modular** y no servicios.
Con un planificador, una base de datos y un despliegue de 5 $/mes (ADR-009), partirlo multiplicaría la operación
sin resolver ningún problema real. Las fronteras ya están puestas y verificadas: si alguna vez hace falta
extraer `spending`, la costura existe.

---

## 3. Frontend: cliente fino por capas, sin dominio propio

Esta es la parte que menos supervisión ha tenido, así que va con más detalle y más crudeza.

### 3.1 Qué arquitectura es, exactamente

No tiene nombre de catálogo, así que la describo por lo que hace:

> **Capas** (`core` → `ui`/`map` → `pages`) + **contenedor por ruta** + **pasarela única de datos** +
> **primitivas de estado propias**, sobre Angular 21 zoneless con componentes standalone y señales. Sin store,
> sin efectos, sin interceptores, sin resolvers ni guards, y **sin dominio propio**.

Que no haya dominio en el frontend **es correcto y es la decisión central**: las reglas del producto —qué es una
queja, qué cuenta cada unidad, qué se puede cruzar, qué no se divide— viven en el backend y viajan en la
respuesta (`unit`, `caveats`, `coverage`). El navegador solo tiene **lógica de presentación**, y la que tiene
está aislada: clasificar valores en cinco clases de color (`map/classification.ts`) y proyectar coordenadas a
SVG (`map/projection.ts`). Las dos son «pintar», y por eso están en `map/` y no en el backend.

Las capas, con lo que contiene cada una y su tamaño real:

| Capa | Ficheros | Qué es | Conoce a |
| --- | --- | --- | --- |
| `core/` | `api.ts` (186), `types.ts` (563), `state.ts` (192), `format.ts` (163) | pasarela HTTP, contrato, estado y formato | nadie |
| `ui/` | 9 componentes (tabla, filtros, dos gráficos, ranking, cifras, estado, colofón) | presentación pura, todo por `input`/`output` | `core` |
| `map/` | `choropleth`, `classification`, `projection` | el mapa y sus matemáticas | `core` |
| `pages/` | 8 contenedores + `matrix` + `district-card` | compone: pide, deriva, decide qué se enseña | `core`, `ui`, `map` |
| raíz | `app.ts/html/css`, `app.routes.ts`, `app.config.ts` | armazón, navegación, rutas perezosas | `core`, `pages` |

Y el reparto en el bundle confirma que las fronteras son **reales en tiempo de ejecución**, no solo en el
código: inicial **277 KB** (76 KB transferidos) y cada sección en su propio trozo perezoso —territorio 32 KB,
contratación 11 KB, presupuesto 10 KB, quejas 10 KB, subvenciones 9 KB, actividad 8 KB, catálogo 8 KB, portada
7 KB—. Abrir la portada no descarga el mapa.

### 3.2 Encapsulación: qué está bien

- **Una sola pieza conoce URLs.** `core/api.ts` es la pasarela; ningún componente construye una petición, y
  ahora hay un test que lo impide. Es también el **punto único de adaptación** cuando la API tiene una rareza:
  el resumen del catálogo llega sin sobre y se envuelve ahí, no en la pantalla.
- **`ui/` y `map/` no piden datos.** Reciben y pintan. Se puede montar cualquiera de los nueve componentes en un
  test con datos literales, y eso es exactamente lo que hacen `data-table.spec` y `classification.spec`.
- **Ninguna sección conoce otra sección.** Comprobado por test, y la definición de «sección» son las rutas.
- **El estado es local por pantalla.** No hay store global, así que no hay acoplamiento invisible entre
  secciones: lo que pasa en subvenciones no puede afectar a quejas. Para un producto de siete exploradores
  independientes, es la decisión correcta y ahorra una capa entera.
- **La obligatoriedad como contrato de componente**: `Choropleth` declara `measure` como `input.required`
  porque ahí viaja la cobertura, de modo que **no existe forma de compilar un mapa sin su cobertura**
  (ADR-020 §9). Eso es una regla de producto convertida en firma de tipo, y es lo más elegante que hay en el
  frontend.

### 3.3 Encapsulación: qué no está bien

**F1. `core/types.ts` es un espacio de nombres global de 563 líneas.** Ahí conviven los contratos de los ocho
contextos: `CitizenSummary`, `Premises`, `ContractingProcess`, `BudgetLine`, `Grant`, `Dataset`, `CrossTab`… Lo
importan **9 de los 11 ficheros de `pages/`**, 2 de 4 de `map/` y 1 de 9 de `ui/`. Consecuencias reales:

- la pantalla de subvenciones **puede ver** el contrato del presupuesto, y nada lo impide;
- es un punto caliente: toda sección nueva lo modifica;
- y es donde estaba escondido el fallo de las tres columnas vacías, no por casualidad: un fichero de 563 líneas
  con ocho dominios dentro no se revisa, se hojea.

El backend resolvió esto mismo con módulos y superficies; el frontend lo tiene todo en una bolsa. **Arreglo
natural**: `core/contract/citizen.ts`, `…/spending.ts`, etc., con el sobre común aparte, y la regla de capas
extendida a «una sección solo importa el contrato de su contexto» —que es una comprobación de cuatro líneas en
el test que ya existe—. Coste: una tarde, mecánico, sin cambio de comportamiento.

**F2. `map/` depende del contrato de la API.** `choropleth.ts` recibe `DistrictFeature[]`, `DistrictRow[]` y
`MeasureColumn`: los tipos **tal como los devuelve la API**. Es decir, la capa de pintado está atada a la forma
de la respuesta, y un cambio de contrato en `geo` o en `territory` entra hasta el componente de dibujo. Lo
correcto en una arquitectura de capas es que `map/` declare su propio modelo de vista (`Shape`, `Legend`) y que
la página traduzca. **Coste**: pequeño (el componente ya construye `Shape` internamente; falta subir la
traducción a la página). Es la deuda que más «arquitectura de verdad» añade por menos trabajo.

**F3. Las plantillas hablan con los objetos de estado.** Después del refactor de hoy, la plantilla escribe
`explorer.setSort($event)`, `explorer.setPage($event)`, `summary.reload()`. Funciona y es idiomático en Angular
con señales, pero significa que **la página ya no media**: cualquier plantilla puede llamar a `reload()` de
cualquier recurso, y el contrato de la pantalla es «mis objetos internos». Es un intercambio consciente —quitó
35 métodos de reenvío— y lo dejo anotado como lo que es: encapsulación cambiada por concisión.

**F4. El catálogo de secciones está declarado tres veces.** `app.routes.ts` (ruta, título, carga perezosa),
`app.ts` (barra lateral: ruta, etiqueta, icono, familia) y `home.ts` (tarjeta: ruta, copete, título, texto,
cifras). Añadir una sección son **tres listas** y nada comprueba que coincidan: `app.spec` afirma los siete
enlaces de la barra y `architecture.spec` afirma que hay al menos siete rutas, pero **nadie comprueba que la
barra y las rutas sean el mismo conjunto**. Una ruta sin entrada en la barra es invisible; un enlace a una ruta
que no existe cae en el `**` y redirige a la portada **en silencio**. **Arreglo**: un único catálogo de
secciones (ruta + etiqueta + icono + familia + copete) del que salgan las tres cosas, o un test que compare los
tres conjuntos. Coste: una hora.

**F5. La URL no es la fuente de verdad del estado.** El eje elegido, los filtros, el orden, la página, la junta
seleccionada y la clasificación del mapa viven **en instancias de componente**. Implicaciones que van más allá
de «no se puede compartir un enlace»:

- el botón de atrás del navegador no deshace nada de lo que el usuario hizo dentro de una sección;
- navegar a otra sección y volver **pierde el estado** y vuelve a pedir desde cero;
- no se puede enlazar a un hallazgo, que en una herramienta de análisis es la mitad de su utilidad;
- y no hay forma de reproducir un informe de error: «esto sale raro» no tiene URL.

Está en la lista de pendientes del producto como «rutas profundas compartibles», pero **es arquitectura**: la
decisión es si el router es el contenedor del estado de pantalla o no. Hoy no lo es. Es la deuda mayor del
frontend.

**F6. Primitivas de estado hechas a mano que la plataforma ya trae… en experimental.** `Loaded` y `Explorer`
reproducen `resource()` de `@angular/core` y `httpResource` de `@angular/common/http`: valor, estado, error,
`reload()`. Lo comprobé en la versión instalada (21.2.23) y **las dos siguen marcadas `@experimental`** (19.0 y
19.2 respectivamente). Con la regla 15 y la conservadurismo de este proyecto, hacerlas a mano fue la decisión
correcta; lo que falta es **que sea una decisión escrita con disparador de revisión**: cuando `httpResource`
salga de experimental, `core/state.ts` es 192 líneas que se borran. Anotado en ADR-022 y ahora aquí.

**F7. Cifras escritas a mano que la API ya publica.** El backend devuelve los números y el texto los repite de
memoria. Comprobado hoy contra la instancia, y conviene separar dónde está cada caso porque el daño es
distinto:

| Cifra escrita | Dónde | Valor real hoy | Estado |
| --- | --- | --- | --- |
| «437 fichas municipales» | comentario de `catalog.ts` | **438** | ya falso, pero no se ve en pantalla |
| «4.292 de los 8.005 procesos sin etapa» | comentario de `contracts.ts` | **1.891 de 8.014** | ya falso, y bastante |
| «2.271 procesos solo salen con el interruptor» | **pantalla** (pista del filtro) y portada | 2.271 | cierto hoy; la API lo publica en `notInDocumentedList` |
| «140 instantáneas datadas» | **pantalla** (entradilla del presupuesto) | 140 | cierto hoy; **cambia el mes que viene** |
| «Cuarenta y dos mil locales», «Ocho mil procesos», «Trece años» | **pantalla** (entradillas) | 42.346 · 8.014 · 2013–2026 | resisten por redondeo |

Dos cifras **ya son falsas** y están en comentarios, que es deriva de documentación: molesta al que lee el
código, no al que usa la pantalla. Las de pantalla siguen siendo ciertas, pero **dos de ellas lo son por suerte
o por poco tiempo**: «140 instantáneas» caduca cuando el ayuntamiento publique la foto de septiembre, y las
«2.271» las devuelve la propia respuesta en un campo que esa misma pantalla ya usa para otra cifra.

Lo que lo convierte en asunto de arquitectura y no en descuido: **hay dos fuentes para el mismo hecho** —la
respuesta y la memoria de quien escribió el párrafo— y solo una se actualiza. La regla que falta es sencilla y
comprobable: **ninguna cifra en el texto que la API pueda dar**; si la da, se interpola; si no la da, se escribe
con su fecha y su fuente («medido en S3.1, septiembre de 2026»), que además es el estilo del resto del
proyecto.

**F8. El contrato de tipos se escribe a mano.** Hay una alternativa estructural que hoy no se usa: **generar**
`types.ts` desde `/v3/api-docs`. Eliminaría la clase entera de fallos que la auditoría de ayer encontró, en vez
de vigilarla con un test de forma. A cambio: una dependencia de desarrollo, un paso de build y tipos generados
menos legibles que los actuales —que llevan la documentación del *por qué* de cada campo, que es media
arquitectura de este producto—. **No lo recomiendo todavía**, pero es la decisión que hay que revisar si vuelve
a aparecer un desajuste de contrato: el guardián actual avisa **después** de grabar la forma a mano.

**F9. No hay página de 404.** `{ path: '**', redirectTo: '' }` manda cualquier URL desconocida a la portada sin
decir nada. Con rutas profundas compartibles (F5) esto pasa de detalle a problema: un enlace que caduca no
avisa, teletransporta.

### 3.4 ¿Es mantenible? Lo que cuesta cada cambio

Esta es la prueba práctica de una arquitectura: qué hay que tocar para hacer algo.

| Cambio | Ficheros a tocar | Juicio |
| --- | --- | --- |
| Añadir una columna a un explorador | 1 (`columns` de su página) | bien |
| Añadir un filtro | 1–2 (su `filterDefs`; el mapeo si no es uno a uno) | bien, y el componente de filtros obliga a que exista en la API |
| Cambiar cómo se pide un listado | 1 (`core/api.ts`) | bien |
| Añadir una **sección** | 6: `api.ts`, `types.ts`, la página, su plantilla, `app.routes.ts`, `app.ts` (+ `home.ts` si va en la portada) | **regular**: dos de ellos son puntos calientes compartidos (F1) y tres son el catálogo duplicado (F4) |
| Añadir una **medida al cruce** | backend: enum + superficie del módulo; frontend: `ALL_MEASURES`, `MEASURE_LABELS` | bien, y el catálogo cerrado es deliberado (ADR-019) |
| Cambiar el sobre de respuesta de un módulo | backend: su `*Dtos`; frontend: su tipo de sobre y las páginas que lo usan | **regular**, por B1 |
| Rediseñar una pantalla | su plantilla + CSS; el sistema visual si es transversal | bien: `styles.css` centraliza los tokens y ADR-021 fija los oficios de color |

Y el coste de **entender** el frontend, que es el otro lado de la mantenibilidad: 21 ficheros TypeScript, cuatro
capas, un solo concepto de estado y cero librerías de terceros. Es un frontend que una persona puede leer
entero en una tarde. Eso no es poca cosa y conviene no perderlo: la mayoría de las deudas de arriba se arreglan
**sin** añadir una capa.

---

## 4. Veredicto por criterios

| Criterio | Backend | Frontend | Comentario |
| --- | --- | --- | --- |
| **Fronteras declaradas y verificadas** | sólido | sólido desde hoy | Modulith + ArchUnit; en el front, el test de capas de ADR-022 |
| **Encapsulación real** | sólido | **mejorable** | superficies mínimas frente a un `types.ts` global (F1) |
| **Dirección de dependencias** | sólido | sólido | acíclico en los dos, y el sumidero (`territory`) no lo conoce nadie |
| **Separación presentación / datos** | sólido | sólido con una fuga | `ui` limpio; `map` atado al contrato (F2) |
| **Estado** | n/a | **deuda mayor** | la URL no es la fuente de verdad (F5) |
| **Contrato entre las dos mitades** | derivado | vigilado | el sobre ha derivado en tres formas (B1); el cliente lo tapa |
| **Testabilidad** | sólido | mejorada hoy | dominio sin framework; en el front, 31 tests y el barrido |
| **Coherencia interna** | **mejorable** | buena | dos persistencias sin criterio escrito (B2), `spending` sin fronteras (B3) |
| **Operabilidad** | sólido | sólido | un despliegue, una base, 5 $/mes; sitio estático sin terceros |

---

## 5. Registro de deuda, por relación daño/coste

| # | Deuda | Daño | Coste | Recomendación |
| --- | --- | --- | --- | --- |
| F5 | La URL no contiene el estado de pantalla | no se puede enlazar un hallazgo; atrás no funciona; se pierde al navegar | medio (router + sincronización en `Explorer`/páginas) | **hacerlo**, y antes de publicar si se puede |
| F7 | Cifras a mano donde la API las publica | dos comentarios ya falsos y dos cifras de pantalla que caducan solas; este producto vive de no afirmar lo que el dato no sostiene | bajo | **hacerlo ya**: interpolar lo que la API dé, fechar lo demás |
| B3 | `spending`: tres agregados sin frontera interna | el módulo mayor crece sin regla; nada impide que se mezclen | bajo (renombrado mecánico) | **hacerlo** cuando se toque `spending` |
| F1 | `types.ts` como contrato global | punto caliente y sitio donde se esconden los fallos | bajo–medio | **hacerlo** al añadir la octava sección |
| F4 | Catálogo de secciones triplicado | una sección nueva se puede quedar a medio declarar sin que nada avise | bajo | test que compare los tres conjuntos, o catálogo único |
| B1 | Sobre de respuesta duplicado y derivado | tres formas de paginar en una API pública | medio (seis módulos) | **decidir**: unificar o documentar como contrato |
| B2 | JPA/JDBC sin criterio escrito | el próximo módulo elige a cara o cruz | muy bajo | ADR de dos párrafos |
| F2 | `map/` atado al contrato de la API | un cambio de respuesta llega al dibujo | bajo | modelo de vista propio en `map/` |
| B4 | `territory` conoce columnas ajenas | un renombrado deja metadatos falsos sin fallar | muy bajo | que cada superficie declare su `dateField` |
| F6 | Estado hecho a mano | 192 líneas que la plataforma hará gratis | nulo hoy | revisar cuando `httpResource` deje de ser experimental |
| F8 | Tipos escritos a mano | la clase de fallo se vigila, no se elimina | medio | solo si vuelve a fallar |
| F9 | Sin 404 | un enlace roto teletransporta a la portada | muy bajo | con F5 |
| F3 | Plantillas hablando con el estado | la página deja de mediar | nulo hoy | dejarlo escrito y vigilar |
| B5/B6/B7 | Voz del producto y aritmética en `web`; configuración grande | ninguno hoy | — | vigilar |

---

## 6. Lo que decidimos a propósito y no es deuda

Para que no se «arregle» por costumbre en la siguiente sesión:

1. **Monolito modular, no microservicios.** Un planificador, una base, un despliegue. Las costuras están
   puestas y verificadas por si algún día hay que separar.
2. **Sin dominio en el frontend.** Las reglas del producto viajan en la respuesta (`unit`, `caveats`,
   `coverage`); el navegador pinta. Duplicarlas en TypeScript sería garantizar que divergen.
3. **Sin store global en el frontend.** Siete exploradores independientes no comparten estado; un store solo
   añadiría acoplamiento e indirección.
4. **Sin caché en ningún lado** (ADR-019 §9), y ahora medido: la matriz compone en 0,80 s. Una caché haría que
   la pantalla enseñara cifras de antes sin poder decir de cuándo.
5. **Catálogo cerrado de medidas** en vez de un `groupBy` libre (ADR-019 §2): un parámetro abierto publicaría el
   modelo interno de cada módulo.
6. **Cero dependencias de tiempo de ejecución en el frontend**: el mapa y los gráficos a mano son ~1.000 líneas
   que evitan una librería, sus actualizaciones y su forma de pensar. Y un test lo defiende.
7. **Español en el texto de usuario** (regla 12). Si algún día hace falta otro idioma, es trabajo; hoy es
   coherencia.
