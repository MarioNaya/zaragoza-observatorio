package es.zaragoza.observatory.spending.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * La regla de dato personal del beneficiario de una subvención (ADR-018 §4, S3.3 §5 y §7). Aquí vive, como
 * {@link PartyIdentity} en la contratación y {@link BudgetHeading} en el presupuesto, y por la misma razón: es
 * una decisión del dominio, no una maña del traductor.
 * <p>
 * Esta fuente es la primera del proyecto en la que <b>el dato personal es el contenido</b>: una subvención sin
 * beneficiario es un importe sin destinatario. Y es también la primera que <b>se contradice consigo misma</b>.
 * El ayuntamiento:
 * <ul>
 * <li>enmascara el identificador fiscal de la persona física ({@code ***332**}) en 6.355 concesiones y lo
 * sustituye por el texto constante {@code NIF} en otras 20.494;</li>
 * <li>anonimiza su directorio de entidades: las 16.450 fichas clasificadas {@code personas-fisicas} tienen un
 * solo título, «Datos de caracter personal», y ni dirección ni teléfono ni correo;</li>
 * <li>y <b>publica el nombre y apellidos</b> de 6.333 beneficiarios en {@code adjudicatario.nombre}.</li>
 * </ul>
 * Lo que se guarda, por tanto: el <b>identificador que ya publica la fuente</b>, que es un seudónimo estable y
 * permite contar cuánto recibe un mismo beneficiario sin nombrarlo; y el nombre y el NIF <b>solo cuando no es
 * una persona física</b>. El nombre no se descarga siquiera: la proyección de la ingesta no lo pide (ADR-018 §3).
 * <p>
 * <b>Persona física es la unión de las dos señales</b>, no su intersección: la clasificación del directorio y el
 * identificador enmascarado discrepan en 645 de 44.316 concesiones (1,5 %), y ante la duda no se publica.
 */
public final class GrantIdentity {

	/** Clasificación con la que el directorio marca a una persona física. */
	public static final String NATURAL_PERSON_CLASS = "personas-fisicas";

	/** Título con el que el directorio nombra a una persona física. Nunca hay otro (S3.3 §6). */
	public static final String PERSONAL_DATA_TITLE = "Datos de caracter personal";

	/** Texto constante con el que la fuente sustituye el identificador fiscal que no publica. */
	public static final String NIF_PLACEHOLDER = "NIF";

	/** Texto constante con el que la fuente sustituye la razón social que no publica. */
	public static final String NAME_PLACEHOLDER = "RAZÓN SOCIAL";

	/** NIF de persona jurídica: empieza por letra de forma societaria. Un DNI nunca casa aquí. */
	private static final Pattern LEGAL_NIF = Pattern.compile("[ABCDEFGHJNPQRSUVW]\\d{7}[0-9A-J]");

	/** El enmascarado con el que la fuente publica el identificador de una persona física. */
	private static final Pattern MASKED = Pattern.compile("[*\\d]*\\*[*\\d]*");

	/**
	 * Si el identificador fiscal que publica la fuente está enmascarado, que es como marca a una persona física.
	 * Es <b>una</b> de las dos señales; la otra es la clasificación del directorio.
	 */
	public static boolean isMaskedIdentifier(String nif) {
		String value = normalize(nif);
		return value != null && MASKED.matcher(value).matches();
	}

	/** Si la clasificación del directorio dice que es una persona física. */
	public static boolean isNaturalPersonClass(String classification) {
		return classification != null
				&& NATURAL_PERSON_CLASS.equalsIgnoreCase(classification.strip().toLowerCase(Locale.ROOT));
	}

	/**
	 * Persona física por <b>cualquiera</b> de las dos señales. La lectura conservadora: las dos discrepan en el
	 * 1,5 % de los casos y ante la duda no se publica identidad.
	 */
	public static boolean isNaturalPerson(String classification, boolean maskedIdentifier) {
		return maskedIdentifier || isNaturalPersonClass(classification);
	}

	/**
	 * El NIF que se guarda: solo el de persona jurídica, y solo si el beneficiario no es una persona física. Ni
	 * el enmascarado ni el marcador constante entran.
	 */
	public static String legalNif(String nif, boolean naturalPerson) {
		String value = normalize(nif);
		if (value == null || naturalPerson || NIF_PLACEHOLDER.equals(value) || !LEGAL_NIF.matcher(value).matches()) {
			return null;
		}
		return value;
	}

	/**
	 * El nombre que se guarda: el de la entidad, y nunca el de una persona física. Los dos textos constantes con
	 * los que la fuente rellena lo que no publica tampoco entran: son ruido, no nombres.
	 */
	public static String name(String name, boolean naturalPerson) {
		String value = normalize(name);
		if (value == null || naturalPerson || NAME_PLACEHOLDER.equals(value) || PERSONAL_DATA_TITLE.equals(value)) {
			return null;
		}
		return value;
	}

	private static String normalize(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.strip();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private GrantIdentity() {
	}

}
