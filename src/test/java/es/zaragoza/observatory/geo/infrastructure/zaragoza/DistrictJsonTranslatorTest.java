package es.zaragoza.observatory.geo.infrastructure.zaragoza;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import es.zaragoza.observatory.geo.domain.District;
import es.zaragoza.observatory.geo.domain.DistrictKind;
import es.zaragoza.observatory.support.Fixtures;
import tools.jackson.databind.json.JsonMapper;

/**
 * Traducción de la capa base territorial sobre la respuesta real grabada por S2.1
 * ({@code distrito.json?srsname=wgs84}). Las cifras son las del spike: si la fuente cambia, esto se entera.
 */
class DistrictJsonTranslatorTest {

	static final Instant SEEN_AT = Instant.parse("2026-09-08T09:47:00Z");

	final DistrictJsonTranslator translator = new DistrictJsonTranslator(JsonMapper.shared());

	List<District> districts() {
		return translator.translate(Fixtures.text("geo/distrito.json_srsname-wgs84_rows-100"), SEEN_AT);
	}

	@Test
	void translatesTheTwentyNineDistrictsWithTheirGeometry() {
		List<District> districts = districts();

		assertThat(districts).hasSize(29);
		assertThat(districts).allSatisfy(district -> {
			assertThat(district.boundary()).isNotNull();
			assertThat(district.boundary().polygons()).isNotEmpty();
			assertThat(district.padronId()).as("el listado no trae idpadron (S2.1)").isNull();
			assertThat(district.firstSeenAt()).isEqualTo(SEEN_AT);
		});
		assertThat(districts.stream().filter(d -> d.kind() == DistrictKind.MUNICIPAL)).hasSize(15);
		assertThat(districts.stream().filter(d -> d.kind() == DistrictKind.VECINAL)).hasSize(14);
		assertThat(districts.stream().mapToInt(d -> d.boundary().vertices()).sum()).isEqualTo(16462);
	}

	@Test
	void keepsTheOfficialTitleAndDerivesTheShortName() {
		District rabal = districts().stream().filter(d -> d.id() == 6).findFirst().orElseThrow();

		assertThat(rabal.name()).isEqualTo("Junta Municipal El Rabal");
		assertThat(rabal.shortName()).isEqualTo("El Rabal");
		assertThat(rabal.kind()).isEqualTo(DistrictKind.MUNICIPAL);
		assertThat(rabal.boundary().polygons()).hasSize(1);
		assertThat(rabal.boundary().polygons().get(0).holes()).isEmpty();
		assertThat(rabal.boundary().vertices()).isEqualTo(93);
	}

	@Test
	void readsCoordinatesAsLongitudeThenLatitude() {
		District actur = districts().stream().filter(d -> d.id() == 1).findFirst().orElseThrow();
		var first = actur.boundary().polygons().get(0).exterior().points().get(0);

		// Zaragoza: longitud negativa próxima a -0,87 y latitud próxima a 41,69 (S2.1).
		assertThat(first.lon()).isCloseTo(-0.8699545, org.assertj.core.data.Offset.offset(1e-6));
		assertThat(first.lat()).isCloseTo(41.6937773, org.assertj.core.data.Offset.offset(1e-6));
	}

	@Test
	void rejectsATitleWithoutAKnownPrefix() {
		assertThatThrownBy(() -> DistrictKind.fromTitle("Barrio de Delicias"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Junta Municipal");
	}

	@Test
	void rejectsADistrictWithoutGeometry() {
		String body = """
				{"totalCount":1,"result":[{"id":1,"title":"Junta Municipal Actur-Rey Fernando"}]}""";

		assertThatThrownBy(() -> translator.translate(body, SEEN_AT))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("has no geometry");
	}

	@Test
	void rejectsAnUnsupportedGeometryType() {
		String body = """
				{"totalCount":1,"result":[{"id":1,"title":"Junta Vecinal Alfocea",
				"geometry":{"type":"Point","coordinates":[-0.9,41.6]}}]}""";

		assertThatThrownBy(() -> translator.translate(body, SEEN_AT))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("unsupported geometry type");
	}

}
