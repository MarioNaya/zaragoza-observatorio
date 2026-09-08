# ADR-012 — El texto libre de las quejas no se pide: `citizen` ingiere una proyección mínima

- **Fecha**: 2026-09-08
- **Estado**: aceptada
- **Afecta a**: `SPEC.md` §4.6 (`citizen`), §4.7, §5, §9; `CLAUDE.md` reglas 22 y 30; `docs/ESTADO.md` §4
- **Se apoya en**: `docs/spikes/S2.2-quejas-ingesta.md` (peticiones reales del 2026-09-08), `docs/spikes/S0.3-open311.md` y su adenda, `docs/decisions/ADR-011-resolucion-territorial.md`

## Contexto

El ayuntamiento publica el texto libre de las quejas y sugerencias **sin anonimizar**. Se descubrió el 2026-09-06 preparando el repositorio para hacerlo público: en un fixture aparecían el nombre completo de una ciudadana y su DNI dentro de la descripción. La reacción inmediata fue defensiva y de higiene —redactar los fixtures, limpiar el historial con `git filter-repo`, regla 22—, pero dejó abierta la pregunta de producto: **qué hace el observatorio con ese texto cuando ingiera la fuente de verdad** (SPEC.md §9). Las opciones escritas entonces eran tres: no almacenarlo; almacenarlo redactado por detección de patrones; o guardar solo longitud, idioma y categoría.

S2.2 midió lo que hacía falta para decidir en vez de opinar:

- **El parámetro `fl` funciona.** Con `fl` de 9 campos la respuesta trae 9 campos y **no** trae `title`, `description` ni `service_notice`: no es un filtrado posterior, es que no llegan. Una página de 5 registros pasa de 3.605 a 1.142 bytes.
- **El texto es universal**: los 7.000 registros muestreados (500 por año, 2013–2026) traen texto en alguno de los tres campos. No es un caso raro que se pueda tratar como excepción.
- **La redacción por patrones no protege**: hay 2 DNI/NIE en 7.000 registros (0,03 %), pero **3.353 (47,9 %) contienen una fórmula de firma** («atentamente», «me llamo», «fdo.»). Son textos en primera persona donde el nombre propio aparece sin ningún patrón que una expresión regular reconozca. Una redacción automática quitaría los 2 DNI y dejaría los ~3.400 nombres, con la agravante de dar por resuelto el problema.
- **El producto no necesita el texto.** Toda la superficie de `citizen` en SPEC.md §4.7 —listado filtrable y agregaciones por junta, categoría, mes y estado, con denominador poblacional y `unassigned`— se resuelve con el identificador, el estado, la categoría, las dos fechas, el punto y la junta declarada. El texto no entra en ningún cálculo.
- **`address_string` tampoco tiene uso.** ADR-011 §2 prohíbe geocodificar por dirección, así que una dirección textual no puede convertirse en junta; y el punto, cuando existe, ya da la junta con `ST_Contains`.

## Decisión

1. **El texto libre no se pide.** El `SourceDescriptor` de `citizen` lleva un `fl` fijo con **ocho campos** y solo esos: `service_request_id`, `status`, `service_code`, `service_name`, `requested_datetime`, `updated_datetime`, `geometry`, `district`. `title`, `description` y `service_notice` no se solicitan, no se descargan y no llegan a `raw_payload`.
2. **Tampoco se pide `address_string`.** No sirve para nada que el observatorio pueda hacer (ADR-011 §2) y es, de los campos restantes, el de mayor riesgo. Si algún día se quisiera para una estadística de cobertura («registros con dirección pero sin punto»), hace falta una ADR nueva que diga para qué.
3. **El modelo no tiene dónde guardarlo.** `ServiceRequest` no declara campo de texto libre ni la tabla `citizen_service_request` columna alguna para él. No es una política de borrado que alguien pueda desactivar: es una ausencia estructural.
4. **La garantía se prueba, no se promete.** Un test comprueba que el descriptor de cada job lleva la proyección exacta de ocho campos, y otro que el traductor ignora `title`/`description`/`service_notice` si el origen los devolviera de todos modos (por un cambio en la API o porque `fl` deje de funcionar).
5. **La API propia nunca expone texto de la queja**, porque no lo tiene. Lo que se publica de cada registro es identificador de origen, estado, categoría, fechas, junta resuelta y junta declarada.
6. **Los fixtures siguen grabándose con `saveRedacted`** (regla 22). No es redundante: los spikes sí piden respuestas completas para poder medir —es la única forma de saber qué publica el origen—, y lo que no puede pasar es que ese texto entre en el repositorio.
7. **Lo que se pierde se dice en voz alta.** Sin texto no hay búsqueda libre, ni análisis de temas, ni desagregación más fina que las ~100 categorías de `services.json`. Es un límite aceptado a cambio de no custodiar datos personales de terceros que el observatorio no necesita.

## Consecuencias

- La ingesta de `citizen` descarga aproximadamente un tercio de los bytes que descargaría sin `fl`, lo que además abarata la carga inicial de 89.432 registros (179 páginas).
- `raw_payload` guarda las páginas crudas 14 días (`zaragoza.ingestion.raw-retention`). Como las páginas ya vienen sin texto, esa retención deja de ser un problema de datos personales: es la diferencia entre confiar en una purga y no tener nada que purgar.
- El hallazgo hay que **comunicarlo al ayuntamiento** (Gobierno Abierto o su delegado de protección de datos): que el observatorio decida no recogerlo no arregla que la API lo publique abierto a cualquiera. Ya está anotado en `docs/ESTADO.md` §6.
- Si el ayuntamiento anonimizase el texto en origen, la decisión se puede revisar con una ADR nueva; mientras tanto, el argumento no depende de la buena fe de nadie.
- El listado de sede tiene 89.432 registros y `statistics.json` cuenta ~40.000 cerradas al año: **es un subconjunto**, y así se declara en `caveats`. No se puede decir «las quejas de Zaragoza», sino «las quejas publicadas en el listado abierto».

## Alternativas descartadas

- **Almacenar el texto redactado por detección de patrones.** Es la opción que parece diligente y es la peor: quita el 0,03 % que una expresión regular sabe encontrar, deja el 48 % que no, y convierte un problema visible en uno invisible. Además obliga a custodiar el original en `raw_payload` mientras se redacta.
- **Almacenar solo longitud, idioma y categoría del texto.** Menos malo, pero sigue exigiendo descargar el texto para derivarlo, y nadie ha pedido esas métricas: sería recoger datos personales para producir una estadística que no está en la especificación.
- **Pedir el texto y no exponerlo por la API.** Es la trampa clásica: el riesgo no está en el endpoint, está en la copia. Con copias diarias del volumen (ADR-010) y `raw_payload`, «no exponerlo» significa tenerlo en cuatro sitios.
- **No ingerir la fuente.** Las quejas son el eje ciudadano de la fase 2 y el único dataset municipal con volumen, histórico y (parcialmente) punto. Renunciar a ella por un campo que se puede no pedir sería tirar el producto para evitar un problema que tiene solución exacta.
