package es.zaragoza.observatory.spending.infrastructure.web;

import java.util.List;
import java.util.stream.Stream;

/**
 * Advertencias que acompañan a toda respuesta de subvenciones (SPEC.md §1.1, regla 7). Aquí son más necesarias
 * que en ninguna otra fuente del producto: el beneficiario es medio dato, y de más de la mitad de las
 * concesiones no se publica quién es.
 */
final class GrantCaveats {

	/** Lo que hay que saber para leer cualquier cifra. */
	static final List<String> BASE = List.of(
			"Ninguna cifra de aquí es dinero pagado. granted es el importe que se acordó conceder, no lo que "
					+ "salió de la caja: el gasto ejecutado y el pago neto están en el presupuesto municipal, en "
					+ "/api/v1/spending/budget.",
			"Los tres importes de una concesión no son intercambiables: requested es lo solicitado, granted lo "
					+ "concedido y annual la anualidad. El presupuesto de una convocatoria es una cuarta cosa, lo "
					+ "que se puso a disposición, y no coincide con la suma de lo que repartió.",
			"El origen no declara moneda en ningún campo. Son euros porque lo es el presupuesto municipal, no "
					+ "porque el dato lo diga.");

	/** La decisión de ADR-018 sobre el beneficiario, dicha entera y donde se lee. */
	static final List<String> BENEFICIARY = List.of(
			"De los beneficiarios que son personas físicas no se publica ni el nombre ni el identificador "
					+ "fiscal: solo el identificador opaco que ya publica el ayuntamiento, que permite contar "
					+ "cuántas subvenciones recibió el mismo beneficiario y por cuánto sin saber quién es. Son el "
					+ "56 % de las concesiones y naturalPerson lo dice en cada una.",
			"Persona física significa aquí que lo dice la clasificación del origen o que el identificador "
					+ "fiscal llega enmascarado. Las dos señales discrepan en el 1,5 % de los casos y se toma la "
					+ "unión: ante la duda no se publica identidad.",
			"El enlace con el beneficiario solo lo publica la versión nueva de la API, que no cubre 2013 ni "
					+ "2014. Esas concesiones salen sin beneficiario y el resumen dice cuántas son. No se deduce "
					+ "por nombre.",
			"Del directorio de beneficiarios no se guarda ningún dato de contacto: ni domicilio, ni teléfono, "
					+ "ni correo, ni web. El origen los publica y una parte son datos personales de quien preside "
					+ "la entidad.");

	/** La redacción del título, con su recuento, como pide la regla 6. */
	static final List<String> REDACTION = List.of(
			"El título de una concesión dice para qué era la ayuda y se publica. En 2.758 de ellas el origen "
					+ "mete dentro el DNI o el NIE del beneficiario: ahí el identificador se sustituye por "
					+ "[identificador omitido], la concesión sale con titleRedacted a true y el resto del texto "
					+ "está completo.");

	/** Lo que el censo es y lo que no. */
	static final List<String> CENSUS = List.of(
			"El censo son las 46.925 concesiones de ayuda-subvencion/resolucion, de 2013 a 2026. El listado de "
					+ "la versión nueva de la API publica 44.316 y no trae ninguna que esta no tenga: es un "
					+ "subconjunto que esconde los dos primeros ejercicios.",
			"Siete concesiones traen una fecha de concesión imposible (años 0002, 0019 y 0022). No se corrigen "
					+ "ni se descartan: salen en su propio grupo y el resumen dice cuántas son.");

	/** La ausencia que hay que decir en voz alta, no dejar que se note. */
	static final List<String> NO_TERRITORY = List.of(
			"Las subvenciones no se pueden repartir por junta con los datos abiertos actuales: esta fuente no "
					+ "publica ninguna localización y no se geocodifica una dirección para inventarle un sitio "
					+ "(ADR-003, ADR-011). Por eso aquí no hay ni filtro ni eje territorial.");

	/** Lo que hay que saber al leer una agregación. */
	static final List<String> AGGREGATION = List.of(
			"Cada grupo publica cuántas de sus concesiones van a una persona física y a cuántos beneficiarios "
					+ "distintos llega. El segundo dato es el denominador que convierte un total en una "
					+ "concentración: el mismo importe repartido entre cuarenta beneficiarios y entre cuatro mil "
					+ "no es lo mismo.",
			"En el eje por beneficiario la etiqueta del grupo es nula siempre que sea una persona física. El "
					+ "grupo se cuenta, no se nombra.");

	static List<String> base() {
		return concat(BASE, BENEFICIARY, REDACTION, CENSUS, NO_TERRITORY);
	}

	static List<String> aggregation() {
		return concat(BASE, BENEFICIARY, CENSUS, NO_TERRITORY, AGGREGATION);
	}

	@SafeVarargs
	private static List<String> concat(List<String>... blocks) {
		return Stream.of(blocks).flatMap(List::stream).toList();
	}

	private GrantCaveats() {
	}

}
