package es.zaragoza.observatory.spending.domain;

import java.util.regex.Pattern;

/**
 * La regla del texto de una concesión (ADR-018 §5, S3.3 §5). El título dice <b>para qué</b> era la ayuda y se
 * guarda; lo que no se guarda es el documento de identidad que la fuente mete dentro.
 * <p>
 * Sobre las 46.925 concesiones del censo hay <b>2.378 DNI y 381 NIE</b> en el campo {@code title}, y <b>todos</b>
 * tienen la letra de control correcta: no son falsos positivos de una expresión regular, son documentos de
 * identidad. Es justo lo contrario de lo que midió ADR-012 en las quejas, donde 2 DNI convivían con 3.353
 * fórmulas de firma que ningún patrón reconoce; por eso allí la redacción por patrones se descartó y aquí se
 * puede defender.
 * <p>
 * <b>Se redacta por forma, no por validez.</b> Se sustituye cualquier coincidencia con la forma de DNI o de NIE,
 * tenga o no la letra de control correcta. La validez se mide —y las 2.759 medidas la tienen— pero no decide:
 * una fila que se guardara por tener la letra mal sería exactamente el fallo que esto evita.
 * <p>
 * La segunda mitad de la garantía está en la base de datos: {@code spending_grant_title_has_no_identity} (V014)
 * rechaza cualquier título con forma de DNI o NIE, así que un traductor roto no puede colar uno.
 */
public final class GrantTitle {

	/** Lo que queda en el texto donde había un documento de identidad. */
	public static final String MARKER = "[identificador omitido]";

	/** Ocho dígitos y una letra: la forma de un DNI. */
	private static final Pattern DNI = Pattern.compile("\\b\\d{8}[A-Za-z]\\b");

	/** Letra inicial, siete dígitos y una letra: la forma de un NIE. */
	private static final Pattern NIE = Pattern.compile("\\b[XYZxyz]\\d{7}[A-Za-z]\\b");

	/**
	 * El título tal como se guarda, con el hecho de si hubo que redactarlo.
	 *
	 * @param text el texto, ya recortado, o {@code null} si el origen no trae ninguno
	 * @param redacted si se sustituyó algún documento de identidad
	 */
	public record Redacted(String text, boolean redacted) {
	}

	public static Redacted redact(String title) {
		if (title == null) {
			return new Redacted(null, false);
		}
		String trimmed = title.strip();
		if (trimmed.isEmpty()) {
			return new Redacted(null, false);
		}
		String clean = NIE.matcher(DNI.matcher(trimmed).replaceAll(MARKER)).replaceAll(MARKER);
		return new Redacted(clean, !clean.equals(trimmed));
	}

	/** Si un texto lleva dentro algo con forma de documento de identidad. Lo que la base de datos rechaza. */
	public static boolean carriesIdentity(String text) {
		return text != null && (DNI.matcher(text).find() || NIE.matcher(text).find());
	}

	private GrantTitle() {
	}

}
