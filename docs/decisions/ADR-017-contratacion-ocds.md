# ADR-017 — La contratación OCDS en `spending`: qué se guarda de las partes, del texto libre y de lo que el documento no dice

- **Fecha**: 2026-09-10
- **Estado**: aceptada
- **Afecta a**: `SPEC.md` §2, §4.6, §4.7, §9; ADR-003 (§2 y §4, que se concretan aquí)
- **Se apoya en**: `docs/spikes/S3.1-ocds-ingesta.md` (barrido completo: 8.001 procesos, 5.622 release packages, 25,2 MB)

## Contexto

ADR-003 fundó `spending` sobre tres fuentes y dejó la primera, la contratación OCDS, descrita a grandes rasgos: `ContractingProcess`, `Award`, `Contract`, `Supplier`, y un `stage` con valores `planned` / `committed` / `executed`. S3.1 midió la fuente entera antes de escribir código y encontró cuatro cosas que ese boceto no preveía.

1. **El listado documentado es un subconjunto estricto.** Publica 5.730 de los 8.001 procesos que existen. Los 2.271 restantes solo aparecen mandando `after`, y `after` **no es una fecha**: por debajo de un umbral situado entre el 1 y el 2 de enero de 2017 no hace nada, y por encima el valor da igual —`after=2030-01-01` devuelve procesos publicados en 2008—. Ningún filtro de fecha toca a esos 2.271: entran o no entran.
2. **El 29,7 % de los procesos no tiene release publicado** (2.379 responden 404), y no están repartidos al azar: la cobertura cae del 100 % en los expedientes más antiguos al 8 % en el último decil. Son releases que aún no se han publicado, no procesos inexistentes. Además, 16 packages responden 200 con `releases` vacío.
3. **1.560 contratos son cáscaras**: `contracts[].id` aparece 4.970 veces, pero `awardID`, `dateSigned` y `description` solo 3.410. Son procesos completos cuyo contrato no dice cuándo se firmó.
4. **El dato personal está en un sitio inesperado**: no en el texto libre, sino en `parties[].id`, que lleva el **NIF incrustado** (`12619-NIF-B50892819-award-65236`) y es además la clave con la que el documento enlaza adjudicación y adjudicatario. La jugada de ADR-012 —no pedir el campo— no existe aquí: el identificador es estructural.

Los números del riesgo, sobre los 5.622 documentos:

| medida | valor |
|---|---|
| DNI con letra de control válida | **0** |
| NIE válidos | **0** |
| `parties[].id` con NIF incrustado | 8.482 de 17.614 |
| de esos, persona jurídica (empieza por letra) | **8.481** |
| de esos, empieza por dígito (persona física) | **1** |
| fórmula de tratamiento en texto libre | **21** (0,4 % de los documentos) |
| correos | 3 |

Para comparar: ADR-012 descartó la redacción por patrones porque el **47,9 %** de las quejas traía fórmula de firma, y ADR-016 apagó `raw_payload` porque el registro de licencias traía **15 DNI válidos**. Aquí el orden de magnitud es otro, y está medido sobre la fuente entera, no sobre una muestra.

## Decisión

### 1. La enumeración va con el interruptor, y se comprueba

El censo de procesos se pide con **`after=2030-01-01T00:00:00Z`**, que no es una fecha sino el interruptor que abre los 2.271 procesos que el listado esconde. El código lo dice con ese nombre (`OcdsListing.AFTER_SWITCH`) para que nadie lo tome por una ventana temporal y lo «arregle».

Además, en cada ingesta se pide **también el listado sin filtro** y se comprueba que es **subconjunto** del ampliado. Si algún día deja de serlo, la comprobación **falla ruidosamente** en vez de adivinar: significaría que el interruptor ha cambiado de sentido y que la enumeración ya no es completa.

De paso, esa segunda petición produce un hecho publicable que hoy nadie tiene: **qué procesos están fuera del listado documentado**. Se guarda en `in_documented_list` y se publica como `inDocumentedList`, con el aviso de que quien pagine el listado documentado se lleva el 71,6 % del histórico creyendo que lo tiene entero.

### 2. El NIF de persona jurídica se guarda; el de persona física, nunca

`parties[].id` **no se guarda tal cual**, porque guardarlo sería guardar el NIF sin decidir de quién es. Se descompone al traducir:

- Si el NIF incrustado **empieza por letra**, es de persona jurídica y su NIF es dato público de contratación: se guarda normalizado en `tax_id` junto al nombre. Es lo que permite agregar por adjudicatario sin depender de cómo esté escrito el nombre, que es frágil.
- Si **empieza por dígito**, es de persona física: **no se guarda ni su NIF ni su nombre**. La parte se guarda como adjudicataria marcada `natural_person`, sin identidad, y el recuento se publica en `caveats`. La adjudicación sigue contando con su importe: lo que desaparece es *quién*, no *cuánto*.

