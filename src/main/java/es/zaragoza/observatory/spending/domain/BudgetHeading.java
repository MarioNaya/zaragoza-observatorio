package es.zaragoza.observatory.spending.domain;

import java.util.List;
import java.util.regex.Pattern;

/**
 * La regla de dato personal de esta fuente (S3.2 §8, regla 22). Aquí vive, como {@link PartyIdentity} en la
 * contratación, y por la misma razón: es una decisión del dominio, no una maña del traductor.
 * <p>
 * El barrido completo de las <b>154.508 filas</b> del histórico no encontró un solo DNI, NIE, correo ni
 * teléfono. Lo que sí encontró, y ningún patrón de identificador habría visto, es una <b>partida de pensión que
 * nombra a una persona física con nombre y apellidos</b> con la fórmula «a la viuda de D. …»: <b>4 filas</b>, en
 * los cierres de 2006 a 2009 y en ninguna instantánea posterior.
 * <p>
 * El nombre de la partida <b>hay que guardarlo</b> —sin él una partida es un importe sin concepto—, así que la
 * salida no puede ser la de ADR-012 (no pedirlo) ni la de ADR-016 (no guardar ninguna columna). Se guarda, y las
 * filas que casan esta lista <b>cerrada</b> de fórmulas se guardan sin el texto.
 * <p>
 * La redacción por patrón es justo lo que ADR-012 descartó, y aquí es defendible por lo contrario que allí: la
 * fuente está barrida entera, la lista es cerrada y son 4 filas de 154.508, no el 47,9 % del campo. <b>Añadir una
 * fórmula a esta lista exige volver a medir</b>: el recuento de filas redactadas se publica, y si un día crece se
 * ve.
 */
public final class BudgetHeading {

	/** Las fórmulas con las que el presupuesto antiguo nombra a una persona física. Lista cerrada (S3.2 §8). */
	public static final List<String> PERSON_FORMULAS = List.of("viuda de", "vda. de", "herederos de", "hdros");

	private static final Pattern PERSON = Pattern
			.compile("(?i)\\b(viuda\\s+de|vda\\.?\\s*de|herederos\\s+de|hdros)\\b");

	/** Si el nombre de la partida nombra a una persona física. */
	public static boolean namesNaturalPerson(String heading) {
		return heading != null && PERSON.matcher(heading).find();
	}

	/**
	 * El nombre de la partida tal como se guarda: recortado, o {@code null} si nombra a una persona física. El
	 * hecho no se pierde —la fila lleva {@code headingRedacted}— y se publica su recuento.
	 */
	public static String sanitize(String heading) {
		if (heading == null) {
			return null;
		}
		String trimmed = heading.strip();
		if (trimmed.isEmpty()) {
			return null;
		}
		return namesNaturalPerson(trimmed) ? null : trimmed;
	}

	private BudgetHeading() {
	}

}
