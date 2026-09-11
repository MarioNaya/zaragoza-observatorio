# ADR-018 — Las subvenciones en `spending`: qué se guarda del beneficiario cuando el beneficiario es el dato

- **Fecha**: 2026-09-11
- **Estado**: aceptada
- **Afecta a**: `SPEC.md` §2, §4.6, §9; ADR-003 (§1 y §2, que se completan aquí); completa la serie ADR-012, ADR-016, ADR-017 y S3.2 sobre datos personales
- **Se apoya en**: `docs/spikes/S3.3-subvenciones-ingesta.md` (barrido completo: 46.925 concesiones, 1.589 convocatorias, 20.910 beneficiarios, 145 peticiones)

## Contexto

ADR-003 fundó `spending` sobre tres fuentes. Las dos primeras están dentro (ADR-017 y S3.2) y esta es la tercera
y última. Entra **sin ADR de contexto** —cabe en `spending` sin tocar sus fronteras, como preveía ADR-003 §1—,
pero necesita ADR propia por una razón distinta: **es la primera fuente en la que el dato personal es el
contenido, no un accidente del formato**. Una subvención sin beneficiario es un importe sin destinatario.

Las cuatro respuestas anteriores del proyecto no sirven aquí:

| decisión | figura | por qué no vale aquí |
|---|---|---|
| ADR-012 (quejas) | «no lo pidas» | el beneficiario no es un campo accesorio que se pueda no pedir sin perder el dato |
| ADR-016 (locales) | «no le des sitio» | vale para el texto, no para un campo que el producto necesita |
| ADR-017 (contratación) | «descompón el identificador» | supone que el identificador esconde el NIF; aquí el nombre viene en claro |
| S3.2 (presupuesto) | «lista cerrada» | eran cuatro filas contadas a mano; aquí son miles |

Y lo que S3.3 encontró tampoco estaba previsto: **la fuente se contradice consigo misma**.

| hecho medido | valor |
|---|---|
| Concesiones en el censo completo (`ayuda-subvencion/resolucion`, 2013–2026) | **46.925** |
| Concesiones cuyo beneficiario es persona física (por cualquiera de las dos señales) | **25.421 (57,4 %)** |
| Fichas del directorio clasificadas `personas-fisicas` | 16.450 |
| …con título distinto de «Datos de caracter personal» | **0** |
| …con dirección, teléfono o correo | **0** |
| `nifcif` enmascarado (`***332**`) | 6.355 |
| **Nombres reales de persona en `adjudicatario.nombre`** | **6.333** |
| **DNI con letra de control válida dentro de `title`** | **2.378** |
| **NIE válidos dentro de `title`** | **381** |
| Correos de proveedor gratuito en el directorio de entidades | 1.346 |

O sea: el ayuntamiento enmascara el NIF de la persona física, anonimiza su directorio de entidades hasta dejarlo
sin un solo dato de contacto… y publica el nombre y apellidos completos en un campo estructural y el DNI entero
dentro del texto del título.

Dos diferencias con S2.2, que son las que permiten decidir:

- Allí la redacción por patrones se descartó **con datos**: 2 DNI frente a 3.353 fórmulas de firma, que ninguna
  expresión regular reconoce. Aquí el problema es **exactamente** lo que una expresión regular reconoce, y las
  2.759 coincidencias tienen **todas** la letra de control correcta. No hay falsos positivos que discutir.
- Allí el texto libre no aportaba nada al producto. Aquí el título dice **para qué era la ayuda**, que es media
  lectura de la fuente.

## Decisión

### 1. La fuente es `ayuda-subvencion`, no `ayuda-subvencion-v2`

El censo es **`ayuda-subvencion/resolucion`** (46.925, 2013–2026). La v2 es un **subconjunto estricto** —cero
registros propios— y esconde los ejercicios 2013 y 2014. Se ingiere también **`ayuda-subvencion-v2/concesion`**,
pero **solo por el enlace con el beneficiario**, que la v1 no publica: de ella se leen tres campos (`id`,
`beneficiario`, `nifcif`) y ninguno más.

De la v1 entra además **`/convocatoria`** (1.589), que es la unidad de la que cuelga todo lo demás. No entran
`ayuda-subvencion` (el listado raíz: 20.737, menos años y sin nada que los otros no tengan), `/gestor` (68 roles
que ya vienen dentro de la convocatoria) ni `ayuda-subvencion-v2/convocatoria` (1.412, subconjunto del de la v1).

