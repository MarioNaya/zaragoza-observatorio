package es.zaragoza.observatory.geo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * La tabla de sinónimos de ADR-011 §5, con los cuatro casos que S2.2 observó de verdad en
 * {@code quejas-sugerencias/list.json}. Si alguien añade un sinónimo sin evidencia, este test no lo impide; lo
 * que sí fija es que los cuatro conocidos casan y que la clave normalizada resuelve el resto sola.
 */
class DistrictNamesTest {

	/** Los 29 nombres oficiales tal como los publica {@code distrito.json} (fixture de S2.1). */
	static final Map<Integer, String> OFFICIAL = official();

	static Map<Integer, String> official() {
		var names = new LinkedHashMap<Integer, String>();
		names.put(1, "Junta Municipal Actur-Rey Fernando");
		names.put(2, "Junta Municipal La Almozara");
		names.put(3, "Junta Municipal Casco Histórico");
		names.put(4, "Junta Municipal Centro");
		names.put(5, "Junta Municipal Delicias");
		names.put(6, "Junta Municipal El Rabal");
		names.put(7, "Junta Municipal Las Fuentes");
		names.put(8, "Junta Municipal Oliver-Valdefierro");
		names.put(9, "Junta Municipal San José");
		names.put(10, "Junta Municipal Torrero");
		names.put(11, "Junta Municipal Universidad");
		names.put(12, "Junta Municipal Casablanca");
		names.put(13, "Junta Municipal Santa Isabel");
		names.put(14, "Junta Vecinal Alfocea");
		names.put(15, "Junta Vecinal La Cartuja Baja");
		names.put(16, "Junta Vecinal Casetas");
		names.put(17, "Junta Vecinal Garrapinillos");
		names.put(18, "Junta Vecinal Juslibol");
		names.put(19, "Junta Municipal Miralbueno");
		names.put(20, "Junta Vecinal Montañana");
		names.put(21, "Junta Vecinal Monzalbarba");
		names.put(22, "Junta Vecinal Movera");
		names.put(23, "Junta Vecinal Peñaflor");
		names.put(24, "Junta Vecinal San Gregorio");
		names.put(25, "Junta Vecinal San Juan Mozarrifar");
		names.put(26, "Junta Vecinal Torrecilla de Valmadrid");
		names.put(27, "Junta Vecinal Venta del Olivar");
		names.put(29, "Junta Vecinal Villarrapa");
		names.put(30, "Junta Municipal Sur");
		return names;
	}

	final DistrictNames names = DistrictNames.of(OFFICIAL);

	@ParameterizedTest(name = "\"{0}\" -> junta {1}")
	@CsvSource({
			// Casan por clave normalizada: mayúsculas, sin tilde, sin prefijo, sin artículo.
			"DELICIAS, 5", "EL RABAL, 6", "ALMOZARA, 2", "LAS FUENTES, 7", "SAN JOSÉ, 9", "CASCO HISTÓRICO, 3",
			"MONTAÑANA, 20", "OLIVER VALDEFIERRO, 8", "ACTUR REY FERNANDO, 1", "CARTUJA BAJA, 15",
			"Junta Vecinal Villarrapa, 29", "VENTA DEL OLIVAR, 27",
			// Los cuatro sinónimos observados en S2.2 §7.
			"DISTRITO SUR, 30", "SAN JUAN DE MOZARRIFAR, 25", "TORRECILLA, 26" })
	void resolvesObservedNames(String declared, int expected) {
		assertThat(names.resolve(declared)).contains(expected);
	}

	/**
	 * La variante con el carácter de control U+0093 incrustado que publica el origen: la normalización lo
	 * convierte en espacio y el sinónimo la recoge. Va aparte porque no cabe en un {@code @CsvSource}.
	 */
	@Test
	void resolvesTheNameWithAnEmbeddedControlCharacter() {
		assertThat(names.resolve("CASCO HIST\u00d3\u0093RICO")).contains(3);
		assertThat(DistrictNames.normalize("CASCO HIST\u00d3\u0093RICO")).isEqualTo("CASCO HISTO RICO");
	}

	@Test
	void doesNotGuess() {
		assertThat(names.resolve("DELICIAS NORTE")).isEmpty();
		assertThat(names.resolve("BARRIO INVENTADO")).isEmpty();
		assertThat(names.resolve("")).isEmpty();
		assertThat(names.resolve(null)).isEmpty();
	}

	@Test
	void anOfficialNameAlwaysWinsOverASynonym() {
		// Si una junta se llamase igual que un sinónimo, manda la oficial: el sinónimo solo rellena huecos.
		var renamed = new LinkedHashMap<>(OFFICIAL);
		renamed.put(4, "Junta Municipal Distrito Sur");
		assertThat(DistrictNames.of(renamed).resolve("DISTRITO SUR")).contains(4);
	}

	@Test
	void normalizesConsistently() {
		assertThat(DistrictNames.normalize("Junta Municipal El Rabal")).isEqualTo("RABAL");
		assertThat(DistrictNames.normalize("  la  almozara ")).isEqualTo("ALMOZARA");
		assertThat(DistrictNames.normalize("Oliver-Valdefierro")).isEqualTo("OLIVER VALDEFIERRO");
		assertThat(DistrictNames.normalize(null)).isEmpty();
	}

}
