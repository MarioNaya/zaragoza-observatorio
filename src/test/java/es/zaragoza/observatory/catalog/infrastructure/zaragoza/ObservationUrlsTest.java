package es.zaragoza.observatory.catalog.infrastructure.zaragoza;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import es.zaragoza.observatory.catalog.domain.ApiEndpoint;
import es.zaragoza.observatory.catalog.domain.Dataset;
import es.zaragoza.observatory.catalog.domain.Dataset.Distribution;

/**
 * Reglas de S1.1 sobre distribuciones reales del catálogo (fixture {@code catalogo-rows500-fl.json}) y de S1.2
 * sobre el Swagger ({@code swagger-api.json}).
 */
class ObservationUrlsTest {

	static final Distribution ARTE_API = new Distribution(44, "application/api",
			"https://www.zaragoza.es/docs-api_sede/#/Cultura%3A%20Arte%20en%20la%20via%20publica",
			"/sede/servicio/arte-publico", "Arte Público", null);
	static final Distribution AFORO_GEOJSON = new Distribution(4280, "application/json",
			"https://www.zaragoza.es/sede/servicio/equipamiento/aforo.geojson?fl=title%2Coccupation%2Cgeometry&rows=50",
			null, "Aforo", null);
	static final Distribution CENTROS_JSON = new Distribution(4247, "application/json",
			"https://www.zaragoza.es/sede/servicio/equipamiento/basic/centros-deportivos.json?srsname=utm30n_etrs89",
			null, "Centros", null);
	static final Distribution CENTROS_CSV = new Distribution(4245, "text/csv",
			"https://www.zaragoza.es/sede/servicio/equipamiento/basic/centros-deportivos.csv?srsname=utm30n_etrs89",
			null, "Centros csv", null);
	static final Distribution PABELLONES_XLS = new Distribution(849, "application/vnd.ms-excel", null,
			"https://www.zaragoza.es/cont/paginas/opendata/PABELLONESZRG1.xls", "Pabellones", null);
	static final Distribution VIAS_WFS = new Distribution(13762, "application/vnd.ogc.wfs_xml",
			"https://idezar-sig.zaragoza.es/servicios/geoserver/urbanismo/wfs?version=1.1.1&&Request=GetCapabilities",
			null, "Vías", "Vias");
	static final Distribution URBANISMO_WMS = new Distribution(13760, "application/vnd.ogc.wms_xml",
			"https://idezar-sig.zaragoza.es/servicios/geoserver/urbanismo/wms", null, "WMS", null);
	static final Distribution ESCAPED = new Distribution(9, "text/xml",
			"https://idezar.zaragoza.es/wfss/wfss?request=GetFeature&amp;featureType=PuntosDeInteres", null, "x", null);

	@Test
	void apiCandidatesPreferApiThenJsonThenGeoJsonAndIgnoreCsvVariants() {
		Dataset dataset = dataset(AFORO_GEOJSON, CENTROS_CSV, CENTROS_JSON, ARTE_API, PABELLONES_XLS);

		assertThat(ObservationUrls.apiCandidates(dataset)).containsExactly(ARTE_API, CENTROS_JSON, AFORO_GEOJSON);
		assertThat(ObservationUrls.fileCandidates(dataset)).containsExactly(PABELLONES_XLS);
		assertThat(ObservationUrls.wfsCandidates(dataset)).isEmpty();
	}

	@Test
	void apiUrlAppendsJsonExtensionRowsAndSrsForRelativeDownloadUrl() {
		URI url = ObservationUrls.apiUrl(ARTE_API, true);

		assertThat(url).hasToString("https://www.zaragoza.es/sede/servicio/arte-publico.json?rows=1&srsname=wgs84");
		assertThat(ObservationUrls.sortedUrl(url, "lastUpdated"))
				.hasToString("https://www.zaragoza.es/sede/servicio/arte-publico.json?rows=1&srsname=wgs84&sort=lastUpdated+desc");
	}