### 2. Toda petición manda `sort` y proyección, y nunca mezcla `rows` con `page`

- **`sort=id asc`** en todo barrido, como en el presupuesto (S3.2) y por lo mismo: sin él nada garantiza el
  orden entre páginas. Aquí el orden por defecto resultó estable, pero eso es una observación de un día, no un
  contrato.
- **Nunca se mandan `rows` y `page` juntos.** El desplazamiento lo calcula `pageSize` (50 por defecto) aunque el
  tamaño lo fije `rows`, así que mezclarlos solapa páginas en silencio (S3.3 §2). La v1 se pagina con
  `rows`+`start`; la v2, con `pageSize`+`page`.
- **`pageSize` no tiene tope** y puede devolver los 46.925 registros de una vez. No se usa así: se pagina, se
  cuenta por identificadores distintos y se respeta la memoria (ADR-009).

### 3. El nombre de la persona física no se descarga

La ingesta de concesiones manda una **proyección fija** que **no incluye `adjudicatario`**. Es la figura de
ADR-012 —la garantía es que el campo no llega—, y es posible porque en la v1 `fl` recorta de verdad (S3.3 §3).

Como en `citizen`, la proyección es una propiedad de configuración con **una comprobación que hace fallar el
arranque** si alguien añade un campo prohibido, y el traductor **ignora esos campos aunque lleguen**. Dos
garantías, no una.

La página cruda **no se guarda** (`keepsRawPayload() = false`, como en `urban`): la respuesta de la v2 no se
puede proyectar —`fl` sobre ella devuelve `{}`— y trae el `nifcif` enmascarado dentro.

### 4. El beneficiario entra como seudónimo, y su identidad solo si no es una persona

Se guarda una tabla de beneficiarios con:

- **`id`**: el identificador que ya publica la fuente. Es un **seudónimo estable**, y es lo que permite decir
  «este beneficiario recibió doce subvenciones por X €» sin nombrar a nadie. Entra para todos.
- **`natural_person`**: verdadero si **cualquiera** de las dos señales lo dice —clasificación `personas-fisicas`
  o `nifcif` enmascarado—. Las dos discrepan en 645 de 44.316 casos (1,5 %) y se toma la unión, que es la
  lectura conservadora.
- **`name`** y **`legal_nif`**: **solo cuando `natural_person` es falso**. De una persona física no entra el
  nombre, ni el NIF, ni el enmascarado.
- **`classification`**: el código que publica la fuente, tal cual, sin traducir (regla 6).

**No entra ningún dato de contacto**: ni domicilio, ni código postal, ni teléfono, ni correo, ni URL. No hacen
falta para el producto —`spending` no tiene territorio (ADR-003 §1) y ADR-011 §2 prohíbe geocodificar por
dirección— y 1.346 de esos correos son de un proveedor gratuito y 186 tienen forma `nombre.apellido@…`: son el
contacto personal de quien preside la asociación, no un dato de la entidad.

La base de datos lo impone, no solo el traductor:

```sql
CONSTRAINT spending_grant_beneficiary_no_natural_identity CHECK (
    NOT natural_person OR (name IS NULL AND legal_nif IS NULL))
```

Es la misma figura que `spending_award_party_no_natural_identity` (V012) y
`spending_budget_line_redacted_has_no_text` (V013).

### 5. El título se guarda con el identificador redactado

El título de una concesión dice para qué era la ayuda y se guarda. Cuando lleva dentro un DNI o un NIE —2.759 de
46.925, el 5,9 %— **se sustituye el identificador por un marcador** y se marca la fila con `title_redacted`. El
recuento se publica en `caveats`.

La redacción es **por forma, no por validez**: se sustituye cualquier coincidencia con la forma de DNI o NIE,
tenga o no la letra de control correcta. La validez se cuenta —y las 2.759 medidas la tienen— pero no decide:
una fila que se guardara por tener la letra mal sería exactamente el fallo que esto evita.

Y la base de datos lo impone:

```sql
CONSTRAINT spending_grant_title_has_no_identity CHECK (
    title IS NULL
    OR (title !~ '\m[0-9]{8}[A-Za-z]\M' AND title !~ '\m[XYZxyz][0-9]{7}[A-Za-z]\M'))
```

Con esa restricción puesta, un traductor roto no puede guardar un documento de identidad: la inserción falla.

### 6. Ninguna cifra se llama «lo que cobró Fulano»

