package es.zaragoza.observatory.spending.domain;

import java.math.BigDecimal;
import java.util.Map;

/**
 * El universo de las subvenciones con sus huecos, para el resumen de la API. Los tres recuentos del final no son
 * telemetría: son los tres avisos que hacen falta para leer cualquier cifra de esta fuente (ADR-018 §5 y §7).
 *
 * @param grants concesiones cargadas
 * @param granted importe concedido, sumado
 * @param calls convocatorias cargadas
 * @param callBudget presupuesto declarado de las convocatorias, que <b>no</b> es lo repartido
 * @param beneficiaries beneficiarios distintos
 * @param naturalPersonBeneficiaries cuántos de ellos son personas físicas, de las que no se guarda identidad
 * @param naturalPersonGrants concesiones que van a una persona física
 * @param byClassification concesiones por clasificación de beneficiario
 * @param firstYear primer año de concesión con datos
 * @param lastYear último
 * @param withoutBeneficiary concesiones sin enlace de beneficiario: la v2 no publica 2013 ni 2014
 * @param redactedTitles concesiones cuyo título llevaba un documento de identidad y se guardó redactado
 * @param impossibleDates concesiones con fecha de concesión anterior a 1900: siete, y salen como tales
 */
public record GrantTotals(long grants, BigDecimal granted, long calls, BigDecimal callBudget, long beneficiaries,
		long naturalPersonBeneficiaries, long naturalPersonGrants, Map<String, Long> byClassification,
		Integer firstYear, Integer lastYear, long withoutBeneficiary, long redactedTitles, long impossibleDates) {

	public GrantTotals {
		granted = granted == null ? BigDecimal.ZERO : granted;
		callBudget = callBudget == null ? BigDecimal.ZERO : callBudget;
		byClassification = byClassification == null ? Map.of() : Map.copyOf(byClassification);
	}

}
