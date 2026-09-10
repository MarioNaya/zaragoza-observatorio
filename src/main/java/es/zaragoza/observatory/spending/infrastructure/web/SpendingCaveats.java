package es.zaragoza.observatory.spending.infrastructure.web;

import java.util.List;
import java.util.stream.Stream;

/**
 * Advertencias que acompañan a toda respuesta de {@code spending} (SPEC.md §1.1, regla 7). No son letra pequeña:
 * en esta fuente, un recuento leído sin ellas dice cosas falsas con aspecto de dato.
 */
final class SpendingCaveats {

	/** Lo que hay que saber para leer cualquier cifra de contratación. */
	static final List<String> BASE = List.of(
			"Ninguna cifra de aquí es dinero pagado. OCDS no publica ni previsión ni ejecución: el campo de "
					+ "planificación aparece en 0 documentos y el de ejecución del contrato, en 0. Lo que hay es "
					+ "importe licitado e importe adjudicado, y viajan siempre por separado: el histórico son "
					+ "4.359 millones licitados frente a 1.819 adjudicados. El gasto ejecutado sale del "
					+ "presupuesto municipal, no de aquí.",
			"El listado que documenta la API publica 5.730 procesos de los 8.001 que existen. Los otros 2.271 "
					+ "solo aparecen mandando el parámetro after, que no es una fecha sino un interruptor: por "
					+ "debajo de un umbral de enero de 2017 no hace nada y por encima el valor da igual. El "
					+ "observatorio enumera con el interruptor puesto y publica en inDocumentedList si cada "
					+ "proceso aparece o no en el listado documentado (S3.1 §1).",
			"El 29,7 % de los procesos no tiene todavía release publicado (2.379 de 8.001) y son los expedientes "
					+ "recientes: la cobertura va del 100 % en los más antiguos al 8 % en el último decil. De esos "
					+ "procesos se conoce el identificador y nada más, así que no tienen objeto, ni importe, ni "
					+ "adjudicataria. Salen en releaseStatus y se siguen reintentando.",
			"La etapa solo se publica donde el documento la sostiene. 1.560 procesos completos tienen un contrato "
					+ "que es una cáscara con identificador y nada más, sin fecha de firma, así que se quedan sin "
					+ "etapa: ponerles «comprometido» por deducción sería una conclusión de este observatorio, no "
					+ "un dato del ayuntamiento (ADR-017 §6).",
			"Los códigos de estado, procedimiento y categoría son los que publica el origen y no se traducen. Del "
					+ "adjudicatario se guarda el NIF cuando es de persona jurídica; de una persona física no se "
					+ "guarda ni NIF ni nombre, y sus adjudicaciones siguen contando con su importe (ADR-017 §2).");

	/** Lo que hay que saber además al leer cualquier agregación. */
	static final List<String> AGGREGATION = List.of(
			"Cada agregación declara en unit qué está contando: procesos o adjudicaciones. No son lo mismo y no "
					+ "se suman: 3.410 procesos tienen adjudicación y 195 tienen más de una.",
			"Los procesos sin release entran en los grupos y aportan 0 al importe, porque existen. withRelease "
					+ "dice cuántos de cada grupo se han podido leer de verdad.");

	/** Lo que hay que saber cuando un registro puede caer en más de un grupo. */
	static final List<String> OVERLAPPING = List.of(
			"En este eje la suma de los grupos NO es el total: un mismo registro cae en varios grupos. Un proceso "
					+ "con dos códigos CPV cuenta en los dos, y una adjudicación con dos adjudicatarias cuenta "
					+ "entera en cada una.",
			"Solo el 42,7 % de los procesos publica algún código CPV, así que un reparto por CPV describe esa "
					+ "parte del histórico y no el conjunto.");

	/** Lo que hay que saber al leer cualquier serie por año. */
	static final List<String> SERIES = List.of(
			"El año es el de publicación del release, no el de firma del contrato ni el de ejecución del gasto.",
			"Los últimos años están incompletos por construcción: son los que más procesos tienen sin release "
					+ "publicado. Comparar 2026 con 2015 sin mirar withRelease compara dos cosas distintas.");

	/** La ausencia que hay que decir en voz alta, no dejar que se note. */
	static final List<String> NO_TERRITORY = List.of(
			"El gasto público municipal no se puede repartir por junta con los datos abiertos actuales. Esta "
					+ "fuente no publica ninguna localización —cero campos de lugar en 184 caminos distintos y "
					+ "5.622 documentos— y no se geocodifica el título de un contrato para inventarle un sitio "
					+ "(ADR-003, ADR-011 §2). Por eso aquí no hay ni filtro ni eje territorial.");

	static List<String> base() {
		return concat(BASE, NO_TERRITORY);
	}

	static List<String> aggregation(boolean overlapping, boolean series) {
		var blocks = Stream.of(BASE, NO_TERRITORY, AGGREGATION).flatMap(List::stream);
		if (overlapping) {
			blocks = Stream.concat(blocks, OVERLAPPING.stream());
		}
		if (series) {
			blocks = Stream.concat(blocks, SERIES.stream());
		}
		return blocks.toList();
	}

	@SafeVarargs
	private static List<String> concat(List<String>... blocks) {
		return Stream.of(blocks).flatMap(List::stream).toList();
	}

	private SpendingCaveats() {
	}

}
