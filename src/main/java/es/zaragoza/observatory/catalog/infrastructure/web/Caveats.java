package es.zaragoza.observatory.catalog.infrastructure.web;

import java.util.List;

/** Advertencias que acompañan a toda respuesta del monitor (SPEC.md §1.1 «trazabilidad», regla 7). */
final class Caveats {

	static final List<String> CATALOG = List.of(
			"La frescura declarada compara dos campos que declara el publicador (modified y accrualPeriodicity) "
					+ "con los umbrales configurados; no observa el dato.",
			"NOT_EVALUABLE agrupa las fichas sin periodicidad evaluable (NEVER, IRREG, P0DT1S o vacía) "
					+ "o sin fecha modified; son la mayoría del catálogo (S0.1).",
			"metadataUpdated (lastUpdated en origen) es la fecha de la ficha de metadatos, no del dato.",
			"La frescura observada mide lo que devuelve una distribución de la ficha, con el método que indica "
					+ "observationMethod: FILE_HEADERS (Last-Modified del fichero descargable, por HEAD), "
					+ "API_MAX_DATE (valor máximo del campo indicado en observationDetail, pedido con sort desc a la "
					+ "API de la sede, más totalCount), API_COUNT (solo totalCount) y WFS_HITS (numberMatched de la "
					+ "capa). No es un juicio sobre el dato (S1.1).",
			"lastUpdated es la marca de modificación del registro en la plataforma de la sede; creationDate, "
					+ "fecha, publicationDate y similares son fechas del registro más reciente, no de modificación. "
					+ "Las fechas sin zona de la sede se interpretan como hora de Zaragoza.",
			"Cada ficha se observa una vez al día con la primera distribución que responde (API, ficheros, WFS). "
					+ "Los intentos fallidos quedan en observationError con la URL probada (servicios inexistentes, "
					+ "redirecciones a páginas web, servicios de intranet). NOT_OBSERVABLE marca las fichas sin "
					+ "distribución observable (WMS, SPARQL, buscadores, HTML o sin distribuciones).",
			"Las fechas del catálogo se sirven como hora local de Zaragoza sin zona, tal como las publica la fuente.");

	private Caveats() {
	}

}
