package es.zaragoza.observatory.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import es.zaragoza.observatory.catalog.domain.Dataset;
import es.zaragoza.observatory.catalog.infrastructure.zaragoza.CatalogJsonTranslator;
import es.zaragoza.observatory.support.Fixtures;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calidad de datos (SPEC.md §6, ESTADO §4.6): propiedades sobre lo que ingiere {@code catalog}, con la matriz
 * de S0.6 ({@code docs/spikes/S0.6-inventario-matriz.csv}, 436 datasets evaluados a mano el 2026-09-05) como
 * referencia independiente. Si el catálogo municipal cambia, el fixture se refresca con el spike S0.1 y esta
 * clase señala qué propiedad dejó de cumplirse.
 */
class CatalogDataQualityTest {

	static final Path MATRIX = Path.of("docs", "spikes", "S0.6-inventario-matriz.csv");
	static final LocalDate FIXTURE_DAY = LocalDate.of(2026, 9, 6);

	static Map<Integer, Dataset> ingested;
	static Map<Integer, Map<String, String>> matrix;

	@BeforeAll
	static void load() throws IOException {
		var translator = new CatalogJsonTranslator(JsonMapper.shared());
		ingested = translator.translate(Fixtures.text("catalog/catalogo-rows500-fl.json"), Instant.now()).stream()
				.collect(Collectors.toMap(Dataset::sourceId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
		matrix = readMatrix();
	}

	@Test
	void noDuplicateSourceIdsAndSameUniverseAsTheInventory() {
		assertThat(ingested).hasSize(436);
		assertThat(matrix).hasSize(436);
		assertThat(ingested.keySet()).containsExactlyInAnyOrderElementsOf(matrix.keySet());
	}

	@Test
	void datesAreCoherent() {
		// issued llega a 1968 («Reservas», 3996) y 1998 («Impresos», 301): fechas de creación del recurso, no del portal
		assertThat(ingested.values()).allSatisfy(d -> {
			assertThat(d.issued()).as("issued de %s", d.sourceId()).isNotNull();
			assertThat(d.metadataUpdated()).as("lastUpdated de %s", d.sourceId()).isNotNull();
			assertThat(d.issued().getYear()).as("issued de %s", d.sourceId()).isBetween(1900, FIXTURE_DAY.getYear());
			assertThat(d.issued().toLocalDate()).isBeforeOrEqualTo(FIXTURE_DAY);
			assertThat(d.metadataUpdated().toLocalDate()).isBeforeOrEqualTo(FIXTURE_DAY);
			if (d.declaredModified() != null) {
				assertThat(d.declaredModified().getYear()).as("modified de %s", d.sourceId())
						.isBetween(2000, FIXTURE_DAY.getYear());
				assertThat(d.declaredModified().toLocalDate()).isBeforeOrEqualTo(FIXTURE_DAY);
			}
		});
		// S0.1: lastUpdated es la fecha de la ficha (todas en 2026), no del dato
		assertThat(ingested.values()).allSatisfy(d -> assertThat(d.metadataUpdated().getYear()).isEqualTo(2026));
	}

	@Test
	void periodicityIsEitherEvaluableOrExplicitlyNot() {
		assertThat(ingested.values()).allSatisfy(d -> {
			if (d.periodicityDays() != null) {
				assertThat(d.declaredPeriodicity()).startsWith("P");
				assertThat(d.periodicityDays()).isPositive();
			}
			else {
				assertThat(d.declaredPeriodicity()).isIn(null, "NEVER", "IRREG", "P0DT1S");
			}
		});
	}

	@Test
	void matchesTheInventoryMatrixFieldByField() {
		ingested.forEach((id, d) -> {
			Map<String, String> row = matrix.get(id);
			assertThat(d.title()).as("título de %s", id).isEqualTo(row.get("título").strip());
			assertThat(nullToEmpty(d.declaredPeriodicity())).as("periodicidad de %s", id)
					.isEqualTo(dashToEmpty(row.get("periodicidad")));
			assertThat(nullToEmpty(d.apiTag())).as("tag swagger de %s", id).isEqualTo(row.get("tag swagger"));
			assertThat(d.explorable() ? "sí" : "no").as("explorable de %s", id).isEqualTo(row.get("explorable"));
			assertThat(Boolean.TRUE.equals(d.open()) ? "sí" : "no").as("abierto de %s", id)
					.isEqualTo(row.get("abierto"));
			assertThat(d.declaredModified() == null ? "" : d.declaredModified().toLocalDate().toString())
					.as("modified de %s", id).isEqualTo(dashToEmpty(row.get("modified")));
		});
	}

	@Test
	void apiDistributionsAndSwaggerTagsMatchS01() {
		// 69 datasets con application/api; 68 con tag (S0.1). El 2203 («morosidad») tiene la API sin accessURL.
		assertThat(ingested.values().stream().filter(Dataset::hasApiDistribution)).hasSize(69);
		assertThat(ingested.values().stream().filter(d -> d.apiTag() != null)).hasSize(68);
		ingested.values().stream().filter(Dataset::hasApiDistribution).forEach(d -> assertThat(d.distributions())
				.filteredOn(Dataset.Distribution::isApi).allSatisfy(dist -> {
					if (dist.accessUrl() == null) {
						assertThat(d.sourceId()).isEqualTo(2203);
						assertThat(dist.downloadUrl()).isNotBlank();
					}
					else if (d.apiTag() == null) {
						assertThat(dist.accessUrl()).doesNotContain("#/");
					}
				}));
	}

	static String dashToEmpty(String value) {
		return "-".equals(value) ? "" : value;
	}

	static Map<Integer, Map<String, String>> readMatrix() throws IOException {
		List<String> lines = Files.readAllLines(MATRIX, StandardCharsets.UTF_8);
		String[] header = lines.get(0).split(";", -1);
		Map<Integer, Map<String, String>> rows = new LinkedHashMap<>();
		for (String line : lines.subList(1, lines.size())) {
			if (line.isBlank()) {
				continue;
			}
			String[] cells = line.split(";", -1);
			assertThat(cells).as("columnas de la fila «%s»", line).hasSize(header.length);
			Map<String, String> row = new LinkedHashMap<>();
			for (int i = 0; i < header.length; i++) {
				row.put(header[i], cells[i]);
			}
			rows.put(Integer.parseInt(row.get("id")), row);
		}
		return rows;
	}

	static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

}
