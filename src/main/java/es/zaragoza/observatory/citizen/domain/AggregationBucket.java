package es.zaragoza.observatory.citizen.domain;

/**
 * Un grupo de una agregación. No lleva ningún indicador compuesto ni etiqueta interpretativa (regla 6): números
 * y denominador, y quien lea saca la conclusión.
 * <p>
 * El tiempo de respuesta solo se calcula sobre las cerradas y viene acompañado de cuántas son. Las abiertas no
 * se estiman: tienen censura por la derecha y una mediana que las ignore sin decirlo engaña.
 *
 * @param key clave del grupo (id de junta, código de servicio o {@code yyyy-MM})
 * @param label etiqueta legible, {@code null} si el grupo no la tiene
 * @param total registros del grupo
 * @param closed cerradas del grupo
 * @param withPoint registros con punto (los únicos que pueden tener junta)
 * @param medianResponseHours mediana del tiempo de respuesta en horas sobre las cerradas, {@code null} si no hay
 */
public record AggregationBucket(String key, String label, long total, long closed, long withPoint,
		Double medianResponseHours) {
}
