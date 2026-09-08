package es.zaragoza.observatory.citizen.infrastructure.zaragoza;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import es.zaragoza.observatory.citizen.CitizenSources;
import es.zaragoza.observatory.citizen.application.RegisterServiceRequests;
import es.zaragoza.observatory.citizen.domain.ServiceRequest;
import es.zaragoza.observatory.citizen.domain.ServiceRequestStatus;
import es.zaragoza.observatory.citizen.infrastructure.CitizenProperties;
import es.zaragoza.observatory.citizen.support.FakeGeo;
import es.zaragoza.observatory.citizen.support.InMemoryServiceRequests;
import es.zaragoza.observatory.ingestion.SourceDescriptor;
import es.zaragoza.observatory.ingestion.SourceDescriptor.Pagination.Mode;
import es.zaragoza.observatory.ingestion.SourceDescriptor.ResponseShape;
import tools.jackson.databind.json.JsonMapper;

/**
 * Qué se le pide exactamente a la API. Aquí se rompen a la vez las tres cosas que S2.2 dejó fijadas si alguien
 * las toca: la proyección de ADR-012, el {@code sort} explícito —sin él la paginación por offset se salta
 * registros sin avisar— y la marca de agua que hace incremental la ingesta.
 */
class ServiceRequestsIngestionJobTest {

	static final URI URL = URI.create("https://www.zaragoza.es/sede/servicio/quejas-sugerencias/list.json");
	static final String FIELDS = "service_request_id,status,service_code,service_name,requested_datetime,"
			+ "updated_datetime,geometry,district";

	final CitizenProperties properties = new CitizenProperties(URL, FIELDS, 500, Duration.ofDays(1),
			Duration.ofHours(6));
	final InMemoryServiceRequests requests = new InMemoryServiceRequests();
	final ServiceRequestJsonTranslator translator = new ServiceRequestJsonTranslator(JsonMapper.shared());
	final RegisterServiceRequests register = new RegisterServiceRequests(requests, new FakeGeo());

	NewRequestsIngestionJob newRequests() {
		return new NewRequestsIngestionJob(properties, requests, translator, register);
	}

	ClosedRequestsIngestionJob closures() {
		return new ClosedRequestsIngestionJob(properties, requests, translator, register);
	}

	@Test
	void theFirstRunHasNoWatermarkAndSweepsTheWholeHistory() {
		SourceDescriptor source = newRequests().source();

		assertThat(source.dataset()).isEqualTo(CitizenSources.REQUESTS);
		assertThat(source.url()).isEqualTo(URL);
		assertThat(source.shape()).isEqualTo(ResponseShape.ARRAY);
		assertThat(source.pagination().mode()).isEqualTo(Mode.OFFSET);
		assertThat(source.pagination().rows()).isEqualTo(500);
		assertThat(source.query()).containsEntry("srsname", "wgs84").containsEntry("fl", FIELDS)
				.containsEntry("sort", "requested_datetime asc").doesNotContainKey("q");
		// La URL completa que sale de aquí se comprueba en ZaragozaHttpClientTest, que es donde vive buildUri.
	}

	@Test
	void afterTheFirstRunItAsksOnlyForWhatIsNew() {
		requests.upsertAll(List.of(stored(1, "2026-09-07T10:00:00Z", null)), Instant.now());

		SourceDescriptor source = newRequests().source();
		// La marca menos el margen de seguridad de 6 h: las fechas del origen no llevan zona y el filtro se manda
		// con Z, así que se retrocede lo bastante para que esa duda no pueda saltarse registros (S2.2).
		assertThat(source.query()).containsEntry("q", "requested_datetime=ge=2026-09-07T04:00:00Z");
	}

	@Test
	void theClosuresJobWalksTheOtherAxis() {
		requests.upsertAll(List.of(stored(1, "2026-09-01T10:00:00Z", "2026-09-06T12:00:00Z")), Instant.now());

		SourceDescriptor source = closures().source();
		assertThat(source.dataset()).isEqualTo(CitizenSources.CLOSURES);
		assertThat(source.query()).containsEntry("sort", "updated_datetime asc")
				.containsEntry("q", "updated_datetime=ge=2026-09-06T06:00:00Z");
	}

	@Test
	void theTwoJobsAreDistinctDatasetsOnTheSameEndpoint() {
		assertThat(newRequests().source().dataset()).isNotEqualTo(closures().source().dataset());
		assertThat(newRequests().source().url()).isEqualTo(closures().source().url());
		assertThat(newRequests().name()).isEqualTo("sede:quejas-sugerencias");
		assertThat(closures().name()).isEqualTo("sede:quejas-sugerencias-cierres");
	}

	@Test
	void theProjectionNeverCarriesFreeText() {
		for (SourceDescriptor source : List.of(newRequests().source(), closures().source())) {
			assertThat(source.query().get("fl")).doesNotContain("title").doesNotContain("description")
					.doesNotContain("service_notice").doesNotContain("address_string");
		}
	}

	private static ServiceRequest stored(long id, String requestedAt, String updatedAt) {
		return new ServiceRequest(id, ServiceRequestStatus.CLOSED, "250", "Acera", Instant.parse(requestedAt),
				updatedAt == null ? null : Instant.parse(updatedAt), null,
				es.zaragoza.observatory.citizen.domain.DistrictAssignment.withoutPoint(null, null),
				Instant.parse(requestedAt), Instant.parse(requestedAt));
	}

}
