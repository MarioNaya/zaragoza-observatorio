package es.zaragoza.observatory.spending.domain;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * La identidad de una parte del documento OCDS, después de aplicarle la regla de ADR-017 §2.
 * <p>
 * Esta clase existe porque el origen mete el NIF <b>dentro</b> del identificador:
 * {@code 12619-NIF-B50892819-award-65236}. No es un campo que se pueda dejar de pedir como en ADR-012 —es la
 * clave con la que el documento enlaza adjudicación y adjudicatario—, así que se descompone aquí, y el
 * identificador crudo no se guarda en ninguna parte.
 * <p>
 * La regla, medida sobre los 17.614 identificadores de la fuente entera (S3.1 §6):
 * <ul>
 * <li>NIF que <b>empieza por letra</b> → persona jurídica. Su NIF es dato público de contratación: se guarda,
 * normalizado en mayúsculas, junto a la razón social. Son 8.481.</li>
 * <li>NIF que <b>empieza por dígito</b> → persona física. <b>No se guarda ni el NIF ni el nombre</b>: para una
 * persona el nombre es el identificador fuerte. La parte queda marcada como tal y su adjudicación sigue
 * contando con su importe: lo que desaparece es <i>quién</i>, no <i>cuánto</i>. Hoy es una.</li>
 * <li>Sin NIF incrustado → el identificador no dice nada de nadie (son el ayuntamiento y sus unidades
 * compradoras): se conserva el nombre y no hay NIF que guardar.</li>
 * </ul>
 * Es una <b>regla, no un recuento</b>: si mañana hubiera quinientas personas físicas, ninguna entraría.
 *
 * @param taxId NIF de persona jurídica normalizado, o {@code null}
 * @param name razón social, o {@code null} si es persona física
 * @param naturalPerson si el identificador del origen apunta a una persona física
 */
public record PartyIdentity(String taxId, String name, boolean naturalPerson) {

	/** El NIF va incrustado en el identificador: {@code 12619-NIF-B50892819-award-65236} (S3.1 §6). */
	private static final Pattern EMBEDDED_NIF = Pattern.compile("-NIF-([0-9A-Za-z]+)");

	public PartyIdentity {
		if (naturalPerson && (taxId != null || name != null)) {
			throw new IllegalArgumentException(
					"de una persona física no se guarda ni NIF ni nombre (ADR-017 §2)");
		}
	}

	/**
	 * Descompone el identificador y el nombre que publica el origen. Nunca devuelve {@code null}: una parte sin
	 * identificador ni nombre es una parte anónima, que es un hecho, no un error.
	 */
	public static PartyIdentity from(String sourceId, String name) {
		String cleanName = blankToNull(name);
		Matcher matcher = sourceId == null ? null : EMBEDDED_NIF.matcher(sourceId);
		if (matcher == null || !matcher.find()) {
			return new PartyIdentity(null, cleanName, false);
		}
		String nif = matcher.group(1).strip().toUpperCase(Locale.ROOT);
		if (nif.isEmpty()) {
			return new PartyIdentity(null, cleanName, false);
		}
		if (Character.isDigit(nif.charAt(0))) {
			// Persona física: no entra su NIF ni su nombre. La parte existe, con su importe; su identidad no.
			return new PartyIdentity(null, null, true);
		}
		return new PartyIdentity(nif, cleanName, false);
	}

	/** Con qué agrupar esta parte al agregar por adjudicatario: el NIF si lo hay, si no el nombre. */
	public String groupingKey() {
		if (taxId != null) {
			return taxId;
		}
		return name != null ? name : "";
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

}
