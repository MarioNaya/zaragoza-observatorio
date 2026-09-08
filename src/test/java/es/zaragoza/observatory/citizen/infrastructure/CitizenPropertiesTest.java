package es.zaragoza.observatory.citizen.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * La proyección de ADR-012 no es una convención: es una invariante que se comprueba al arrancar. Si alguien
 * añade el texto libre a {@code zaragoza.citizen.fields}, la aplicación no arranca.
 */
class CitizenPropertiesTest {

	static final URI URL = URI.create("https://www.zaragoza.es/sede/servicio/quejas-sugerencias/list.json");
	static final String FIELDS = "service_request_id,status,service_code,service_name,requested_datetime,"
			+ "updated_datetime,geometry,district";

	@Test
	void acceptsTheProjectionOfAdr012() {
		var properties = new CitizenProperties(URL, FIELDS, 500, Duration.ofDays(1), Duration.ofHours(6));
		assertThat(properties.fields()).doesNotContain("title", "description", "service_notice");
		assertThat(properties.rows()).isEqualTo(500);
	}

	@ParameterizedTest(name = "rechaza {0}")
	@ValueSource(strings = { "service_request_id,title", "service_request_id, description ",
			"SERVICE_CODE,SERVICE_NOTICE", "title" })
	void rejectsAnyProjectionThatAsksForCitizenText(String fields) {
		assertThatThrownBy(() -> new CitizenProperties(URL, fields, 500, Duration.ofDays(1), Duration.ofHours(6)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("ADR-012");
	}

	@Test
	void rejectsNonsenseValues() {
		assertThatThrownBy(() -> new CitizenProperties(URL, FIELDS, 0, Duration.ofDays(1), Duration.ofHours(6)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CitizenProperties(URL, FIELDS, 500, Duration.ofDays(1), Duration.ofHours(-1)))
				.isInstanceOf(IllegalArgumentException.class);
	}

}