Es una **regla, no un recuento**. Hoy hay una sola persona física en 8.482 identificadores; si mañana hubiera quinientas, ninguna entraría. La misma regla se aplica a `tenderer`, `supplier`, `buyer` y `procuringEntity`.

No guardar tampoco el **nombre** de la persona física es deliberado y va más allá de lo que exige el NIF: para una persona física el nombre es el identificador fuerte, y ADR-012 ya decidió no republicar identidad de personas aunque el ayuntamiento la publique. La coherencia manda aquí lo mismo.

### 3. El texto libre se guarda tal cual

`tender.title`, `tender.description`, `awards[].title`, `awards[].description` y `contracts[].description` **se guardan y se publican**. Son texto administrativo que dice **qué se contrató**, y sin ellos un contrato es un importe sin objeto —el CPV solo está en el 42,7 % de los procesos, así que no lo sustituye—.

La decisión se apoya en los números de §4 del spike, no en una impresión: cero identificadores válidos y 21 fórmulas de tratamiento en 5.606 documentos. No se redacta por patrones, por la misma razón que en ADR-012: una expresión regular que no reconoce el 47,9 % de los casos allí tampoco garantiza nada aquí, y aplicarla daría una **falsa sensación de garantía**. Lo que se hace es dejar constancia de la medición y repetirla: la comprobación de DNI y NIE del spike se conserva como test sobre los fixtures, y si algún día aparece uno válido, la decisión se revisa con una ADR nueva, no con un parche.

### 4. La página cruda del listado sí se guarda

Al contrario que en ADR-016, aquí `keepsRawPayload()` se queda en `true`. El listado son 622 KB de `ocid` e `id`, sin una sola línea de texto ni un solo identificador de persona, y la retención de catorce días lo acota. Sirve además de evidencia cruda del comportamiento del interruptor, que es la rareza de esta fuente.

**El detalle no pasa por ahí en ningún caso**: son 8.001 peticiones fuera del ciclo de página (§5), así que los 25,2 MB de release packages no se guardan nunca en `raw_payload`.

### 5. Dos pasos, como en `geo`: el listado es un job, el detalle es un planificador

El listado es un `IngestionJob` de una sola petición (`Pagination.none`, forma `ARRAY`, sin `start`: la fuente lo ignora). El detalle **no** cabe en `handle(RawPage)`: son miles de peticiones.

El detalle lo lee un planificador propio por lotes, exactamente el patrón del muestreo observado de ADR-005 (`zaragoza.spending.releases.*`: `tick`, `batch-size`, `request-delay`). Con los valores por defecto —200 por tick cada 10 minutos con pausa de 0,2 s— el histórico entero se completa en unas siete horas y el mantenimiento diario cuesta una fracción de un lote.

La **cadencia de reintento** distingue tres situaciones, porque no son la misma:

| estado | cuándo se vuelve a pedir |
|---|---|
| nunca pedido | ya |
| **404 o release vacío** | espera creciente: 1, 2, 4, 8, 16 días, con tope de 30 |
| publicado, con licitación **activa** | cada 7 días (es lo único que puede cambiar pronto) |
| publicado y cerrado | cada 30 días |

Reintentar los 2.379 sin release todos los días serían 2.379 peticiones diarias para nada. Con espera creciente, una fracción; y como el 404 se concentra en los expedientes recientes, los que más probabilidad tienen de publicarse son justo los que menos tiempo llevan esperando.

**Un proceso sin release no es un error y no se descarta**: se guarda con su ocid, su estado, el número de intentos y la fecha del último, y se publica como lo que es: el 29,7 % del universo.

### 6. `stage` solo donde el documento lo sostiene

ADR-003 §2 daba `stage` por derivable siempre. No lo es:

- **`COMMITTED`** solo si algún contrato del proceso trae `dateSigned`. Son 3.410.
- **`PLANNED`** solo si la licitación está `active`. Son 305.
- **En los 1.560 restantes, `stage` es nulo** y así se publica, con su reparto por `tender.status` al lado. Ponerles `committed` porque «existe un contrato» sería una conclusión del observatorio sobre un documento que no la dice (regla 6).

`EXECUTED` **no existe en esta fuente**: `planning` aparece en 0 documentos e `implementation` en 0. El importe de contratación es **adjudicado, nunca pagado**, y cada respuesta lo dice en `unit` y en `caveats`. `executed` saldrá del presupuesto (S0.6), no de aquí.

