package es.zaragoza.observatory.catalog.infrastructure.zaragoza;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import es.zaragoza.observatory.catalog.domain.ApiEndpoint;
import es.zaragoza.observatory.support.Fixtures;
import tools.jackson.databind.json.JsonMapper;

/**
 * Anti-corruption layer del Swagger sobre el documento real ({@code swagger-api.json}, idéntico el 2026-09-05 y
 * el 2026-09-06). Los recuentos son los de S1.2 (docs/spikes/S1.2-inventario-api.md).
 */
class SwaggerJsonTranslatorTest {

	static final Instant SEEN = Instant.parse("2026-09-06T16:37:00Z");

	final SwaggerJsonTranslator translator = new SwaggerJsonTranslator(JsonMapper.shared());

	@Test
	void translatesTheRealSwaggerWithTheCountsMeasuredInS12() {
		List<ApiEndpoint> all = translator.translate(Fixtures.text("catalog/swagger-api.json"), SEEN);

		assertThat(all).hasSize(497);
		assertThat(all.stream().map(ApiEndpoint::key).distinct()).hasSize(497);
		assertThat(all.stream().map(ApiEndpoint::tag).distinct()).hasSize(84);
		assertThat(all.stream().filter(e -> e.method().equals("get"))).hasSize(496);
		assertThat(all.stream().filter(e -> e.method().equals("post"))).hasSize(1);
		assertThat(all.stream().filter(e -> e.summary() != null)).hasSize(424);
		assertThat(all.stream().filter(ApiEndpoint::templated)).hasSize(208);
		assertThat(all.stream().map(ApiEndpoint::ordinal).toList())
				.containsExactlyElementsOf(java.util.stream.IntStream.range(0, 497).boxed().toList());
		assertThat(all).allSatisfy(e -> {
			assertThat(e.path()).startsWith("/servicio/");
			assertThat(e.url()).isEqualTo("https://www.zaragoza.es/sede" + e.path());
			assertThat(e.firstSeenAt()).isEqualTo(SEEN);
			assertThat(e.lastSeenAt()).isEqualTo(SEEN);
		});
		// 11 paths llegan sin barra inicial (publicaciones, proyecto-cooperacion): se normalizan
		assertThat(all.stream().map(ApiEndpoint::path)).contains("/servicio/publicaciones/list",
				"/servicio/proyecto-cooperacion/{id}");

		List<ApiEndpoint> arte = all.stream().filter(e -> e.tag().equals("Cultura: Arte en la via publica")).toList();
		assertThat(arte).hasSize(10);
		assertThat(arte.stream().filter(e -> !e.templated())).hasSize(7);
		assertThat(arte.stream().map(ApiEndpoint::path)).contains("/servicio/arte-publico", "/servicio/arte-publico/barrio");

		assertThat(all.stream().filter(e -> e.tag().equals("Urbanismo: Clavos Topograficos")).map(ApiEndpoint::path))
				.containsExactlyInAnyOrder("/servicio/clavo-topografico/list", "/servicio/clavo-topografico/{id}");
		assertThat(all.stream().filter(e -> e.tag().equals("Urbanismo: Clavos Topograficos") && !e.templated()))
				.singleElement().extracting(ApiEndpoint::summary).isEqualTo("Listado de clavos");
		// tags sin ficha en el catálogo, pero documentados: fuentes clave del proyecto (S0.1)
		assertThat(all.stream().filter(e -> e.tag().equals("Ayuntamiento: Contratación pública OCDS"))).hasSize(23);
		assertThat(all.stream().filter(e -> e.tag().equals("Ayuntamiento: Juntas administrativas"))).hasSize(4);
	}

	@Test
	void schemaChangesFailLoudly() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> translator.translate("{\"openapi\":\"3.0.0\",\"paths\":{}}", SEEN))
				.withMessageContaining("Swagger 2.0");
		assertThatIllegalArgumentException()
				.isThrownBy(() -> translator.translate("{\"swagger\":\"2.0\",\"host\":\"h\"}", SEEN))
				.withMessageContaining("paths");
		assertThatIllegalArgumentException()
				.isThrownBy(() -> translator.translate("{\"swagger\":\"2.0\",\"paths\":{\"/a\":{\"get\":{\"tags\":[\"T\"]}}}}", SEEN))
				.withMessageContaining("host");
		assertThatIllegalArgumentException()
				.isThrownBy(() -> translator.translate(
						"{\"swagger\":\"2.0\",\"host\":\"h\",\"paths\":{\"/a\":{\"get\":{\"summary\":\"x\"}}}}", SEEN))
				.withMessageContaining("tags");
	}

	@Test
	void normalizesPathsBlankSummariesAndMissingSchemesAndSplitsMultipleTags() {
		List<ApiEndpoint> endpoints = translator.translate("{\"swagger\":\"2.0\",\"host\":\"www.example.org\","
				+ "\"basePath\":\"/base\",\"paths\":{\"servicio/x\":{\"get\":{\"tags\":[\"A\",\"B\"],\"summary\":\"  \"},"
				+ "\"parameters\":[]},\"/servicio/y/{id}\":{\"post\":{\"tags\":[\"A\"],\"summary\":\"Alta\"}}}}", SEEN);

		assertThat(endpoints).hasSize(3);
		assertThat(endpoints.get(0).path()).isEqualTo("/servicio/x");
		assertThat(endpoints.get(0).url()).isEqualTo("https://www.example.org/base/servicio/x");
		assertThat(endpoints.get(0).summary()).isNull();
		assertThat(endpoints.stream().map(ApiEndpoint::tag)).containsExactly("A", "B", "A");
		assertThat(endpoints.get(2).method()).isEqualTo("post");
		assertThat(endpoints.get(2).templated()).isTrue();
		assertThat(endpoints.get(2).summary()).isEqualTo("Alta");
	}

}
