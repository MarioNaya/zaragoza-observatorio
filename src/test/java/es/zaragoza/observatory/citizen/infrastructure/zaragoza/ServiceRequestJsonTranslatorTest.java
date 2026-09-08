package es.zaragoza.observatory.citizen.infrastructure.zaragoza;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import es.zaragoza.observatory.citizen.domain.ServiceRequestDraft;
import es.zaragoza.observatory.citizen.domain.ServiceRequestStatus;
import es.zaragoza.observatory.support.Fixtures;
import tools.jackson.databind.json.JsonMapper;

/**
 * El traductor sobre la respuesta real grabada por S2.2 ({@code fl} de ocho campos, 500 registros).
 * <p>
 * El test que más importa es {@link #neverReadsTheFreeText()}: la segunda mitad de la garantía de ADR-012. No
 * pedir el texto lo evita mientras la API respete {@code fl}; no leerlo lo evita también si algún día deja de
 * respetarlo.
 */
class ServiceRequestJsonTranslatorTest {

	static final String PAGE = "open311/sede-list-ingest-page.json";

	final ServiceRequestJsonTranslator translator = new ServiceRequestJsonTranslator(JsonMapper.shared());

	@Test
	void translatesTheRecordedPage() {
		List<ServiceRequestDraft> drafts = translator.translate(Fixtures.text(PAGE));

		assertThat(drafts).hasSize(500);
		assertThat(drafts).allSatisfy(draft -> {
			assertThat(draft.sourceId()).isPositive();
			assertThat(draft.serviceCode()).isNotBlank();
			assertThat(draft.requestedAt()).isNotNull();
		});
		// Las cerradas traen updated_datetime y las abiertas casi nunca (S0.3: se informa al cerrar).
		assertThat(drafts).anySatisfy(draft -> assertThat(draft.status()).isEqualTo(ServiceRequestStatus.CLOSED));
		assertThat(drafts).anySatisfy(draft -> assertThat(draft.status()).isEqualTo(ServiceRequestStatus.OPEN));
		assertThat(drafts.stream().filter(d -> d.status() == ServiceRequestStatus.CLOSED))
				.allSatisfy(draft -> assertThat(draft.updatedAt()).isNotNull());
		// Cobertura territorial parcial, que es el hecho central de esta fuente (S2.2 §6).
		assertThat(drafts.stream().filter(d -> d.point() != null)).isNotEmpty();
		assertThat(drafts.stream().filter(d -> d.point() == null)).isNotEmpty();
		assertThat(drafts.stream().filter(d -> d.declaredDistrict() != null)).isNotEmpty();
		// Los puntos están en Zaragoza y en el orden lon/lat de srsname=wgs84 (S0.5).
		assertThat(drafts.stream().filter(d -> d.point() != null)).allSatisfy(draft -> {
			assertThat(draft.point().lon()).isBetween(-1.3, -0.6);
			assertThat(draft.point().lat()).isBetween(41.4, 42.0);
		});
	}

	@Test
	void neverReadsTheFreeText() {
		// El origen sin `fl` devuelve title, description y service_notice: si algún día ignorase la proyección,
		// el traductor tiene que seguir sin recogerlos (ADR-012).
		String withText = """
				[{"service_request_id":947147,"status":"closed","service_code":"250","service_name":"Acera",
				  "requested_datetime":"2026-09-01T10:00:00","updated_datetime":"2026-09-02T10:00:00",
				  "title":"Nombre Apellido, 12345678Z",
				  "description":"Soy vecina de la calle X. Atentamente, Nombre Apellido",
				  "service_notice":"respuesta con datos personales",
				  "district":"DELICIAS"}]
				""";
		List<ServiceRequestDraft> drafts = translator.translate(withText);

		assertThat(drafts).hasSize(1);
		ServiceRequestDraft draft = drafts.get(0);
		assertThat(draft.toString())
				.as("ningún campo del registro traducido puede contener texto escrito por el ciudadano")
				.doesNotContain("Nombre Apellido", "12345678Z", "Atentamente", "vecina", "respuesta con datos");
		assertThat(draft.declaredDistrict()).isEqualTo("DELICIAS");
		assertThat(ServiceRequestJsonTranslator.CITIZEN_TEXT_FIELDS)
				.containsExactlyInAnyOrder("title", "description", "service_notice");
	}

	@Test
	void skipsRecordsWithoutTheMinimum() {
		String body = """
				[{"status":"open","service_code":"1","requested_datetime":"2026-09-01T10:00:00"},
				 {"service_request_id":2,"service_code":"1"},
				 {"service_request_id":3,"requested_datetime":"2026-09-01T10:00:00"},
				 {"service_request_id":4,"service_code":"1","requested_datetime":"2026-09-01T10:00:00"}]
				""";
		assertThat(translator.translate(body)).singleElement()
				.satisfies(draft -> assertThat(draft.sourceId()).isEqualTo(4));
	}

	@Test
	void readsLocalTimeAsZaragozaTime() {
		String body = """
				[{"service_request_id":9,"status":"closed","service_code":"1",
				  "requested_datetime":"2026-01-15T09:30:00","updated_datetime":"2026-01-16T09:30:00"}]
				""";
		ServiceRequestDraft draft = translator.translate(body).get(0);
		// 09:30 en Zaragoza en enero (CET, UTC+1) son las 08:30 UTC.
		assertThat(draft.requestedAt()).isEqualTo(Instant.parse("2026-01-15T08:30:00Z"));
	}

	@Test
	void ignoresGeometriesThatAreNotPoints() {
		String body = """
				[{"service_request_id":9,"service_code":"1","requested_datetime":"2026-09-01T10:00:00",
				  "geometry":{"type":"LineString","coordinates":[[-0.88,41.65],[-0.89,41.66]]}},
				 {"service_request_id":10,"service_code":"1","requested_datetime":"2026-09-01T10:00:00",
				  "geometry":{"type":"Point","coordinates":[999,41.65]}}]
				""";
		assertThat(translator.translate(body)).hasSize(2)
				.allSatisfy(draft -> assertThat(draft.point()).isNull());
	}

	@Test
	void readsTheThreeStatusesTheSourcePublishesAndFlagsTheRest() {
		// `rejected` no salió en los 7.000 registros muestreados por S2.2: apareció una sola vez en la carga
		// completa de 89.432. Lo que hizo que no se perdiera fue el valor UNKNOWN, que sigue estando para el
		// siguiente valor inesperado.
		String body = """
				[{"service_request_id":1,"status":"open","service_code":"1","requested_datetime":"2026-09-01T10:00:00"},
				 {"service_request_id":2,"status":"closed","service_code":"1","requested_datetime":"2026-09-01T10:00:00"},
				 {"service_request_id":3,"status":"rejected","service_code":"1","requested_datetime":"2026-09-01T10:00:00"},
				 {"service_request_id":4,"status":"inventado","service_code":"1","requested_datetime":"2026-09-01T10:00:00"},
				 {"service_request_id":5,"service_code":"1","requested_datetime":"2026-09-01T10:00:00"}]
				""";
		assertThat(translator.translate(body)).extracting(ServiceRequestDraft::status)
				.containsExactly(ServiceRequestStatus.OPEN, ServiceRequestStatus.CLOSED,
						ServiceRequestStatus.REJECTED, ServiceRequestStatus.UNKNOWN, ServiceRequestStatus.UNKNOWN);
	}

	@Test
	void rejectsAnythingThatIsNotAnArray() {
		assertThatThrownBy(() -> translator.translate("{\"totalCount\":1,\"result\":[]}"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("expected a JSON array");
	}

}
