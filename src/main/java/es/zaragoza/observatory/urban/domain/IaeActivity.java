package es.zaragoza.observatory.urban.domain;

/**
 * El epígrafe del Impuesto de Actividades Económicas con el que el origen clasifica el local. Es <b>la</b>
 * actividad del producto: viene codificado en los 42.342 registros, con su título oficial, y pertenece a una
 * taxonomía cerrada de 965 entradas que la propia API publica en {@code registro-licencia/iae} (S2.4 §9).
 * <p>
 * Sustituye al campo de texto libre {@code actividad}, que decía lo mismo escrito a mano y traía dos DNI dentro
 * (ADR-016 §3). No es un recorte de capacidad de análisis: el epígrafe se agrupa y se compara, y el texto no.
 *
 * @param code {@code iae.identifier} ("16732"), la referencia del epígrafe
 * @param title {@code iae.title} ("OTROS CAFES Y BARES"), tal como lo publica el origen
 * @param section sección del epígrafe ({@code iae.id.seccion}), {@code null} si el origen no la trae
 * @param group agrupación ({@code iae.id.agrupacion}), el nivel por el que tiene sentido agregar
 */
public record IaeActivity(String code, String title, Integer section, Integer group) {

	public IaeActivity {
		code = blankToNull(code);
		title = blankToNull(title);
	}

	/** Un local sin epígrafe. No se ha observado ninguno, pero el modelo no lo prohíbe. */
	public static final IaeActivity NONE = new IaeActivity(null, null, null, null);

	public boolean isEmpty() {
		return code == null && title == null;
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

}
