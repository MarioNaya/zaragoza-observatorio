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
			"Las fechas del catálogo se sirven como hora local de Zaragoza sin zona, tal como las publica la fuente.",
			"apiEndpoints son las operaciones que el Swagger de la API municipal documenta bajo el apiTag de la ficha "
					+ "(inventario ingerido por separado, con su propio ingestedAt); tagDocumented indica si ese tag "
					+ "existe en el Swagger. Que un endpoint esté documentado no significa que responda (S1.2).");

	static final List<String> API_INVENTORY = List.of(
			"El inventario es el Swagger 2.0 de la API municipal (sede/servicio/catalogo/api.json), ingerido a diario "
					+ "y sincronizado entero: tag, method, path y summary tal como los documenta; url = scheme://host + "
					+ "basePath + path del propio documento. El documento no publica Last-Modified ni ETag; "
					+ "firstSeenAt y lastSeenAt son marcas de este observatorio.",
			"El cruce con el catálogo se hace por el tag que declara la distribución application/api de cada ficha "
					+ "(accessURL con #/<tag>). Un tag con endpoints = 0 es un tag que el catálogo declara y el Swagger "
					+ "no documenta; un tag sin datasets está documentado pero no tiene ficha en el catálogo (S0.1, S1.2).",
			"Que un endpoint esté documentado no significa que responda ni que sea el dato de la ficha: el eje "
					+ "observado mide el endpoint que la ficha declara en downloadURL y, si falla, solo el path "
					+ "documentado <declarado>/list de su tag (S1.2).");

	private Caveats() {
	}

}
