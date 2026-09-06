package es.zaragoza.observatory.catalog.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Umbrales de frescura declarada (SPEC.md §4.6, §9; propuesta de S0.1 pendiente de confirmar con el muestreo
 * observado). Configurables; nunca se escriben en el código de consulta.
 *
 * @param onTimeMax ratio máximo para {@link DeclaredFreshness#ON_TIME}
 * @param slightDelayMax ratio máximo para {@link DeclaredFreshness#SLIGHT_DELAY}
 * @param delayedMax ratio máximo para {@link DeclaredFreshness#DELAYED}; por encima, {@code NOT_UPDATED}
 */
public record FreshnessPolicy(double onTimeMax, double slightDelayMax, double delayedMax) {

	public static final FreshnessPolicy DEFAULT = new FreshnessPolicy(1.0, 2.0, 5.0);

	public FreshnessPolicy {
		if (!(onTimeMax > 0 && slightDelayMax > onTimeMax && delayedMax > slightDelayMax)) {
			throw new IllegalArgumentException("thresholds must be positive and strictly increasing");
		}
	}

	public Evaluation evaluate(Dataset dataset, LocalDate today) {
		Integer periodDays = dataset.periodicityDays();
		Integer ageDays = dataset.declaredModified() == null ? null
				: (int) ChronoUnit.DAYS.between(dataset.declaredModified().toLocalDate(), today);
		if (periodDays == null || ageDays == null) {
			return new Evaluation(ageDays, periodDays, null, DeclaredFreshness.NOT_EVALUABLE);
		}
		double ratio = Math.max(0, ageDays) / (double) periodDays;
		return new Evaluation(ageDays, periodDays, ratio, categorize(ratio));
	}

	public DeclaredFreshness categorize(double ratio) {
		if (ratio <= onTimeMax) {
			return DeclaredFreshness.ON_TIME;
		}
		if (ratio <= slightDelayMax) {
			return DeclaredFreshness.SLIGHT_DELAY;
		}
		if (ratio <= delayedMax) {
			return DeclaredFreshness.DELAYED;
		}
		return DeclaredFreshness.NOT_UPDATED;
	}

	/**
	 * @param ageDays días desde {@code modified} hasta hoy, o {@code null} si no hay {@code modified}
	 * @param periodicityDays días del periodo declarado, o {@code null} si no es evaluable
	 * @param ratio {@code ageDays / periodicityDays}, o {@code null} si no es evaluable
	 */
	public record Evaluation(Integer ageDays, Integer periodicityDays, Double ratio, DeclaredFreshness category) {
	}

}
