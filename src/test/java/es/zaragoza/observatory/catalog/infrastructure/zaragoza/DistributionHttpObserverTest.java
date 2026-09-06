package es.zaragoza.observatory.catalog.infrastructure.zaragoza;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import es.zaragoza.observatory.catalog.domain.ApiEndpoint;
import es.zaragoza.observatory.catalog.domain.Dataset;
import es.zaragoza.observatory.catalog.domain.Dataset.Distribution;
import es.zaragoza.observatory.catalog.domain.Observation;
import es.zaragoza.observatory.catalog.domain.ObservationMethod;
import es.zaragoza.observatory.catalog.support.InMemoryApiEndpoints;
import es.zaragoza.observatory.shared.HttpDates;
import es.zaragoza.observatory.support.Fixtures;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adaptador de observación sobre {@link MockRestServiceServer} con las respuestas reales grabadas por S1.1
 * ({@code fixtures/zaragoza/catalog/observation/}): API de la sede ({@code rows=1} y {@code sort desc}),
 * {@code HEAD} a ficheros, WFS {@code hits}, y los fallos observados (404 HTML, 303, 400 por {@code sort}, 200
 * vacío, timeouts).
 */
class DistributionHttpObserverTest {

	static final Instant NOW = Instant.parse("2026-09-06T12:00:00Z");
	static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json;charset=UTF-8");
	static final String INCIDENCIA = "https://www.zaragoza.es/sede/servicio/via-publica/incidencia.json?rows=1&srsname=wgs84";
	static final String ARTE = "https://www.zaragoza.es/sede/servicio/arte-publico.json?rows=1&srsname=wgs84";
	static final String WFS_URBANISMO = "https://idezar-sig.zaragoza.es/servicios/geoserver/urbanismo/wfs";

