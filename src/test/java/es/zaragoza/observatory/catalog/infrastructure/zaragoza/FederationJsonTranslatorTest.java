package es.zaragoza.observatory.catalog.infrastructure.zaragoza;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import es.zaragoza.observatory.catalog.domain.FederatedDataset;
import es.zaragoza.observatory.support.Fixtures;
import tools.jackson.databind.json.JsonMapper;

/** Anti-corruption layer de datos.gob.es sobre las páginas reales grabadas por S1.3 (2026-09-06). */
class FederationJsonTranslatorTest {

	static final Instant SEEN = Instant.parse("2026-09-06T17:20:00Z");

	final FederationJsonTranslator translator = new FederationJsonTranslator(JsonMapper.shared());

	@Test
	void translatesTheRealFirstPage() {
		List<FederatedDataset> page = translator.translate(Fixtures.text("catalog/datos-gob-es-page0.json"), SEEN);

		assertThat(page).hasSize(50);
		assertThat(page.stream().map(FederatedDataset::sourceId).distinct()).hasSize(50);
		assertThat(page.get(0).sourceId()).isEqualTo(218);
		assertThat(page.get(0).url())
				.isEqualTo("https://datos.gob.es/catalogo/l01502973-boletin-economico-ciudad-de-zaragoza-n-2-2-trimestre-2010");
		assertThat(page.get(0).title()).startsWith("Boletín Económico Ciudad de");
		assertThat(page.stream().map(FederatedDataset::sourceId)).contains(1062, 273, 296, 67, 1742);
		assertThat(page).allSatisfy(f -> {
			assertThat(f.url()).startsWith("https://datos.gob.es/catalogo/l01502973-");
			assertThat(f.title()).isNotBlank();
			assertThat(f.firstSeenAt()).isEqualTo(SEEN);
			assertThat(f.lastSeenAt()).isEqualTo(SEEN);
		});
	}

	@Test
	void lastPageAndPageBeyondTheEnd() {
		assertThat(translator.translate(Fixtures.text("catalog/datos-gob-es-page-last.json"), SEEN)).hasSize(169);
		assertThat(translator.translate(Fixtures.text("catalog/datos-gob-es-page-beyond.json"), SEEN)).isEmpty();
	}

	@Test
	void schemaChangesFailLoudly() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> translator.translate("{\"result\":{\"page\":0}}", SEEN))
				.withMessageContaining("result.items");
		assertThatIllegalArgumentException()
				.isThrownBy(() -> translator.translate("{\"result\":{\"items\":[{\"_about\":\"https://datos.gob.es/catalogo/x\","
						+ "\"identifier\":\"https://www.zaragoza.es/otra-cosa/5\"}]}}", SEEN))
				.withMessageContaining("identifier");
		assertThatIllegalArgumentException()
				.isThrownBy(() -> translator.translate("{\"result\":{\"items\":[{\"identifier\":"
						+ "\"https://www.zaragoza.es/web/espacio-de-datos/servicio/catalogo/5\"}]}}", SEEN))
				.withMessageContaining("url");
	}

	@Test
	void toleratesTitleAsStringOrObjectAndTrailingSlash() {
		List<FederatedDataset> page = translator.translate("{\"result\":{\"items\":["
				+ "{\"_about\":\"https://datos.gob.es/catalogo/a\",\"identifier\":\"https://www.zaragoza.es/web/espacio-de-datos/servicio/catalogo/7/\",\"title\":\"Siete\"},"
				+ "{\"_about\":\"https://datos.gob.es/catalogo/b\",\"identifier\":\"https://www.zaragoza.es/web/espacio-de-datos/servicio/catalogo/8\",\"title\":{\"_value\":\"Ocho\"}},"
				+ "{\"_about\":\"https://datos.gob.es/catalogo/c\",\"identifier\":\"https://www.zaragoza.es/web/espacio-de-datos/servicio/catalogo/9\"}]}}", SEEN);

		assertThat(page).extracting(FederatedDataset::sourceId).containsExactly(7, 8, 9);
		assertThat(page).extracting(FederatedDataset::title).containsExactly("Siete", "Ocho", null);
	}

}