	@Test
	void apiUrlKeepsOriginalQueryExceptPagingAndDoesNotDuplicateSrs() {
		assertThat(ObservationUrls.apiUrl(AFORO_GEOJSON, true)).hasToString(
				"https://www.zaragoza.es/sede/servicio/equipamiento/aforo.geojson?fl=title%2Coccupation%2Cgeometry&rows=1&srsname=wgs84");
		assertThat(ObservationUrls.apiUrl(CENTROS_JSON, true)).hasToString(
				"https://www.zaragoza.es/sede/servicio/equipamiento/basic/centros-deportivos.json?srsname=utm30n_etrs89&rows=1");
		assertThat(ObservationUrls.apiUrl(ARTE_API, false))
				.hasToString("https://www.zaragoza.es/sede/servicio/arte-publico.json?rows=1");
	}

	@Test
	void wfsHitsUrlDropsTheCatalogQueryStringAndEncodesTheLayer() {
		Dataset dataset = dataset(URBANISMO_WMS, VIAS_WFS);

		assertThat(ObservationUrls.wfsCandidates(dataset)).containsExactly(VIAS_WFS);
		assertThat(ObservationUrls.wfsHitsUrl(VIAS_WFS)).hasToString(
				"https://idezar-sig.zaragoza.es/servicios/geoserver/urbanismo/wfs?service=WFS&version=2.0.0&request=GetFeature&typeNames=Vias&resultType=hits");
		assertThat(ObservationUrls.apiCandidates(dataset)).isEmpty();
		assertThat(ObservationUrls.fileCandidates(dataset)).isEmpty();
	}

	@Test
	void normalizesHtmlEscapedAmpersandsAndRelativePaths() {
		assertThat(ObservationUrls.url(ESCAPED))
				.isEqualTo("https://idezar.zaragoza.es/wfss/wfss?request=GetFeature&featureType=PuntosDeInteres");
		assertThat(ObservationUrls.normalize("/sede/servicio/x")).isEqualTo("https://www.zaragoza.es/sede/servicio/x");
		assertThat(ObservationUrls.normalize(null)).isEmpty();
	}

	// --- S1.2: «<declarado>/list» documentado en el tag --------------------------------------------------------------

	@Test
	void documentedListUrlIsTheDeclaredPathPlusListWithinTheTagIgnoringAccentsAndTrailingSlash() {
		String tag = "Gobierno abierto: Censo de Asociaciones";
		Distribution asociacion = new Distribution(1, "application/api", "https://www.zaragoza.es/docs-api_sede/#/x",
				"/sede/servicio/asociacion", "Censo", null);
		List<ApiEndpoint> documented = List.of(endpoint(tag, "/servicio/asociacion"),
				endpoint(tag, "/servicio/asociacion/list"), endpoint(tag, "/servicio/asociacion/{id}"));
		assertThat(ObservationUrls.documentedListUrl(tagged(tag, asociacion), documented))
				.contains("https://www.zaragoza.es/sede/servicio/asociacion/list");

		// tilde en el catálogo, sin tilde en el Swagger (247 «Clavos Topográficos»)
		Distribution clavos = new Distribution(2, "application/api", null, "/sede/servicio/clavo-topográfico", "Clavos", null);
		assertThat(ObservationUrls.documentedListUrl(tagged("Urbanismo: Clavos Topograficos", clavos),
				List.of(endpoint("Urbanismo: Clavos Topograficos", "/servicio/clavo-topografico/list"))))
				.contains("https://www.zaragoza.es/sede/servicio/clavo-topografico/list");

		// barra final en el catálogo (1742 «Organización Municipal» declara /sede/servicio/organigrama/)
		Distribution organigrama = new Distribution(3, "application/api", null, "/sede/servicio/organigrama/", "Org", null);
		assertThat(ObservationUrls.documentedListUrl(tagged("Ayuntamiento: Organizacion municipal", organigrama),
				List.of(endpoint("Ayuntamiento: Organizacion municipal", "/servicio/organigrama/list"))))
				.contains("https://www.zaragoza.es/sede/servicio/organigrama/list");
	}

