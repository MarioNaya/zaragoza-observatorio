package es.zaragoza.observatory.urban.infrastructure.web;

import java.util.List;
import java.util.stream.Stream;

/**
 * Advertencias que acompañan a toda respuesta de {@code urban} (SPEC.md §1.1, regla 7). No son letra pequeña:
 * son la diferencia entre publicar un dato y publicar una conclusión falsa con aspecto de dato.
 */
final class UrbanCaveats {

	/** Lo que hay que saber para leer cualquier recuento de esta fuente. */
	static final List<String> BASE = List.of(
			"Esto es un registro de licencias concedidas, no un censo de negocios abiertos. Ninguna cifra de aquí "
					+ "dice cuántos locales están en funcionamiento: el campo estado del origen no tiene taxonomía "
					+ "publicada y la fecha de baja solo aparece en 169 de 42.342 locales (S2.4).",
			"El estado del local se publica como el código que trae el origen (0, 1, 2 o 3) y sin traducir, porque "
					+ "no hay ninguna taxonomía que lo describa: ni el Swagger de la API ni la ficha del catálogo. "
					+ "Ponerle nombre sería inventarla (ADR-016 §6).",
			"La actividad es el epígrafe IAE que publica el origen, de una taxonomía cerrada de 965 entradas. El "
					+ "texto libre con el que la fuente también la describe no está aquí ni se guarda: decía lo "
					+ "mismo y contenía datos personales (ADR-016 §3).",
			"Un local con doce licencias es un local y son doce licencias. Cada agregación dice en unit qué está "
					+ "contando, y las dos cifras viajan juntas en cada grupo para que no haya que adivinarlo.",
			"El código de zona saturada es el del origen. La API publica un catálogo de 15 zonas, pero los locales "
					+ "usan 17 códigos: O y P no aparecen en ese catálogo (S2.4 §9).");

	/** Lo que hay que saber además para leer cualquier cifra por junta. */
	static final List<String> TERRITORIAL = List.of(
			"La junta se resuelve con ST_Contains sobre la geometría oficial, nunca preguntando a la API municipal "
					+ "ni geocodificando la dirección (ADR-011). Un local sin punto se queda sin junta y se cuenta "
					+ "como tal en assignment; no se reparte ni se estima.",
			"El 89,4 % de los locales trae punto y el 10,6 % no (4.499 de 42.342). Esa cobertura no está medida "
					+ "por año, así que una serie temporal por junta hay que leerla con coverageByYear al lado.",
			"Esta fuente no declara junta por ninguna vía: ni el local ni el recurso de portales del que cuelga "
					+ "(S2.4 §6). Por eso aquí no hay districtDeclared ni contraste entre lo resuelto y lo "
					+ "declarado, al contrario que en las quejas. No se fabrica cruzando el código de portal contra "
					+ "el callejero: sería resolver por dirección con otro nombre, y el callejero acierta el 89,5 % "
					+ "sin permitir distinguir el acierto del fallo (S2.1).",
			"assignment = AMBIGUOUS significa que el punto cae en más de una junta porque los polígonos publicados "
					+ "se solapan en el entorno de Juslibol (S2.1). En esta fuente no se ha observado ninguno, y 16 "
					+ "locales tienen punto y caen fuera de las 29 juntas.");

	/** Lo que hay que saber además para leer una serie por año de licencia. */
	static final List<String> SERIES = List.of(
			"El año es el del expediente de la licencia, no el de alta del registro informático. El rango que "
					+ "publica el origen va de 1913 a 2033: hay una licencia fechada en el futuro y se publica tal "
					+ "cual, porque corregirla sería inventarse el dato.",
			"La cobertura que importa para comparar años está en coverageByYear, no en los grupos. Dentro de un "
					+ "grupo por junta la cobertura es siempre del 100 % por construcción: sin punto no hay junta, "
					+ "así que los locales que no se pudieron situar no aparecen en ningún grupo.",
			"Las cifras no se ajustan por cobertura, ni aquí ni en ninguna parte: un ajuste supondría que lo no "
					+ "geolocalizado se reparte igual que lo geolocalizado, y eso no está comprobado (ADR-015).",
			"El padrón que acompaña a cada grupo es el de su propio año. La serie del padrón por junta solo tiene "
					+ "2020, 2021, 2022 y 2024 (falta 2023, S2.1), así que los demás años salen sin denominador: el "
					+ "hueco se ve, no se rellena interpolando.",
			"Una licencia concedida en un año no dice que la actividad empezara ese año ni que siga. Contar "
					+ "licencias por año es contar expedientes resueltos, y varias licencias pueden recaer sobre el "
					+ "mismo local: la media es de 1,64 y el máximo observado, 12.");

	static List<String> base() {
		return BASE;
	}

	static List<String> territorial() {
		return concat(BASE, TERRITORIAL);
	}

	static List<String> series() {
		return concat(BASE, TERRITORIAL, SERIES);
	}

	@SafeVarargs
	private static List<String> concat(List<String>... blocks) {
		return Stream.of(blocks).flatMap(List::stream).toList();
	}

	private UrbanCaveats() {
	}

}
