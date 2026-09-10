package es.zaragoza.observatory.spending.domain;

import java.time.Instant;

/**
 * Un contrato del proceso. <b>1.560 de los 4.970 son cáscaras</b>: traen identificador y nada más, sin
 * {@code awardID}, sin {@code dateSigned} y sin descripción (S3.1 §4).
 * <p>
 * El modelo los admite tal cual a propósito. Son un hecho de la fuente, no un error de carga, y son la razón de
 * que 1.560 procesos completos se queden sin {@link Stage}: un contrato que no dice cuándo se firmó no sostiene
 * que se firmara.
 *
 * @param contractId {@code contracts[].id}
 * @param awardId {@code awardID}, {@code null} en las cáscaras
 * @param status terminated 3.391 · vacío 1.560 · active 19
 * @param signedOn {@code dateSigned}, {@code null} en las cáscaras
 */
public record Contract(String contractId, String awardId, String title, String description, String status,
		Instant signedOn, Money value, Instant periodStart, Instant periodEnd) {

	public Contract {
		if (contractId == null || contractId.isBlank()) {
			throw new IllegalArgumentException("contractId must not be blank");
		}
	}

	/** Una cáscara: existe el identificador y no hay nada más que leer. */
	public boolean isEmptyShell() {
		return awardId == null && signedOn == null && description == null && status == null;
	}

	public boolean isSigned() {
		return signedOn != null;
	}

}
