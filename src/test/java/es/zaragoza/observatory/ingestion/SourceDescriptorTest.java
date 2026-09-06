package es.zaragoza.observatory.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import es.zaragoza.observatory.ingestion.SourceDescriptor.Pagination;
import es.zaragoza.observatory.ingestion.SourceDescriptor.ResponseShape;
import es.zaragoza.observatory.shared.DatasetRef;
import es.zaragoza.observatory.shared.Sources;

class SourceDescriptorTest {

	static final DatasetRef CATALOG = DatasetRef.of(Sources.DATA_SPACE, "catalogo");
	static final URI CATALOG_URL = URI.create("https://www.zaragoza.es/web/espacio-de-datos/servicio/catalogo.json");

	@Test
	void requiresJsonOrGeoJsonExtension() {
		assertThatIllegalArgumentException().isThrownBy(() -> new SourceDescriptor(CATALOG,
				URI.create("https://www.zaragoza.es/web/espacio-de-datos/servicio/catalogo"), Map.of(),
				Pagination.offset(500), ResponseShape.ENVELOPE)).withMessageContaining(".json");
		assertThatIllegalArgumentException().isThrownBy(() -> new SourceDescriptor(CATALOG,
				URI.create("https://www.zaragoza.es/sede/servicio/distrito.csv"), Map.of(), Pagination.offset(500),
				ResponseShape.ENVELOPE));
		assertThat(new SourceDescriptor(DatasetRef.of(Sources.SEDE, "distrito"),
				URI.create("https://www.zaragoza.es/sede/servicio/distrito.geojson"), Map.of("srsname", "wgs84"),
				Pagination.none(500), ResponseShape.ENVELOPE).url().getPath()).endsWith(".geojson");
	}

	@Test
	void rejectsQueryStringInsideUrl() {
		assertThatIllegalArgumentException().isThrownBy(() -> new SourceDescriptor(CATALOG,
				URI.create("https://www.zaragoza.es/web/espacio-de-datos/servicio/catalogo.json?rows=500"), Map.of(),
				Pagination.offset(500), ResponseShape.ENVELOPE)).withMessageContaining("query string");
	}

	@Test
	void capsRowsAt500ForSedeAndDataSpaceOnly() {
		assertThatThrownBy(() -> new SourceDescriptor(CATALOG, CATALOG_URL, Map.of(), Pagination.offset(501),
				ResponseShape.ENVELOPE)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("500");
		assertThatThrownBy(() -> new SourceDescriptor(DatasetRef.of(Sources.SEDE, "quejas-sugerencias/list"),
				URI.create("https://www.zaragoza.es/sede/servicio/quejas-sugerencias/list.json"), Map.of(),
				Pagination.offset(1000), ResponseShape.ARRAY)).isInstanceOf(IllegalArgumentException.class);
		// OCDS y Open311 no tienen tope (S0.5)
		var ocds = new SourceDescriptor(DatasetRef.of(Sources.OCDS, "contracting-process"),
				URI.create("https://www.zaragoza.es/sede/servicio/contratacion-publica/ocds/contracting-process.json"),
				Map.of(), Pagination.none(20000), ResponseShape.ARRAY);
		assertThat(ocds.pagination().rows()).isEqualTo(20000);
	}

	@Test
	void copiesQueryAndKeepsOrder() {
		var query = new LinkedHashMap<String, String>();
		query.put("fl", "id,title");
		query.put("srsname", "wgs84");
		var descriptor = new SourceDescriptor(CATALOG, CATALOG_URL, query, Pagination.offset(500),
				ResponseShape.ENVELOPE);
		query.put("extra", "x");

		assertThat(descriptor.query()).containsExactly(Map.entry("fl", "id,title"), Map.entry("srsname", "wgs84"));
		assertThatThrownBy(() -> descriptor.query().put("a", "b")).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void paginationValidatesRows() {
		assertThatIllegalArgumentException().isThrownBy(() -> Pagination.offset(0));
		assertThat(Pagination.none(50).mode()).isEqualTo(Pagination.Mode.NONE);
	}

}
