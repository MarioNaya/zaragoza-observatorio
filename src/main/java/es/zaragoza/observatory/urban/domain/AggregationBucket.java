package es.zaragoza.observatory.urban.domain;

/**
 * Un grupo de una agregación. Sin indicadores compuestos ni etiquetas interpretativas (regla 6): números y
 * denominador, y quien lea saca la conclusión.
 * <p>
 * {@code total} cuenta la unidad del eje ({@link AggregationAxis#unit()}), no siempre lo mismo. {@code premises}
 * y {@code licences} van los dos en cada grupo justamente para que se pueda ver la diferencia sin tener que
 * pedir otra agregación.
 *
 * @param key clave del grupo (id de junta, agrupación IAE, año, tipo de licencia)
 * @param label etiqueta legible tal como la publica el origen, {@code null} si el grupo no la tiene
 * @param total registros del grupo, en la unidad del eje
 * @param premises locales distintos del grupo
 * @param licences licencias del grupo
 * @param withPoint locales del grupo que traen punto (los únicos que pueden tener junta)
 * @param deregistered registros del grupo con {@code fechaBaja}. <b>No</b> significa «cerrados»: el origen no
 * publica taxonomía de estado y no se puede afirmar qué es una baja (ADR-016 §6)
 * @param year año del grupo en los ejes que lo llevan, {@code null} en los demás
 */
public record AggregationBucket(String key, String label, long total, long premises, long licences, long withPoint,
		long deregistered, Integer year) {
}