	@Test
	void documentedListUrlIgnoresOtherPathsOfTheTagTemplatesNonGetAndDatasetsWithoutTag() {
		String tag = "Equipamientos y movilidad: Transporte urbano";
		String base = "/servicio/urbanismo-infraestructuras/transporte-urbano";
		Distribution transporte = new Distribution(4, "application/api", null, "/sede" + base, "Transporte", null);
		assertThat(ObservationUrls.documentedListUrl(tagged(tag, transporte), List.of(endpoint(tag, base + "/linea-autobus"),
				endpoint(tag, base + "/poste-autobus"), endpoint(tag, base + "/parada-tranvia")))).isEmpty();
		assertThat(ObservationUrls.documentedListUrl(tagged(tag, transporte), List.of(endpoint(tag, base + "/list/{id}"))))
				.isEmpty();
		assertThat(ObservationUrls.documentedListUrl(tagged(tag, transporte), List.of(new ApiEndpoint(tag, "post",
				base + "/list", "https://www.zaragoza.es/sede" + base + "/list", null, 0, NOW, NOW)))).isEmpty();
		assertThat(ObservationUrls.documentedListUrl(tagged(tag, transporte), List.of())).isEmpty();
		assertThat(ObservationUrls.documentedListUrl(dataset(transporte), List.of(endpoint(tag, base + "/list")))).isEmpty();
		// sin distribución application/api con downloadURL no hay path declarado
		assertThat(ObservationUrls.documentedListUrl(tagged(tag, CENTROS_JSON), List.of(endpoint(tag, base + "/list"))))
				.isEmpty();
	}

	/**
	 * Sobre el catálogo y el Swagger reales, diez fichas tienen documentado {@code <declarado>/list}: en seis el
	 * endpoint declarado responde y el {@code /list} nunca se llega a probar; en las otras cuatro (asociaciones,
	 * clavos, artistas, premios) el declarado falla y el {@code /list} responde (S1.2).
	 */
	@Test
	void tenCatalogDatasetsHaveADocumentedListPathInTheRealSwagger() {
		var catalog = new CatalogJsonTranslator(tools.jackson.databind.json.JsonMapper.shared())
				.translate(es.zaragoza.observatory.support.Fixtures.text("catalog/catalogo-rows500-fl.json"), NOW);
		var swagger = new SwaggerJsonTranslator(tools.jackson.databind.json.JsonMapper.shared())
				.translate(es.zaragoza.observatory.support.Fixtures.text("catalog/swagger-api.json"), NOW);
		var byTag = swagger.stream().collect(java.util.stream.Collectors.groupingBy(ApiEndpoint::tag));

		var withList = catalog.stream()
				.filter(d -> d.apiTag() != null && ObservationUrls
						.documentedListUrl(d, byTag.getOrDefault(d.apiTag(), List.of())).isPresent())
				.map(Dataset::sourceId).toList();

		assertThat(withList).containsExactlyInAnyOrder(28, 132, 145, 146, 247, 1062, 1681, 1760, 1820, 1920)
				.contains(132, 247, 1920, 1760);
	}

	static final Instant NOW = Instant.parse("2026-09-06T10:00:00Z");

	static ApiEndpoint endpoint(String tag, String path) {
		return new ApiEndpoint(tag, "get", path, "https://www.zaragoza.es/sede" + path, null, 0, NOW, NOW);
	}

	static Dataset tagged(String tag, Distribution... distributions) {
		return new Dataset(1, "test", null, null, null, null, null, null, null, true, true, false, tag,
				List.of(distributions), NOW, NOW);
	}

	static Dataset dataset(Distribution... distributions) {
		return tagged(null, distributions);
	}

}
