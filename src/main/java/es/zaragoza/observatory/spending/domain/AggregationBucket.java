package es.zaragoza.observatory.spending.domain;

import java.math.BigDecimal;

/**
 * Un grupo de una agregación. Sin indicadores compuestos ni etiquetas interpretativas (regla 6): hechos,
 * denominadores y nada más.
 * <p>
 * Los <b>dos importes van siempre por separado</b> y ninguno se llama «importe» a secas: el histórico son 4.359
 * M€ licitados frente a 1.819 M€ adjudicados, y confundirlos es un factor de 2,4. Ninguno de los dos es dinero
 * pagado (ADR-017 §6).
 *
 * @param key clave del grupo; {@code null} es un grupo legítimo (procesos sin etapa, sin procedimiento…)
 * @param label etiqueta legible cuando el origen la publica, {@code null} si no
 * @param year año del grupo en los ejes que lo llevan
 * @param processes procesos distintos del grupo
 * @param awards adjudicaciones del grupo
 * @param withRelease procesos del grupo cuyo detalle se ha podido leer; el resto son ocids sin release y por eso
 * sin importe ni objeto
 * @param withoutStage procesos del grupo sin etapa derivable
 * @param tenderedAmount suma de importes licitados
 * @param awardedAmount suma de importes adjudicados (solo adjudicaciones activas)
 */
public record AggregationBucket(String key, String label, Integer year, long processes, long awards,
		long withRelease, long withoutStage, BigDecimal tenderedAmount, BigDecimal awardedAmount) {

	/** Registros del grupo en la unidad del eje. */
	public long total(AggregationAxis axis) {
		return axis.unit() == AggregationAxis.Unit.AWARDS ? awards : processes;
	}

}
