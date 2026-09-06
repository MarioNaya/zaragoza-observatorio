package es.zaragoza.observatory.catalog.domain;

import java.time.Duration;
import java.time.Period;
import java.time.format.DateTimeParseException;

/**
 * Conversión de {@code accrualPeriodicity} (ISO 8601 en el catálogo, S0.1) a días aproximados para el ratio de
 * frescura declarada. Valores observados en los 436 datasets: {@code P1Y}, {@code P1M}, {@code P1D}, {@code P3M},
 * {@code P1W}, {@code P2Y}, {@code P4Y}, {@code P5Y}, {@code P6M} (evaluables), {@code P0DT1S} (tiempo real),
 * {@code NEVER}, {@code IRREG} y vacío (no evaluables).
 */
public final class Periodicity {

	private Periodicity() {
	}

	/**
	 * @return días aproximados (años × 365, meses × 30, más días) o {@code null} si el valor no es un periodo ISO
	 * 8601 con al menos un día ({@code P0DT1S}, {@code NEVER}, {@code IRREG}, vacío)
	 */
	public static Integer days(String accrualPeriodicity) {
		if (accrualPeriodicity == null) {
			return null;
		}
		String value = accrualPeriodicity.strip();
		if (!value.startsWith("P")) {
			return null;
		}
		long days;
		try {
			Period period = Period.parse(value);
			days = period.getYears() * 365L + period.getMonths() * 30L + period.getDays();
		}
		catch (DateTimeParseException notAPeriod) {
			try {
				days = Duration.parse(value).toDays();
			}
			catch (DateTimeParseException notADuration) {
				return null;
			}
		}
		return days <= 0 ? null : (int) Math.min(days, Integer.MAX_VALUE);
	}

}
