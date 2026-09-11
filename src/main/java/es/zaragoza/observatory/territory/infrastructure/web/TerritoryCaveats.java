package es.zaragoza.observatory.territory.infrastructure.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import es.zaragoza.observatory.territory.domain.CrossTab;
import es.zaragoza.observatory.territory.domain.CrossTab.MeasureColumn;
import es.zaragoza.observatory.territory.domain.Denominator;
import es.zaragoza.observatory.territory.domain.Measure;

/**
 * Advertencias que acompañan a todo cruce (regla 7, ADR-019). Aquí no son un adorno legal: son lo que impide
 * leer dos columnas como si fueran comparables cuando no lo son.
 * <p>
 * Una parte es fija y otra se calcula con lo que ha salido en la respuesta —la cobertura peor, los años de
 * padrón que faltan—, porque una advertencia genérica se ignora y una que dice la cifra no.
 */
final class TerritoryCaveats {

	private TerritoryCaveats() {
	}

	static List<String> of(CrossTab tab) {
		var caveats = new ArrayList<String>();

		caveats.add("Las filas son las 29 juntas municipales y vecinales, que es la única unidad territorial que "
				+ "publica el dato abierto de Zaragoza: no hay barrios (S0.4) y las secciones censales no están "
				+ "implementadas. Los 29 polígonos no son una partición: se solapan en el entorno de Juslibol, y "
				+ "un registro que caiga en dos se cuenta en la de menor id y queda marcado (ADR-011).");

		caveats.add("La suma de las 29 filas de una columna no es el total de esa medida. Solo se puede situar en "
				+ "una junta lo que trae punto, y no se geocodifica ninguna dirección para rellenar el hueco "
				+ "(ADR-011 §2): lo que falta está en la cobertura de cada medida, como assigned y unassigned.");

		caveats.add(coverageWarning(tab));

		caveats.add("La ventana se aplica en cada medida sobre la fecha que declara en dateField, y no es la misma "
				+ "en todas: en urban es el alta del local, no el año del expediente de licencia, así que una "
				+ "ventana de un año son las licencias de los locales dados de alta ese año.");

		caveats.add("Cada medida cuenta su propia unidad (unit) y ninguna es intercambiable con otra: un local con "
				+ "doce licencias es un local y son doce licencias.");

		caveats.add("El producto no divide una medida por otra ni publica ningún cociente entre columnas. Las "
				+ "columnas van completas para que quien lea haga la división sabiendo que está cruzando unidades, "
				+ "ventanas y coberturas distintas.");

		caveats.add("Nada está ajustado por cobertura. Un ajuste supondría que lo que no se pudo situar se reparte "
				+ "por juntas igual que lo que sí, y eso no está comprobado en ninguna de las dos fuentes.");

		if (tab.denominator() == Denominator.POPULATION) {
			caveats.add(populationWarning(tab));
		}
		else {
			caveats.add("Se ha pedido sin denominador: las cifras son absolutas y no están normalizadas por "
					+ "población. Comparar dos juntas de tamaño distinto con cifras absolutas compara también su "
					+ "tamaño.");
		}

		caveats.add("El gasto público no aparece aquí y no es un olvido: ninguna de las tres fuentes de spending "
				+ "—contratación, presupuesto y subvenciones— tiene dimensión territorial en el dato publicado "
				+ "(S0.2, S0.6, S3.1). Está en /api/v1/spending, sin junta.");

		caveats.add("ingestedAt de cada medida es cuándo se leyó su origen por última vez, no cuándo cambió el "
				+ "origen. Lo segundo lo mide el eje observado del catálogo (/api/v1/catalog) y todavía no viaja "
				+ "pegado al cruce (ADR-019 §10).");

		return List.copyOf(caveats);
	}

	/** La cobertura peor de la respuesta, con su cifra: es la que limita lo que la tabla entera puede decir. */
	private static String coverageWarning(CrossTab tab) {
		MeasureColumn worst = null;
		for (MeasureColumn column : tab.columns()) {
			Double coverage = column.tally().pointCoverage();
			if (coverage == null) {
				continue;
			}
			if (worst == null || coverage < worst.tally().pointCoverage()) {
				worst = column;
			}
		}
		if (worst == null) {
			return "Ninguna de las medidas pedidas tiene registros en esta ventana, así que no hay cobertura que "
					+ "declarar.";
		}
		return "La cobertura no es la misma en todas las columnas y la peor de esta respuesta es %s, con el %.1f %% "
				.formatted(worst.measure().id(), worst.tally().pointCoverage() * 100)
				+ "de sus registros situados. Una diferencia entre dos juntas puede ser una diferencia de "
				+ "cobertura y no de ciudad.";
	}

	/** Qué años de padrón se han usado de verdad, y cuántas juntas se han quedado sin denominador. */
	private static String populationWarning(CrossTab tab) {
		Set<Integer> years = new LinkedHashSet<>();
		long without = 0;
		for (CrossTab.DistrictRow row : tab.rows()) {
			if (row.populationYear() == null) {
				without++;
			}
			else {
				years.add(row.populationYear());
			}
		}
		String base = "El denominador es el padrón por junta, que el ayuntamiento publica en 2020, 2021, 2022 y "
				+ "2024, sin 2023: la serie no es continua y los años que faltan no se interpolan. ";
		if (years.isEmpty()) {
			return base + "Ninguna junta de esta respuesta tiene padrón para el año pedido, así que ninguna trae "
					+ "tasa.";
		}
		String used = years.size() == 1 ? "El año usado es " + years.iterator().next() + "."
				: "Los años usados no son el mismo en todas las juntas: " + years.stream().sorted()
						.map(String::valueOf).toList() + ", y cada fila dice el suyo en populationYear.";
		if (without > 0) {
			used += " " + without + (without == 1 ? " junta sale" : " juntas salen")
					+ " sin denominador y sin tasa: el hueco se ve, no se rellena.";
		}
		return base + used;
	}

	/** Por qué no hay medidas de gasto, para el 400 de ADR-019 §8. */
	static String noTerritorialAxis(String module) {
		return "El módulo '" + module + "' no puede aportar una medida territorial: ninguna de sus fuentes publica "
				+ "junta, punto ni ninguna otra localización, y no se geocodifica para inventarla (ADR-011 §2, "
				+ "ADR-019 §8). Sus cifras están en /api/v1/" + module + ", sin dimensión territorial. Medidas "
				+ "disponibles: " + Measure.ids() + ".";
	}

}