### 7. Los hechos se publican en su unidad, y las unidades no se suman

Un proceso, una adjudicación y una licencia de CPV no son la misma cosa:

- los ejes `year`, `tag`, `tender_status`, `procurement_method`, `category`, `stage` y `procuring_entity` cuentan **procesos**;
- `supplier` cuenta **adjudicaciones** (una empresa puede tener varias, y 195 procesos tienen más de una);
- `cpv` cuenta **procesos**, pero un proceso con dos CPV cuenta en los dos, así que la suma de los grupos **no es** el total y la respuesta lo dice.

Cada respuesta declara su `unit`, como en ADR-016 §7. Cada grupo lleva **dos importes separados**, `tenderAmount` y `awardedAmount`, nunca uno solo llamado «importe»: el histórico son 4.359.428.216 € licitados frente a 1.819.170.873 € adjudicados, y confundirlos es un factor de 2,4.

### 8. Sin territorio, y la ausencia se publica

Confirmado sobre la fuente entera: cero caminos de localización en 184 caminos distintos. ADR-003 §1, §3 y §5 se mantienen sin matices. `spending` **no depende de `geo`**, no tiene columna de junta y ninguna respuesta admite un parámetro territorial. El `caveats` lo dice en vez de dejar que se note por ausencia.

### 9. El recurso se llama `processes`, no `contracts`

`SPEC.md` §4.7 lo llamaba `GET /spending/contracts`. Se publica como **`GET /api/v1/spending/processes`**, porque lo que hay son procesos de contratación y llamarlos contratos afirmaría algo falso de 4.591 de ellos: 2.379 no tienen release, 1.560 tienen un contrato sin fecha de firma y 305 son licitaciones vivas. El recurso `contracts` volverá cuando haya contratos que enumerar por sí mismos.

## Consecuencias

- Migración **`V012__spending_contracting.sql`** con cinco tablas: `spending_process`, `spending_award`, `spending_award_party` (sin `parties[].id` crudo), `spending_contract` y `spending_process_cpv`. `spending_contract` admite la cáscara vacía —solo identificador— porque es un hecho de la fuente, no un error de carga.
- El módulo `spending` declara `allowedDependencies = { "shared", "ingestion" }`. **No `geo`**, y el test de modularidad lo hace cumplir.
- La API publica el universo, no solo lo que se pudo leer: `GET /api/v1/spending/summary` da los cuatro estados de release y el recuento sin `stage`.
- La ingesta histórica son 8.001 peticiones y ~7 h de planificador. Su efecto sobre la memoria de la instancia (línea base 425 MB, ADR-009) se mide al desplegar, no se supone.
- `SPEC.md` §2 corrige el universo de OCDS (5.720 → **8.001**, y el listado documentado solo publica 5.730); §4.6 añade el estado de release y quita la derivación universal de `stage`; §4.7 renombra el recurso; §9 cierra «semántica real de `before`/`after`» y abre «por qué 1.560 contratos están vacíos».
- **Dos preguntas para el ayuntamiento** (`docs/ESTADO.md` §6): por qué el listado documentado esconde 2.271 procesos, y si los 1.560 contratos sin fecha de firma son un fallo de publicación o procesos que no llegaron a firmarse.

## Alternativas descartadas

- **Enumerar por el listado documentado** (lo que haría cualquiera que siga la documentación): se pierde el 28,4 % del histórico sin ningún aviso.
- **Deducir el umbral de `after`** y mandar una fecha «suficientemente antigua»: el umbral está entre dos días concretos de 2017 y puede moverse. Un valor futuro más la comprobación de subconjunto no depende de dónde esté.
- **Descartar los procesos sin release** o contarlos como error: son el 29,7 % del universo y son justo los recientes. Un observatorio que los esconde publica un histórico que se acaba en 2022 sin decirlo.
- **Derivar `committed` de la existencia de un contrato**: daría 4.970 en vez de 3.410 y convertiría una cáscara sin fecha en un hecho firmado.
- **Redactar el texto libre por patrones**: ADR-012 ya midió que no funciona, y aquí además no hay nada que redactar.
- **Guardar `parties[].id` tal cual** «porque el origen ya lo publica»: mete un identificador de persona física en la base de datos y en la API propia, y ADR-012 ya decidió lo contrario en un caso idéntico.
- **No guardar ningún NIF**: la agregación por adjudicatario tendría que hacerse por nombre, y la misma empresa aparece escrita de varias formas.
- **Ingerir los endpoints hermanos** (`award`, `contract`, `tender`, `organisation`): devuelven listados pobres, `organisation.json?rows=5` devuelve 10 elementos, y ninguno aporta nada que no esté en el release package (S0.2, S3.1 §7).