Se publican cuatro importes por concesión, y no son intercambiables (misma regla que los ocho del presupuesto,
S3.2 §5): **solicitado**, **concedido**, **anual** y el número de anualidades. El concedido es un **acuerdo de
concesión**, no un pago: el dinero pagado sigue saliendo solo del presupuesto (obligación neta), y así se dice
en `caveats`. La convocatoria publica además su **presupuesto**, que es otra cosa: lo que se puso a disposición,
no lo que se repartió.

### 7. Las siete fechas imposibles y los 2.609 registros sin beneficiario salen como tales

- Siete concesiones tienen año `0002`, `0019` o `0022`. **No se corrigen ni se tiran**: su grupo sale como tal,
  igual que la licencia de 2033 de S2.4.
- Los **2.609 registros que solo publica la v1** (2013, 2014 y siete de 2015) **no tienen beneficiario**, porque
  el enlace lo da la v2 y la v2 no los publica. Salen con beneficiario nulo y el recuento va en `caveats`. No se
  adivina el enlace por nombre: eso sería fabricar el dato que la ADR acaba de decidir no guardar.

## Consecuencias

- El producto puede decir, por primera vez, **a cuántos beneficiarios distintos** va el dinero municipal y
  **cuánto se concentra**, sin nombrar a ninguna persona física.
- Se pierde el nombre del 57,4 % de los beneficiarios. Es una pérdida buscada: quien necesite ese dato tiene la
  fuente original, y el observatorio no es quien debe republicarlo.
- Aparece una cuarta comprobación de integridad personal en la base de datos, y es la primera que se apoya en
  una **expresión regular** y no en un `NULL`.
- El repositorio no puede guardar un fixture de esta fuente sin redactar. `SpikeFixtures.saveRedacted` cubre diez
  campos aquí, el mayor número hasta la fecha.
- Queda **abierto**, y solo lo puede cerrar el ayuntamiento (`docs/ESTADO.md` §6): por qué publica el nombre y el
  DNI de personas físicas en un conjunto en el que enmascara su NIF y anonimiza su directorio. Es el aviso más
  serio que ha salido del proyecto junto con el de S2.2.

## Alternativas descartadas

- **No ingerir la fuente.** Dejaría el contexto de gasto sin su tercera pata y sin la única fuente que dice a
  quién va el dinero. El problema tiene solución técnica y la solución no obliga a renunciar al dato.
- **Ingerir el nombre y no publicarlo.** Un dato personal que está en la base de datos está en el repositorio de
  copias, en los volcados y en la memoria del proceso. La regla 22 habla de que no entre, no de que no se vea.
- **No guardar ningún título** (la figura de ADR-012). Costaría el objeto de las 46.925 ayudas para evitar un
  problema que afecta al 5,9 % y que se reconoce sin ambigüedad.
- **No guardar el título de las 2.759 afectadas** (la figura de S3.2). Más conservador y más caro: esas ayudas
  perderían su objeto sin que la redacción del identificador deje nada identificable.
- **Guardar el `nifcif` enmascarado.** No aporta nada analítico —el seudónimo ya distingue beneficiarios— y es un
  identificador parcial de una persona.
- **Deducir el beneficiario de los 2.609 registros antiguos por nombre.** Exigiría guardar el nombre para
  compararlo, que es justo lo que esta ADR decide no hacer.

## Adenda (2026-09-11): lo que cambió al ingerir de verdad

La ingesta real cuadra con el spike al céntimo, y precisó dos cifras (S3.3 §10):

1. **La señal de persona física es del beneficiario, no de la concesión.** El spike contaba 25.421 concesiones
   mirando el identificador de cada una; si una sola de las concesiones de alguien llega enmascarada, esa
   persona lo es en todas. Contado así son **26.186 concesiones y 16.520 beneficiarios**, y los 107 casos
   discrepantes del §7 se concentran en **70 beneficiarios**. La implementación lo hace por beneficiario, que es
   lo que dice esta ADR, y esa es la cifra que publica el producto.
2. **Los títulos redactados son 2.758, no 2.759**: uno lleva dentro un DNI y un NIE a la vez.

Y una consecuencia de diseño que la primera versión no tenía: el identificador enmascarado llega por un recurso
distinto del directorio, así que **guardarlo como un simple `UPDATE` dejaba el resultado a merced del orden de
ingesta** —si el enlace llegaba antes que la ficha, no había fila que marcar y el directorio la creaba después
con su nombre puesto—. Por eso la fila recuerda `masked_identifier` y la escritura del enlace es un upsert que
crea la ficha si hace falta. Está probado ingiriendo los cuatro recursos **en el peor orden posible**.
