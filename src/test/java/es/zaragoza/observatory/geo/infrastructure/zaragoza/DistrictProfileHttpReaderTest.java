package es.zaragoza.observatory.geo.infrastructure.zaragoza;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import es.zaragoza.observatory.geo.domain.DistrictProfile;
import es.zaragoza.observatory.geo.domain.PopulationRecord;
import es.zaragoza.observatory.geo.infrastructure.GeoProperties;
import es.zaragoza.observatory.support.Fixtures;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lectura del detalle de una junta sobre la respuesta real de El Rabal grabada por S2.1
 * ({@code distrito/6.json?fl=id,title,indicadores}): de ahí salen el {@code idpadron} y la serie de padrón.
 */
class DistrictProfileHttpReaderTest {

	static final Instant NOW = Instant.parse("2026-09-08T09:47:00Z");

	final GeoProperties properties = new GeoProperties(
			java.net.URI.create("https://www.zaragoza.es/sede/servicio/distrito.json"),
			"https://www.zaragoza.es/sede/servicio/distrito/{id}.json", Duration.ofDays(1), Duration.ZERO);

	// El cliente no se usa: estas pruebas ejercen la traducción de la respuesta, no la petición.
	final DistrictProfileHttpReader reader = new DistrictProfileHttpReader(RestClient.create(), JsonMapper.shared(),
			properties, Clock.fixed(NOW, ZoneOffset.UTC));

	DistrictProfile rabal() {
		return reader.parse(6, Fixtures.text("geo/distrito-6-indicadores.json"), NOW);
	}

	@Test
	void readsTheSecondNumberingFromTheIndicators() {
		DistrictProfile profile = rabal();

		// distrito.id 6 (El Rabal) tiene idpadron 15: son dos numeraciones distintas (S2.1).
		assertThat(profile.districtId()).isEqualTo(6);
		assertThat(profile.padronId()).isEqualTo(15);
	}

	@Test
	void readsTheCensusSeriesNewestFirstAndWithoutTwentyTwentyThree() {
		List<PopulationRecord> population = rabal().population();

		assertThat(population.stream().map(PopulationRecord::year)).containsExactly(2024, 2022, 2021, 2020);
		assertThat(population.stream().map(PopulationRecord::year)).as("la serie no es continua (S2.1)")
				.doesNotContain(2023);
		PopulationRecord latest = population.get(0);
		assertThat(latest.total()).isEqualTo(78438);
		assertThat(latest.spaniards()).isNotNull();
		assertThat(latest.foreigners()).isNotNull();
		assertThat(latest.households()).isNotNull();
		assertThat(latest.areaKm2()).isEqualTo(8.400154);
		assertThat(latest.densityPerKm2()).isCloseTo(9337.7, org.assertj.core.data.Offset.offset(1.0));
		assertThat(latest.ingestedAt()).isEqualTo(NOW);
	}

	@Test
	void acceptsADistrictWithoutIndicators() {
		DistrictProfile profile = reader.parse(26, "{\"id\":26,\"title\":\"Junta Vecinal Torrecilla\"}", NOW);

		assertThat(profile.padronId()).isNull();
		assertThat(profile.population()).isEmpty();
	}

	@Test
	void failsIfTheOriginChangesTheNumbering() {
		String body = """
				{"id":6,"indicadores":[{"anyo":"2024","iddatosab":99,"idpadron":15,"totpob":1}]}""";

		assertThatThrownBy(() -> reader.parse(6, body, NOW)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("numbering changed in origin");
	}

	@Test
	void failsIfTheSameDistrictReportsSeveralPadronIds() {
		String body = """
				{"id":6,"indicadores":[{"anyo":"2024","idpadron":15,"totpob":1},
				{"anyo":"2022","idpadron":16,"totpob":1}]}""";

		assertThatThrownBy(() -> reader.parse(6, body, NOW)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("several idpadron");
	}

}
