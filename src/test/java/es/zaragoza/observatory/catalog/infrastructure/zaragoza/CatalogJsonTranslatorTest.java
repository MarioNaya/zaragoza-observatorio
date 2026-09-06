package es.zaragoza.observatory.catalog.infrastructure.zaragoza;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;

import es.zaragoza.observatory.catalog.domain.Dataset;
import es.zaragoza.observatory.support.Fixtures;
import tools.jackson.databind.json.JsonMapper;

/**
 * Anti-corruption layer sobre respuestas reales (2026-09-06). Los recuentos esperados son los de S0.1
 * (docs/spikes/S0.1-catalogo.md) recomputados sobre el fixture con {@code fl}.
 */
class CatalogJsonTranslatorTest {

	static final Instant SEEN = Instant.parse("2026-09-06T08:24:48Z");

	final CatalogJsonTranslator translator = new CatalogJsonTranslator(JsonMapper.shared());

	@Test
	void translatesArtePublicoFromRealResponse() {
		List<Dataset> datasets = translator.translate(Fixtures.text("catalog/catalogo-rows2-fl.json"), SEEN);

		assertThat(datasets).hasSize(2);
		Dataset arte = datasets.get(0);
		assertThat(arte.sourceId()).isEqualTo(13);
		assertThat(arte.title()).isEqualTo("Arte Público");
		assertThat(arte.description()).startsWith("Catálogo de monumentos");
		assertThat(arte.issued()).isEqualTo(LocalDateTime.of(2009, 1, 16, 0, 0));
		assertThat(arte.declaredModified()).isEqualTo(LocalDateTime.of(2019, 10, 23, 0, 0));
		assertThat(arte.metadataUpdated()).isEqualTo(LocalDateTime.of(2026, 1, 20, 13, 12, 38));
		assertThat(arte.declaredPeriodicity()).isEqualTo("P3M");
		assertThat(arte.periodicityDays()).isEqualTo(90);
		assertThat(arte.publicationStatus()).isEqualTo("Finalizado");
		assertThat(arte.hasGeo()).isTrue();
		assertThat(arte.open()).isTrue();
		assertThat(arte.explorable()).isFalse();
		assertThat(arte.apiTag()).isEqualTo("Cultura: Arte en la via publica");
		assertThat(arte.hasApiDistribution()).isTrue();
		assertThat(arte.distributions()).singleElement().satisfies(d -> {
			assertThat(d.sourceId()).isEqualTo(44);
			assertThat(d.mediaType()).isEqualTo("application/api");
			assertThat(d.accessUrl()).startsWith("https://www.zaragoza.es/docs-api_sede/#/");
			assertThat(d.downloadUrl()).isEqualTo("/sede/servicio/arte-publico");
			assertThat(d.title()).isEqualTo("Arte Público");
		});
		assertThat(arte.firstSeenAt()).isEqualTo(SEEN);
		assertThat(arte.lastSeenAt()).isEqualTo(SEEN);

		// «Incidencias en la Vía Pública»: primero un buscador (application/solr) y después la API; el tag sale de la API
		Dataset incidencias = datasets.get(1);
		assertThat(incidencias.sourceId()).isEqualTo(67);
		assertThat(incidencias.distributions()).extracting(Dataset.Distribution::mediaType)
				.containsExactly("application/solr", "application/api");
		assertThat(incidencias.hasApiDistribution()).isTrue();
		assertThat(incidencias.apiTag()).startsWith("Equipamientos y movilidad: Incidencias");
	}

	@Test
	void translatesTheWholeCatalogWithTheCoverageMeasuredInS01() {
		List<Dataset> all = translator.translate(Fixtures.text("catalog/catalogo-rows500-fl.json"), SEEN);

		assertThat(all).hasSize(436);
		assertThat(all.stream().map(Dataset::sourceId).distinct()).hasSize(436);
		assertThat(all.stream().filter(d -> d.declaredModified() != null)).hasSize(412);
		assertThat(all.stream().filter(d -> d.declaredPeriodicity() != null)).hasSize(312);
		assertThat(all.stream().filter(d -> "P1Y".equals(d.declaredPeriodicity()))).hasSize(135);
		assertThat(all.stream().filter(d -> "NEVER".equals(d.declaredPeriodicity()))).hasSize(72);
		assertThat(all.stream().filter(d -> "P0DT1S".equals(d.declaredPeriodicity()))).hasSize(46);
		assertThat(all.stream().filter(d -> d.periodicityDays() != null)).hasSize(312 - 72 - 46 - 17);
		assertThat(all.stream().filter(Dataset::hasApiDistribution)).hasSize(69);
		assertThat(all.stream().filter(d -> d.apiTag() != null)).hasSize(68);
		assertThat(all.stream().filter(Dataset::explorable)).hasSize(110);
		assertThat(all.stream().filter(d -> Boolean.TRUE.equals(d.hasGeo()))).hasSize(317);
		assertThat(all.stream().filter(d -> d.hasGeo() == null)).hasSize(28);
		assertThat(all.stream().filter(d -> Boolean.FALSE.equals(d.open()))).hasSize(149);
		assertThat(all.stream().filter(d -> "Finalizado".equals(d.publicationStatus()))).hasSize(371);
		assertThat(all.stream().map(Dataset::declaredModified).filter(Objects::nonNull).max(LocalDateTime::compareTo))
				.contains(LocalDateTime.of(2026, 8, 11, 0, 0));
		assertThat(all).allSatisfy(d -> assertThat(d.metadataUpdated()).isNotNull());
	}

	@Test
	void schemaChangesFailLoudly() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> translator.translate("{\"totalCount\":1,\"result\":[{\"id\":5}]}", SEEN))
				.withMessageContaining("title");
		assertThatIllegalArgumentException()
				.isThrownBy(() -> translator.translate("{\"totalCount\":1,\"result\":[{\"title\":\"x\"}]}", SEEN))
				.withMessageContaining("id");
		assertThatIllegalArgumentException().isThrownBy(() -> translator.translate("{\"totalCount\":1}", SEEN))
				.withMessageContaining("result");
	}

	@Test
	void toleratesMissingOptionalFields() {
		List<Dataset> datasets = translator.translate(
				"{\"totalCount\":1,\"result\":[{\"id\":7,\"title\":\"Mínimo\",\"geo\":\"\",\"modified\":\"2025-13-40\"}]}",
				SEEN);

		Dataset d = datasets.get(0);
		assertThat(d.hasGeo()).isNull();
		assertThat(d.open()).isNull();
		assertThat(d.declaredModified()).isNull();
		assertThat(d.declaredPeriodicity()).isNull();
		assertThat(d.periodicityDays()).isNull();
		assertThat(d.distributions()).isEmpty();
		assertThat(d.explorable()).isFalse();
	}

}
