package es.zaragoza.observatory.geo.infrastructure.web;

import java.util.List;

/** Advertencias que acompañan a toda respuesta territorial (SPEC.md §1.1, regla 7). */
final class GeoCaveats {

	static final List<String> DISTRICTS = List.of(
			"La unidad territorial es la junta municipal o vecinal (29). Los barrios no existen como dato abierto: "
					+ "lo que en el habla es un barrio suele ser una junta, y no siempre (S0.4).",
			"Hay dos numeraciones y no coinciden: id es el de la API que sirve las geometrías (distrito.id, "
					+ "iddatosab en los indicadores) y padronId el de los datasets de población (idpadron). Las dos "
					+ "las publica la fuente en distrito/{id}.indicadores (S2.1).",
			"El padrón es el que publica la propia junta por año. La serie tiene 2020, 2021, 2022 y 2024: "
					+ "no hay 2023, así que no es continua y toda normalización debe decir qué año usa (S2.1).",
			"Los recuentos son los de origen. Los índices que la fuente trae ya calculados (envejecimiento, "
					+ "dependencia, feminidad, natalidad) no se copian: son lecturas, y las hace quien consulta.",
			"Las juntas vecinales tienen poblaciones muy pequeñas (Torrecilla de Valmadrid, 20 habitantes en 2024): "
					+ "cualquier tasa por habitante es inestable ahí. El denominador viaja siempre al lado.");

	static final List<String> LOCATE = List.of(
			"La junta se resuelve con ST_Contains sobre la geometría oficial de las 29 juntas, no preguntando a la "
					+ "API municipal: su buscador de direcciones acierta la junta el 89,5 % de las veces sin permitir "
					+ "distinguir el acierto del fallo, y sus parámetros point/distance no filtran por proximidad "
					+ "(S2.1, ADR-011). Contrastada con la junta oficial de locales-vacios: 99,69 % de acuerdo.",
			"status = AMBIGUOUS significa que el punto cae en más de una junta: los polígonos publicados se solapan "
					+ "en el entorno de Juslibol. Se devuelve la de menor id y todas las candidatas (S2.1).",
			"status = OUTSIDE significa que el punto no cae en ninguna junta. Un punto exactamente sobre el borde "
					+ "también queda fuera: ST_Contains excluye la frontera.",
			"Un registro sin punto no llega a resolverse y se cuenta como sin asignar: no se geocodifica por "
					+ "dirección para rellenar el hueco (ADR-011).");

	private GeoCaveats() {
	}

}
