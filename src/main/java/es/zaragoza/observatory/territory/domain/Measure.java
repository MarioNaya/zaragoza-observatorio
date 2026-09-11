package es.zaragoza.observatory.territory.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Catálogo <b>cerrado</b> de medidas que se pueden cruzar por junta (ADR-019 §2).
 * <p>
 * Que sea cerrado es la decisión, no una limitación de la implementación: la libertad que la fase 4 necesitaba
 * es la de <b>combinar medidas y ventana</b>, no la de agregar por cualquier campo. Un parámetro que aceptara
 * campos arbitrarios obligaría a publicar el modelo interno de cada módulo y convertiría la API en un motor de
 * consultas que hay que defender de peticiones caras.
 * <p>
 * Son tres, de dos módulos, y eso es <b>lo que hay territorializable en los datos abiertos de Zaragoza hoy</b>:
 * el gasto público no tiene junta en ninguna de sus tres fuentes. El catálogo crece añadiendo una entrada —con
 * la fuente que la respalda—, nunca abriendo un parámetro.
 */
public enum Measure {

	/** Quejas y sugerencias por junta resuelta. La ventana se aplica sobre el alta (ADR-012, S2.2). */
	CITIZEN_REQUESTS("citizen.requests", "citizen", Unit.REQUESTS, "requested_at", "alta de la queja"),

	/** Locales con licencia por junta resuelta. La ventana se aplica sobre el alta del local (ADR-016, S2.4). */
	URBAN_PREMISES("urban.premises", "urban", Unit.PREMISES, "created_at", "alta del local"),

	/**
	 * Licencias por junta resuelta del local. La ventana se aplica sobre el <b>alta del local</b>, no sobre el año
	 * del expediente: una ventana de 2024 son las licencias de los locales dados de alta en 2024, que no son las
	 * licencias de 2024 (ADR-019 §5). La serie por año de licencia está en la API de {@code urban}.
	 */
	URBAN_LICENCES("urban.licences", "urban", Unit.LICENCES, "created_at", "alta del local, no el año de licencia");

	/** Qué cuenta cada medida. Va en la respuesta: sin decirlo, dos columnas parecen comparables y no lo son. */
	public enum Unit {
		REQUESTS, PREMISES, LICENCES
	}

	/**
	 * Módulos que el producto tiene pero que <b>no</b> pueden aportar una medida territorial, para poder
	 * rechazarlos diciendo por qué en vez de con un «medida desconocida» (ADR-019 §8).
	 */
	public static final Set<String> MODULES_WITHOUT_TERRITORY = Set.of("spending");

	private final String id;
	private final String module;
	private final Unit unit;
	private final String dateField;
	private final String dateFieldMeaning;

	Measure(String id, String module, Unit unit, String dateField, String dateFieldMeaning) {
		this.id = id;
		this.module = module;
		this.unit = unit;
		this.dateField = dateField;
		this.dateFieldMeaning = dateFieldMeaning;
	}

	/** Identificador público, {@code <módulo>.<medida>}. */
	public String id() {
		return id;
	}

	public String module() {
		return module;
	}

	public Unit unit() {
		return unit;
	}

	/** Campo del origen sobre el que esta medida aplica la ventana. */
	public String dateField() {
		return dateField;
	}

	/** Qué significa ese campo, en palabras, porque el nombre de columna solo no lo dice. */
	public String dateFieldMeaning() {
		return dateFieldMeaning;
	}

	public static Optional<Measure> byId(String id) {
		if (id == null) {
			return Optional.empty();
		}
		String wanted = id.strip().toLowerCase(Locale.ROOT);
		return Arrays.stream(values()).filter(measure -> measure.id.equals(wanted)).findFirst();
	}

	/** El módulo al que apunta un identificador, aunque la medida no exista: lo de antes del primer punto. */
	public static String moduleOf(String id) {
		if (id == null) {
			return "";
		}
		String wanted = id.strip().toLowerCase(Locale.ROOT);
		int dot = wanted.indexOf('.');
		return dot <= 0 ? wanted : wanted.substring(0, dot);
	}

	public static java.util.List<String> ids() {
		return Arrays.stream(values()).map(Measure::id).toList();
	}

}
