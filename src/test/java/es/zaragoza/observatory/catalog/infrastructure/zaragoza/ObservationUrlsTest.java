package es.zaragoza.observatory.catalog.infrastructure.zaragoza;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import es.zaragoza.observatory.catalog.domain.Dataset;
import es.zaragoza.observatory.catalog.domain.Dataset.Distribution;

/** Reglas de S1.1 sobre distribuciones reales del catálogo (fixture {@code catalogo-rows500-fl.json}). */
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

	static Dataset dataset(Distribution... distributions) {
		Instant now = Instant.parse("2026-09-06T10:00:00Z");
		return new Dataset(1, "test", null, null, null, null, null, null, null, true, true, false, null,
				List.of(distributions), now, now);
	}

}
