package es.zaragoza.observatory.spending.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Una concesión de subvención: el acuerdo por el que se concede una ayuda a un beneficiario (S3.3 §4).
 * <p>
 * <b>Ninguno de sus importes es dinero pagado.</b> {@code granted} es lo que se acordó conceder; lo que salió de
 * la caja sigue estando solo en el presupuesto, en la obligación neta y el pago neto (S3.2). Los tres importes
 * viajan separados por lo mismo que los ocho del presupuesto: no son intercambiables.
 * <p>
 * El origen no declara moneda en ningún campo, así que aquí tampoco se inventa una: son {@link BigDecimal} y el
 * {@code caveats} dice que son euros porque lo es el presupuesto municipal, no porque lo diga el dato.
 *
 * @param id identificador de la concesión en el origen. La v1 y la v2 comparten espacio de identificadores
 * @param callId convocatoria de la que cuelga, o {@code null} si el origen no la trae
 * @param title para qué era la ayuda, con el documento de identidad redactado si lo llevaba (ADR-018 §5)
 * @param titleRedacted si hubo que redactar el título
 * @param fileNumber expediente ({@code expediente}), de la forma {@code 0618143/2014}
 * @param requested importe solicitado
 * @param granted importe concedido
 * @param annual importe de la anualidad
 * @param annuities ejercicio de la anualidad ({@code numAnualidades}), que no es un número de anualidades pese
 * al nombre: los valores observados son años
 * @param requestedOn fecha de solicitud
 * @param grantedOn fecha de concesión, el eje de la serie
 * @param agreedOn fecha de acuerdo, que solo trae una parte de los registros
 */
public record Grant(long id, Integer callId, String title, boolean titleRedacted, String fileNumber,
		BigDecimal requested, BigDecimal granted, BigDecimal annual, Integer annuities, LocalDate requestedOn,
		LocalDate grantedOn, LocalDate agreedOn) {

	public Grant {
		if (id <= 0) {
			throw new IllegalArgumentException("id must be positive");
		}
		if (titleRedacted && title == null) {
			throw new IllegalArgumentException("a redacted title keeps the rest of the text, never null");
		}
		if (GrantTitle.carriesIdentity(title)) {
			throw new IllegalArgumentException("a grant title must not carry an identity document (ADR-018 §5)");
		}
		fileNumber = blankToNull(fileNumber);
	}

	/** El año por el que se agrupa la serie: el de la fecha de concesión. */
	public Integer year() {
		return grantedOn == null ? null : grantedOn.getYear();
	}

	private static String blankToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.strip();
		return trimmed.isEmpty() ? null : trimmed;
	}

}
