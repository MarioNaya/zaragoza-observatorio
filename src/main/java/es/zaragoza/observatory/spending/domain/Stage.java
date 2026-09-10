package es.zaragoza.observatory.spending.domain;

/**
 * Etapa del gasto (ADR-003 §2), y <b>solo donde el documento la sostiene</b> (ADR-017 §6).
 * <p>
 * ADR-003 daba por hecho que se podía derivar siempre. S3.1 midió que no: 1.560 procesos completos tienen un
 * contrato que es una cáscara con un identificador y nada más —sin {@code awardID}, sin {@code dateSigned} y sin
 * descripción—, así que no hay nada que sostenga que se firmaron. Esos procesos se publican <b>sin etapa</b>,
 * con su {@code tender.status} al lado; ponerles {@code COMMITTED} porque «existe un contrato» daría 4.970 en
 * vez de 3.410 y sería una conclusión del observatorio (regla 6).
 * <p>
 * {@code EXECUTED} no está aquí porque no está en la fuente: {@code planning} aparece en 0 documentos e
 * {@code implementation} en 0. El gasto ejecutado sale del presupuesto (S0.6), no de la contratación.
 */
public enum Stage {

	/** La licitación está {@code active}. Son 305. */
	PLANNED,

	/** Algún contrato del proceso trae fecha de firma. Son 3.410. */
	COMMITTED;

	/**
	 * La etapa que sostiene el documento, o {@code null} si no sostiene ninguna.
	 *
	 * @param tenderStatus {@code tender.status} tal como lo publica el origen
	 * @param anyContractSigned si algún contrato del proceso trae {@code dateSigned}
	 */
	public static Stage derive(String tenderStatus, boolean anyContractSigned) {
		if (anyContractSigned) {
			return COMMITTED;
		}
		if ("active".equalsIgnoreCase(tenderStatus)) {
			return PLANNED;
		}
		return null;
	}

}
