package es.zaragoza.observatory.citizen.infrastructure.zaragoza;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import es.zaragoza.observatory.citizen.CitizenSources;
import es.zaragoza.observatory.citizen.application.RegisterServiceRequests;
import es.zaragoza.observatory.citizen.domain.DistrictAssignment;
import es.zaragoza.observatory.citizen.domain.ServiceRequest;
import es.zaragoza.observatory.citizen.domain.ServiceRequestStatus;
import es.zaragoza.observatory.citizen.infrastructure.CitizenProperties;
import es.zaragoza.observatory.citizen.support.FakeGeo;
import es.zaragoza.observatory.citizen.support.InMemoryServiceRequests;
import es.zaragoza.observatory.ingestion.Ingestion;
import es.zaragoza.observatory.ingestion.IngestionJob;
import es.zaragoza.observatory.ingestion.IngestionRunSummary;
import es.zaragoza.observatory.ingestion.RunStatus;
import es.zaragoza.observatory.ingestion.SourceDescriptor;
import es.zaragoza.observatory.ingestion.SourceDescriptor.Pagination.Mode;
import es.zaragoza.observatory.ingestion.SourceDescriptor.ResponseShape;
import es.zaragoza.observatory.shared.DatasetRef;
import es.zaragoza.observatory.shared.IngestionRunId;
import tools.jackson.databind.json.JsonMapper;

/**
 * Qué se le pide exactamente a la API. Aquí se rompen a la vez las cosas que S2.2 dejó fijadas si alguien las
 * toca: la proyección de ADR-012, el {@code sort} explícito, la marca de agua y —lo más fácil de romper sin
 * darse cuenta— <b>el reparto de papeles entre los dos ejes</b>: la carga completa solo puede ir por
 * {@code requested_datetime}, porque por {@code updated_datetime} un barrido entero pierde 8.804 de los 89.432
 * registros (S2.2 §10).
 */
class ServiceRequestsIngestionJobTest {

	static final URI URL = URI.create("https://www.zaragoza.es/sede/servicio/quejas-sugerencias/list.json");
	static final String FIELDS = "service_request_id,status,service_code,service_name,requested_datetime,"
			+ "updated_datetime,geometry,district";
	static final Instant NOW = Instant.parse("2026-09-08T12:00:00Z");

	final CitizenProperties properties = new CitizenProperties(URL, FIELDS, 500, Duration.ofDays(1),
			Duration.ofHours(6));
	final InMemoryServiceRequests requests = new InMemoryServiceRequests();
	final ServiceRequestJsonTranslator translator = new ServiceRequestJsonTranslator(JsonMapper.shared());
	final RegisterServiceRequests register = new RegisterServiceRequests(requests, new FakeGeo());
	final StubIngestion ingestion = new StubIngestion();
	final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

	NewRequestsIngestionJob newRequests() {
		return new NewRequestsIngestionJob(properties, requests, translator, register, ingestion);
	}

	ClosedRequestsIngestionJob closures() {
		return new ClosedRequestsIngestionJob(properties, requests, translator, register, ingestion, clock);
	}

	@Test
	void theFirstRunOfTheNewRequestsJobSweepsTheWholeHistory() {
		SourceDescriptor source = newRequests().source();

		assertThat(source.dataset()).isEqualTo(CitizenSources.REQUESTS);
		assertThat(source.url()).isEqualTo(URL);
		assertThat(source.shape()).isEqualTo(ResponseShape.ARRAY);
		assertThat(source.pagination().mode()).isEqualTo(Mode.OFFSET);
		assertThat(source.pagination().rows()).isEqualTo(500);
		assertThat(source.query()).containsEntry("srsname", "wgs84").containsEntry("fl", FIELDS)
				.containsEntry("sort", "requested_datetime asc").doesNotContainKey("q");
	}

	@Test
	void afterCompletingASweepItAsksOnlyForWhatIsNew() {
		requests.upsertAll(List.of(stored(1, "2026-09-07T10:00:00Z", null)), NOW);
		ingestion.succeeded(CitizenSources.REQUESTS);

		// La marca menos el margen de seguridad de 6 h: las fechas del origen no llevan zona y el filtro se manda
		// con Z, así que se retrocede lo bastante para que esa duda no pueda saltarse registros (S2.2).
		assertThat(newRequests().source().query()).containsEntry("q", "requested_datetime=ge=2026-09-07T04:00:00Z");
	}

	@Test
	void aPopulatedTableIsNotEnoughToSkipTheFullSweep() {
		// El fallo real del 2026-09-08: el job de cierres llenó la tabla primero y el de altas dio por hecho que
		// tenía el histórico. Lo que habilita la marca de agua es el registro de ejecuciones propio, no la tabla.
		requests.upsertAll(List.of(stored(1, "2026-09-07T10:00:00Z", "2026-09-07T11:00:00Z")), NOW);
		ingestion.succeeded(CitizenSources.CLOSURES);

		assertThat(newRequests().source().query())
				.as("el éxito del otro eje no dice nada del histórico de este").doesNotContainKey("q");
	}

	@Test
	void theClosuresJobNeverSweepsTheWholeListing() {
		// Con la tabla vacía arranca desde ahora, no desde 2013: por este eje un barrido completo pierde 8.804
		// registros de 89.432 (S2.2 §10), y el histórico ya lo trae el job de altas.
		SourceDescriptor cold = closures().source();
		assertThat(cold.dataset()).isEqualTo(CitizenSources.CLOSURES);
		assertThat(cold.query()).containsEntry("sort", "updated_datetime asc")
				.containsEntry("q", "updated_datetime=ge=2026-09-08T06:00:00Z");

		requests.upsertAll(List.of(stored(1, "2026-09-01T10:00:00Z", "2026-09-06T12:00:00Z")), NOW);
		assertThat(closures().source().query()).containsEntry("q", "updated_datetime=ge=2026-09-06T06:00:00Z");
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
				DistrictAssignment.withoutPoint(null, null), Instant.parse(requestedAt), Instant.parse(requestedAt));
	}

	/** Registro de ejecuciones de mentira: solo hace falta saber qué datasets han terminado alguna vez. */
	static final class StubIngestion implements Ingestion {

		private final List<DatasetRef> completed = new ArrayList<>();

		void succeeded(DatasetRef dataset) {
			completed.add(dataset);
		}

		@Override
		public IngestionRunSummary run(IngestionJob job) {
			throw new UnsupportedOperationException("este doble no ejecuta ingestas");
		}

		@Override
		public Optional<IngestionRunSummary> lastSuccessful(DatasetRef dataset) {
			if (!completed.contains(dataset)) {
				return Optional.empty();
			}
			return Optional.of(new IngestionRunSummary(IngestionRunId.newId(), dataset, NOW, NOW,
					RunStatus.SUCCEEDED, 1, 1, null, null));
		}

		@Override
		public List<IngestionRunSummary> history(DatasetRef dataset, int limit) {
			return List.of();
		}
	}

}