	MockRestServiceServer server;
	DistributionHttpObserver observer;
	final InMemoryApiEndpoints endpoints = new InMemoryApiEndpoints();

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		observer = new DistributionHttpObserver(builder.build(), JsonMapper.shared(), endpoints,
				Clock.fixed(NOW, ZoneOffset.UTC), Duration.ZERO, 10);
	}

	// --- S1.2: «<declarado>/list» documentado en el Swagger ------------------------------------------------------

	@Test
	void triesTheDocumentedListPathWhenTheDeclaredEndpointRedirectsToAnIndex() {
		// «Censo de Asociaciones» (132): /sede/servicio/asociacion responde 303 a un índice con jsessionid (S1.1)
		String tag = "Gobierno abierto: Censo de Asociaciones";
		endpoints.replaceAll(List.of(endpoint(tag, "/servicio/asociacion", 0), endpoint(tag, "/servicio/asociacion/list", 1),
				endpoint(tag, "/servicio/asociacion/{id}", 2)), NOW);
		Dataset asociaciones = tagged(132, false, tag, api("/sede/servicio/asociacion"));
		String list = "https://www.zaragoza.es/sede/servicio/asociacion/list.json?rows=1";
		server.expect(requestTo("https://www.zaragoza.es/sede/servicio/asociacion.json?rows=1"))
				.andRespond(withStatus(HttpStatus.SEE_OTHER).header(HttpHeaders.LOCATION,
						"https://www.zaragoza.es/sede/servicio/asociacion/;jsessionid=iU93lJeq7"));
		server.expect(requestTo(list)).andRespond(withSuccess(
				Fixtures.bytes("catalog/observation/api-sede-servicio-asociacion-list-rows1.json"), JSON_UTF8));
		server.expect(requestTo(list + "&sort=creationDate+desc")).andRespond(withSuccess(
				Fixtures.bytes("catalog/observation/api-sede-servicio-asociacion-list-rows1.json"), JSON_UTF8));

		Observation o = observer.observe(asociaciones);

		server.verify();
		assertThat(o.measured()).isTrue();
		assertThat(o.method()).isEqualTo(ObservationMethod.API_MAX_DATE);
		assertThat(o.url()).isEqualTo(list + "&sort=creationDate+desc");
		assertThat(o.records()).isEqualTo(2823);
		assertThat(o.detail()).isEqualTo("creationDate");
		assertThat(o.lastChange()).isEqualTo(Instant.parse("1987-05-26T22:00:00Z")); // 1987-05-27T00:00 en Zaragoza
	}

	@Test
	void matchesTheDocumentedListPathIgnoringAccentsWhenTheDeclaredEndpointDoesNotExist() {
		// «Clavos Topográficos» (247): el catálogo declara clavo-topográfico (404 HTML); el Swagger documenta
		// clavo-topografico/list en su tag
		String tag = "Urbanismo: Clavos Topograficos";
		endpoints.replaceAll(List.of(endpoint(tag, "/servicio/clavo-topografico/list", 0),
				endpoint(tag, "/servicio/clavo-topografico/{id}", 1)), NOW);
		Dataset clavos = tagged(247, true, tag, api("/sede/servicio/clavo-topográfico"), wfs("Clavos_Topograficos"));
		String list = "https://www.zaragoza.es/sede/servicio/clavo-topografico/list.json?rows=1&srsname=wgs84";
		server.expect(requestTo(startsWith("https://www.zaragoza.es/sede/servicio/clavo-topogr")))
				.andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.TEXT_HTML)
						.body("<HTML><HEAD><TITLE>Error 404--Not Found</TITLE>"));
		server.expect(requestTo(list)).andRespond(withSuccess(
				Fixtures.bytes("catalog/observation/api-sede-servicio-clavo-topografico-list-rows1.json"), JSON_UTF8));
		server.expect(requestTo(list + "&sort=lastUpdated+desc")).andRespond(withSuccess(
				Fixtures.bytes("catalog/observation/api-sede-servicio-clavo-topografico-list-rows1.json"), JSON_UTF8));

		Observation o = observer.observe(clavos);

		server.verify(); // el WFS no llega a consultarse
		assertThat(o.method()).isEqualTo(ObservationMethod.API_MAX_DATE);
		assertThat(o.records()).isEqualTo(4357);
		assertThat(o.detail()).isEqualTo("lastUpdated");
		assertThat(o.lastChange()).isEqualTo(Instant.parse("2017-12-29T11:26:20Z"));
	}

	@Test
	void doesNotUseOtherDocumentedPathsOfTheTag() {
		// «Tranvía de Zaragoza» (327): el tag documenta linea-autobus, poste-autobus y parada-tranvia, ninguno es
		// <declarado>/list; la elección no sería unívoca y se registra el fallo del endpoint declarado
		String tag = "Equipamientos y movilidad: Transporte urbano";
		String base = "/servicio/urbanismo-infraestructuras/transporte-urbano";
		endpoints.replaceAll(List.of(endpoint(tag, base + "/linea-autobus", 0), endpoint(tag, base + "/poste-autobus", 1),
				endpoint(tag, base + "/parada-tranvia", 2)), NOW);
		Dataset tranvia = tagged(327, true, tag, api("/sede" + base));
		server.expect(times(1), requestTo("https://www.zaragoza.es/sede" + base + ".json?rows=1&srsname=wgs84"))
				.andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.TEXT_HTML).body("<html>404</html>"));

		Observation o = observer.observe(tranvia);

		server.verify();
		assertThat(o.failed()).isTrue();
		assertThat(o.error()).startsWith("HTTP 404");
	}

	static ApiEndpoint endpoint(String tag, String path, int ordinal) {
		return new ApiEndpoint(tag, "get", path, "https://www.zaragoza.es/sede" + path, null, ordinal, NOW, NOW);
	}

	static Dataset tagged(int id, boolean geo, String tag, Distribution... distributions) {
		return new Dataset(id, "dataset " + id, null, null, null, null, "P0DT1S", null, "Finalizado", geo, true,
				false, tag, List.of(distributions), NOW, NOW);
	}

	@Test
	void apiMaxDateFromRealSedeResponses() {
		Dataset incidencias = dataset(67, true, solr(), api("/sede/servicio/via-publica/incidencia"));
		server.expect(requestTo(INCIDENCIA)).andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(Fixtures.bytes("catalog/observation/api-sede-servicio-via-publica-incidencia-rows1.json"), JSON_UTF8));
		server.expect(requestTo(INCIDENCIA + "&sort=lastUpdated+desc"))
				.andRespond(withSuccess(Fixtures.bytes("catalog/observation/api-sede-servicio-via-publica-incidencia-sort-lastUpdated.json"), JSON_UTF8));

		Observation o = observer.observe(incidencias);

		server.verify();
		assertThat(o.measured()).isTrue();
		assertThat(o.method()).isEqualTo(ObservationMethod.API_MAX_DATE);
		assertThat(o.lastChange()).isEqualTo(Instant.parse("2026-09-04T12:23:47Z")); // 14:23:47 hora de Zaragoza
		assertThat(o.records()).isEqualTo(67);
		assertThat(o.detail()).isEqualTo("lastUpdated");
		assertThat(o.url()).isEqualTo(INCIDENCIA + "&sort=lastUpdated+desc");
		assertThat(o.observedAt()).isEqualTo(NOW);
	}

	@Test
	void apiCountWhenSortIsRejectedWith400() {
		Dataset arte = dataset(13, true, api("/sede/servicio/arte-publico"));
		server.expect(requestTo(ARTE))
				.andRespond(withSuccess(Fixtures.bytes("catalog/observation/api-sede-servicio-arte-publico-rows1.json"), JSON_UTF8));
		server.expect(requestTo(ARTE + "&sort=lastUpdated+desc")).andRespond(withBadRequest()
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"status\":400, \"mensaje\":\"org.hibernate.QueryException: could not resolve property: lastUpdated\"}"));

		Observation o = observer.observe(arte);

		server.verify();
		assertThat(o.method()).isEqualTo(ObservationMethod.API_COUNT);
		assertThat(o.records()).isEqualTo(471);
		assertThat(o.lastChange()).isNull();
		assertThat(o.detail()).startsWith("sort=lastUpdated desc no utilizable: HTTP 400");
		assertThat(o.url()).isEqualTo(ARTE);
	}

	@Test
	void apiCountWhenSortAnswers200WithEmptyBody() {
		Dataset puntos = dataset(23, false, api("/sede/servicio/puntos-interes"));
		String base = "https://www.zaragoza.es/sede/servicio/puntos-interes.json?rows=1";
		server.expect(requestTo(base)).andRespond(withSuccess(
				"{\"totalCount\":19632,\"start\":0,\"rows\":1,\"result\":[{\"id\":\"acto-313727\",\"lastUpdated\":\"2026-09-05T13:30:23\"}]}",
				JSON_UTF8));
		server.expect(requestTo(base + "&sort=lastUpdated+desc")).andRespond(withSuccess());

		Observation o = observer.observe(puntos);

		server.verify();
		assertThat(o.method()).isEqualTo(ObservationMethod.API_COUNT);
		assertThat(o.records()).isEqualTo(19632);
		assertThat(o.detail()).contains("respuesta no JSON");
	}

	@Test
	void apiCountOnlyWhenNoWhitelistedDateField() {
		Dataset subvenciones = dataset(1400, false, api("/sede/servicio/ayuda-subvencion"));
		server.expect(requestTo("https://www.zaragoza.es/sede/servicio/ayuda-subvencion.json?rows=1")).andRespond(
				withSuccess("{\"records\":[{\"id\":1,\"fechaAdjudicacion\":\"2014-10-17T00:00:00\"}],\"totalRecords\":20737}",
						JSON_UTF8));

		Observation o = observer.observe(subvenciones);

		server.verify();
		assertThat(o.method()).isEqualTo(ObservationMethod.API_COUNT);
		assertThat(o.records()).isEqualTo(20737);
		assertThat(o.detail()).isEqualTo("sin campo de fecha admitido en el registro");
	}

	@Test
	void apiCountZeroWhenTheEnvelopeHasNoResultArray() {
		Dataset anuncios = dataset(262, false, api("/sede/servicio/anuncio-juventud"));
		server.expect(requestTo("https://www.zaragoza.es/sede/servicio/anuncio-juventud.json?rows=1"))
				.andRespond(withSuccess("{\"rows\":1,\"start\":0,\"totalCount\":0}", JSON_UTF8));

		Observation o = observer.observe(anuncios);

		server.verify();
		assertThat(o.method()).isEqualTo(ObservationMethod.API_COUNT);
		assertThat(o.records()).isZero();
	}

	@Test
	void filesTakeTheNewestLastModifiedAcrossHeadRequests() {
		HttpHeaders xls = Fixtures.headers("catalog/observation/head-xls-216-425.headers");
		HttpHeaders ods = Fixtures.headers("catalog/observation/head-ods-216-856.headers");
		Dataset boletin = dataset(216, false,
				file("application/vnd.ms-excel", "https://www.zaragoza.es/cont/paginas/estadistica/pdf/Apendice_Boletin1.xls"),
				file("application/vnd.oasis.opendocument.spreadsheet", "https://www.zaragoza.es/cont/paginas/estadistica/pdf/Apendice_Boletin1.ods"));
		server.expect(requestTo("https://www.zaragoza.es/cont/paginas/estadistica/pdf/Apendice_Boletin1.xls"))
				.andExpect(method(HttpMethod.HEAD)).andRespond(withSuccess().headers(xls));
		server.expect(requestTo("https://www.zaragoza.es/cont/paginas/estadistica/pdf/Apendice_Boletin1.ods"))
				.andExpect(method(HttpMethod.HEAD)).andRespond(withSuccess().headers(ods));
		Instant xlsModified = HttpDates.lastModified(xls.getFirst(HttpHeaders.LAST_MODIFIED));
		Instant odsModified = HttpDates.lastModified(ods.getFirst(HttpHeaders.LAST_MODIFIED));
		assertThat(xlsModified).isNotNull();
		assertThat(odsModified).isNotNull();

		Observation o = observer.observe(boletin);

		server.verify();
		assertThat(o.method()).isEqualTo(ObservationMethod.FILE_HEADERS);
		assertThat(o.lastChange()).isEqualTo(xlsModified.isAfter(odsModified) ? xlsModified : odsModified);
		assertThat(o.records()).isNull();
		assertThat(o.detail()).isEqualTo("2 fichero(s) consultado(s), 2 con Last-Modified");
		assertThat(o.url()).endsWith(xlsModified.isAfter(odsModified) ? ".xls" : ".ods");
	}

	@Test
	void fileHeadRequestsAreCapped() {
		List<Distribution> zips = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			zips.add(file("application/zip", "https://www.zaragoza.es/contenidos/archivo/AMZ_" + i + ".zip"));
		}
		server.expect(times(10), requestTo(startsWith("https://www.zaragoza.es/contenidos/archivo/AMZ_")))
				.andExpect(method(HttpMethod.HEAD))
				.andRespond(withSuccess().header(HttpHeaders.LAST_MODIFIED, "Wed, 13 Feb 2013 09:30:24 GMT"));

		Observation o = observer.observe(dataset(29, false, zips.toArray(Distribution[]::new)));

		server.verify();
		assertThat(o.method()).isEqualTo(ObservationMethod.FILE_HEADERS);
		assertThat(o.lastChange()).isEqualTo(Instant.parse("2013-02-13T09:30:24Z"));
		assertThat(o.detail()).isEqualTo("10 fichero(s) consultado(s), 10 con Last-Modified");
	}

	@Test
	void wfsHitsWhenTheDatasetOnlyHasGeoServices() {
		Dataset callejero = dataset(22, true, wms(), wfs("Vias"), wfs("Portales"));
		server.expect(requestTo(WFS_URBANISMO + "?service=WFS&version=2.0.0&request=GetFeature&typeNames=Vias&resultType=hits"))
				.andRespond(withSuccess(Fixtures.bytes("catalog/observation/wfs-hits-Vias.xml"), MediaType.APPLICATION_XML));

		Observation o = observer.observe(callejero);

		server.verify();
		assertThat(o.method()).isEqualTo(ObservationMethod.WFS_HITS);
		assertThat(o.records()).isEqualTo(3359);
		assertThat(o.lastChange()).isNull();
		assertThat(o.detail()).isEqualTo("typeNames=Vias");
	}

	@Test
	void fallsBackToWfsWhenTheApiEndpointDoesNotExist() {
		Dataset solares = dataset(1520, true, api("/sede/servicio/solar"), wms(), wfs("Solares"));
		server.expect(requestTo("https://www.zaragoza.es/sede/servicio/solar.json?rows=1&srsname=wgs84"))
				.andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.TEXT_HTML)
						.body("<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.0 Draft//EN\"><HTML><HEAD><TITLE>Error 404--Not Found</TITLE>"));
		server.expect(requestTo(WFS_URBANISMO + "?service=WFS&version=2.0.0&request=GetFeature&typeNames=Solares&resultType=hits"))
				.andRespond(withSuccess("<wfs:FeatureCollection numberMatched=\"12\" numberReturned=\"0\"/>",
						MediaType.APPLICATION_XML));

		Observation o = observer.observe(solares);

		server.verify();
		assertThat(o.method()).isEqualTo(ObservationMethod.WFS_HITS);
		assertThat(o.records()).isEqualTo(12);
	}

	@Test
	void redirectAndIntranetServicesAreReportedAsTheFirstFailure() {
		Dataset presupuesto = dataset(336, false, api("/sede/servicio/presupuesto"), wfs("MU1_lan"));
		server.expect(requestTo("https://www.zaragoza.es/sede/servicio/presupuesto.json?rows=1"))
				.andRespond(withStatus(HttpStatus.SEE_OTHER).header(HttpHeaders.LOCATION,
						"https://www.zaragoza.es/sede/portal/hacienda"));
		server.expect(requestTo(startsWith(WFS_URBANISMO)))
				.andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.TEXT_HTML).body("<html>403</html>"));

		Observation o = observer.observe(presupuesto);

		server.verify();
		assertThat(o.failed()).isTrue();
		assertThat(o.method()).isEqualTo(ObservationMethod.API_COUNT);
		assertThat(o.url()).isEqualTo("https://www.zaragoza.es/sede/servicio/presupuesto.json?rows=1");
		assertThat(o.error()).startsWith("HTTP 303").contains("https://www.zaragoza.es/sede/portal/hacienda");
	}

	@Test
	void ioFailuresAreRecordedNotThrown() {
		Dataset dataset = dataset(1, false, api("/sede/servicio/convenios"));
		server.expect(requestTo("https://www.zaragoza.es/sede/servicio/convenios.json?rows=1"))
				.andRespond(withException(new SocketTimeoutException("read timed out")));

		Observation o = observer.observe(dataset);

		assertThat(o.failed()).isTrue();
		assertThat(o.error()).startsWith("E/S: read timed out");
	}

	@Test
	void notObservableWithoutObservableDistributionsMakesNoRequest() {
		Dataset cartografia = dataset(29, false, wms(),
				new Distribution(110, "text/html", "https://www.zaragoza.es/ciudad/urbanismo/carto_planos.htm", null, "html", null),
				new Distribution(2865, "application/sparql-query", "https://www.zaragoza.es/sede/portal/datos-abiertos/servicio/sparql", null, "sparql", null));

		Observation o = observer.observe(cartografia);

		server.verify();
		assertThat(o.method()).isEqualTo(ObservationMethod.NOT_OBSERVABLE);
		assertThat(o.measured()).isFalse();
		assertThat(o.failed()).isFalse();
	}

	// --- fixtures de dominio ------------------------------------------------------------------------------------

	static Dataset dataset(int id, boolean geo, Distribution... distributions) {
		return new Dataset(id, "dataset " + id, null, null, null, null, "P0DT1S", null, "Finalizado", geo, true,
				false, null, List.of(distributions), NOW, NOW);
	}

	static Distribution api(String downloadUrl) {
		return new Distribution(1, "application/api", "https://www.zaragoza.es/docs-api_sede/#/Tag", downloadUrl,
				"api", null);
	}

	static Distribution file(String mediaType, String downloadUrl) {
		return new Distribution(2, mediaType, null, downloadUrl, "fichero", null);
	}

	static Distribution wfs(String layer) {
		return new Distribution(3, "application/vnd.ogc.wfs_xml",
				WFS_URBANISMO + "?version=1.1.1&&Request=GetCapabilities", null, "wfs", layer);
	}

	static Distribution wms() {
		return new Distribution(4, "application/vnd.ogc.wms_xml",
				"https://idezar-sig.zaragoza.es/servicios/geoserver/urbanismo/wms", null, "wms", null);
	}

	static Distribution solr() {
		return new Distribution(5, "application/solr", "https://www.zaragoza.es/ciudad/viapublica/movilidad/buscador_Incidencia",
				null, "buscador", null);
	}

}
