package es.zaragoza.observatory.citizen.infrastructure.web;

import java.util.List;
import java.util.stream.Stream;

/**
 * Advertencias que acompañan a toda respuesta de {@code citizen} (SPEC.md §1.1, regla 7). No son letra pequeña:
 * son la diferencia entre publicar un dato y publicar una conclusión falsa con aspecto de dato.
 */
final class CitizenCaveats {

	/** Lo que hay que saber para leer cualquier recuento de esta fuente. */
	static final List<String> BASE = List.of(
			"El listado abierto no son todas las quejas. Tiene 89.432 registros desde 2013-01-08, mientras que las "
					+ "estadísticas municipales cuentan del orden de 40.000 incidencias cerradas al año: es un "
					+ "subconjunto y no se conoce el criterio con el que se publica (S0.3, S2.2).",
			"El texto de la queja no está aquí ni se ha llegado a descargar: el origen lo publica sin anonimizar y "
					+ "la ingesta no lo pide (ADR-012). Lo que se publica de cada registro es su identificador, "
					+ "estado, categoría, fechas y junta.",
			"La categoría es la del origen (unas 100 en services.json). Los servicios INTERNAL no son quejas "
					+ "ciudadanas y siguen contando: se pueden filtrar por su código, no se excluyen en silencio.");

	/** Lo que hay que saber además para leer cualquier cifra por junta. */
	static final List<String> TERRITORIAL = List.of(
			"La junta se resuelve con ST_Contains sobre la geometría oficial, nunca preguntando a la API municipal "
					+ "ni geocodificando la dirección (ADR-011). Un registro sin punto se queda sin junta y se "
					+ "cuenta como tal en assignment; no se reparte ni se estima.",
			"La cobertura de punto es baja y muy desigual entre años: del 16 % al 45 % según el año en la muestra "
					+ "de S2.2. Dos años distintos no son comparables sin mirar su cobertura, que viaja al lado de "
					+ "cada cifra.",
			"districtDeclared es el nombre de junta que trae el origen, tal cual, y nunca sustituye al resuelto: "
					+ "está para poder medir la discrepancia entre ambos, que es información sobre la calidad de la "
					+ "fuente (ADR-011 §3).",
			"assignment = AMBIGUOUS significa que el punto cae en más de una junta porque los polígonos publicados "
					+ "se solapan en el entorno de Juslibol (S2.1). Se toma la de menor id y se marca.");

	/** Lo que hay que saber además para leer un tiempo de respuesta. */
	static final List<String> RESPONSE_TIME = List.of(
			"El tiempo de respuesta se calcula solo sobre las cerradas, con updated_datetime como fecha de cierre "
					+ "(el origen la informa al cerrar, S0.3). Las abiertas no se estiman: tienen censura por la "
					+ "derecha y una media que las ignore sin decirlo engaña. Cada grupo trae cuántas cerradas "
					+ "sostienen su mediana.");

	static List<String> territorial() {
		return concat(BASE, TERRITORIAL);
	}

	static List<String> aggregations() {
		return concat(BASE, TERRITORIAL, RESPONSE_TIME);
	}

	@SafeVarargs
	private static List<String> concat(List<String>... blocks) {
		return Stream.of(blocks).flatMap(List::stream).toList();
	}

	private CitizenCaveats() {
	}

}
