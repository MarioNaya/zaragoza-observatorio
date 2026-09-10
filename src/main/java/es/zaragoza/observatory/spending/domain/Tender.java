package es.zaragoza.observatory.spending.domain;

/**
 * La licitación de un proceso: qué se sacó a concurso, por cuánto y con qué procedimiento.
 * <p>
 * {@code title} y {@code description} se guardan tal cual (ADR-017 §3). Son texto administrativo que dice qué se
 * contrató, y sin ellos un contrato es un importe sin objeto: el CPV no los sustituye, porque solo lo trae el
 * 42,7 % de los procesos. El barrido completo de los 5.622 documentos no encontró un solo DNI ni NIE con letra
 * de control válida.
 * <p>
 * Los códigos se publican como los publica el origen y sin traducir, como en ADR-016 §6: {@code status}
 * (complete · active · unsuccessful · cancelled), {@code procurementMethod} (open · limited · direct ·
 * selective) y {@code mainProcurementCategory} (services · goods · works).
 *
 * @param title objeto del contrato
 * @param description descripción; en muchos procesos repite el título con un prefijo generado
 * @param status {@code tender.status}
 * @param procurementMethod {@code tender.procurementMethod}; 53 procesos lo traen vacío
 * @param category {@code tender.mainProcurementCategory}
 * @param awardCriteria {@code tender.awardCriteria}
 * @param numberOfTenderers licitadores declarados; muchos procesos antiguos publican 0
 * @param value importe <b>licitado</b>, nunca pagado
 * @param minValue {@code tender.minValue}, que el origen publica en los 5.606
 */
public record Tender(String title, String description, String status, String procurementMethod, String category,
		String awardCriteria, Integer numberOfTenderers, Money value, Money minValue) {

	public static final Tender NONE = new Tender(null, null, null, null, null, null, null, null, null);

	public boolean isActive() {
		return "active".equalsIgnoreCase(status);
	}

}
