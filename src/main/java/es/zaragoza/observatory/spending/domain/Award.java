package es.zaragoza.observatory.spending.domain;

import java.time.Instant;
import java.util.List;

/**
 * Una adjudicación del proceso. 3.410 procesos tienen alguna y 195 tienen más de una, así que el importe
 * adjudicado de un proceso es una suma, no un campo.
 * <p>
 * Sus partes llegan ya despojadas del identificador crudo del origen: ver {@link PartyIdentity} y ADR-017 §2.
 *
 * @param awardId {@code awards[].id} del origen
 * @param title título generado por el origen («Award of contract to …»)
 * @param description descripción generada por el origen; nombra al adjudicatario
 * @param status active 3.280 · unsuccessful 609 · pending 13
 * @param awardedOn {@code awards[].date}
 * @param value importe <b>adjudicado</b>, nunca pagado
 * @param parties adjudicatarias, en el orden en que las publica el origen
 */
public record Award(String awardId, String title, String description, String status, Instant awardedOn,
		Money value, List<PartyIdentity> parties) {

	public Award {
		if (awardId == null || awardId.isBlank()) {
			throw new IllegalArgumentException("awardId must not be blank");
		}
		parties = parties == null ? List.of() : List.copyOf(parties);
	}

	public boolean isActive() {
		return "active".equalsIgnoreCase(status);
	}

}
