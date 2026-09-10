package es.zaragoza.observatory.spending.infrastructure.web;

import java.util.List;
import java.util.stream.Stream;

/**
 * Advertencias que acompañan a toda respuesta del presupuesto (SPEC.md §1.1, regla 7). No son letra pequeña: las
 * cuatro cifras de esta fuente se parecen mucho y significan cosas distintas.
 */
final class BudgetCaveats {

	/** Lo que hay que saber para leer cualquier cifra del presupuesto. */
	static final List<String> BASE = List.of(
			"Las cuatro cifras no son intercambiables y por eso viajan separadas. creditFinal es lo "
					+ "presupuestado, committed lo comprometido, obligations el gasto ejecutado (la obligación "
					+ "reconocida) y payments lo efectivamente pagado. En la instantánea de agosto de 2026 van de "
					+ "1.094 a 546 millones de euros: quien confunda dos de ellas se equivoca por el doble.",
			"Cada instantánea es una foto acumulada desde enero de su ejercicio, no el gasto de ese mes. Por eso "
					+ "sumar las doce fotos de un año contaría el mismo euro doce veces, y la serie por año toma "
					+ "la última instantánea publicada de cada ejercicio.",
			"El endpoint de origen se llama «gasto corriente» y no lo es: incluye el capítulo 6, inversiones "
					+ "reales, y los capítulos financieros. Es el presupuesto de gastos entero.",
			"La foto de enero de cada año recoge el presupuesto prorrogado o en tramitación, así que tiene "
					+ "bastantes menos partidas que la de diciembre. No es una carga incompleta.",
			"El programa presupuestario no existe en los ejercicios 2010 a 2014 y llega incompleto en otros "
					+ "seis: son 32.618 partidas de 154.508 sin programa. El nulo es el dato, no un fallo de "
					+ "carga, y esas partidas salen en su propio grupo.",
			"Los códigos y los nombres de área, capítulo, programa, órgano y epígrafe son los que publica el "
					+ "origen y no se traducen. Los de los ejercicios antiguos llegan rellenos con espacios y solo "
					+ "se recortan.");

	/** Lo que hay que saber sobre el hueco que deja la carga por lotes. */
	static final List<String> LOADING = List.of(
			"El censo publica 140 instantáneas y el contenido se lee por lotes, así que puede haber "
					+ "instantáneas censadas y todavía sin partidas. readStatus lo dice en cada una y el resumen "
					+ "publica cuántas faltan.");

	/** La ausencia que hay que decir en voz alta, no dejar que se note. */
	static final List<String> NO_TERRITORY = List.of(
			"El gasto presupuestario no se puede repartir por junta con los datos abiertos actuales: esta "
					+ "fuente no publica ninguna localización y no se geocodifica el nombre de una partida para "
					+ "inventarle un sitio (ADR-003, ADR-011). Por eso aquí no hay ni filtro ni eje territorial.");

	/** Lo que hay que decir sobre el nombre de las partidas. */
	static final List<String> REDACTION = List.of(
			"El nombre de cuatro partidas de los cierres de 2006 a 2009 no se republica porque nombra a una "
					+ "persona física: son pensiones «a la viuda de …» heredadas del presupuesto antiguo. Esas "
					+ "filas salen con headingRedacted a true y sin texto; el resto de sus datos está completo, y "
					+ "el resumen publica cuántas son.");

	/** Lo que hay que saber al leer la serie por año. */
	static final List<String> SERIES = List.of(
			"Cada grupo sale de la última instantánea publicada de su ejercicio, y su fecha viaja en el grupo: "
					+ "el último año está a medio ejercicio y no es comparable con uno cerrado.",
			"La serie llega a 2006 porque se calcula desde las instantáneas. Los resúmenes anuales que publica "
					+ "la propia API empiezan en 2015, y coinciden al céntimo con lo que sale de aquí.");

	static List<String> base() {
		return concat(BASE, NO_TERRITORY, REDACTION, LOADING);
	}

	static List<String> aggregation(boolean series) {
		var blocks = Stream.of(BASE, NO_TERRITORY, LOADING).flatMap(List::stream);
		if (series) {
			blocks = Stream.concat(blocks, SERIES.stream());
		}
		return blocks.toList();
	}

	@SafeVarargs
	private static List<String> concat(List<String>... blocks) {
		return Stream.of(blocks).flatMap(List::stream).toList();
	}

	private BudgetCaveats() {
	}

}
