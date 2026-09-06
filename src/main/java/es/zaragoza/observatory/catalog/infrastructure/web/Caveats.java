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
			"La frescura observada (muestreo de distribuciones) no está disponible todavía: observedLastChange, "
					+ "observedRecords y observationMethod llegan vacíos.",
			"Las fechas del catálogo se sirven como hora local de Zaragoza sin zona, tal como las publica la fuente.");

	private Caveats() {
	}

}
