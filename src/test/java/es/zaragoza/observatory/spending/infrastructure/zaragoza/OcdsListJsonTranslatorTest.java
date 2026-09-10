package es.zaragoza.observatory.spending.infrastructure.zaragoza;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

import es.zaragoza.observatory.support.Fixtures;
import tools.jackson.databind.json.JsonMapper;

/**
 * El censo, con las dos formas que este endpoint tiene de responder (S3.1 §2) y grabadas por el spike.
 */
class OcdsListJsonTranslatorTest {

	final OcdsListJsonTranslator translator = new OcdsListJsonTranslator(JsonMapper.builder().build());

	@Test
	void traduceElArrayDeOcids() {
		var ocids = translator.translate(Fixtures.text("ocds/contracting-process-list-rows2.json"));

		assertThat(ocids).containsExactly("ocds-1xraxc-8148-ContractingProcess",
				"ocds-1xraxc-8142-ContractingProcess");
	}

	@Test
	void aceptaElEnvoltorioVacio() {
		// El mismo endpoint devuelve el envoltorio de la sede en vez de un array cuando no hay resultados. Un
		// traductor que solo espere el array revienta con la primera respuesta vacía.
		assertThat(translator.translate(Fixtures.text("ocds/contracting-process-list-empty.json"))).isEmpty();
	}

	@Test
	void cuentaOcidsDistintosNoElementos() {
		String repeated = """
				[{"ocid":"ocds-1xraxc-1-ContractingProcess","id":"1"},
				 {"ocid":"ocds-1xraxc-1-ContractingProcess","id":"1"},
				 {"ocid":"ocds-1xraxc-2-ContractingProcess","id":"2"}]
				""";

		assertThat(translator.translate(repeated)).containsExactly("ocds-1xraxc-1-ContractingProcess",
				"ocds-1xraxc-2-ContractingProcess");
	}

	@Test
	void descartaLosElementosSinOcidSinTirarElResto() {
		String mixed = """
				[{"id":"sin ocid"},{"ocid":"","id":"vacío"},{"ocid":"ocds-1xraxc-3-ContractingProcess","id":"3"}]
				""";

		assertThat(translator.translate(mixed)).containsExactly("ocds-1xraxc-3-ContractingProcess");
	}

	@Test
	void unaRespuestaQueNoSeEntiendeHaceFallarLaIngesta() {
		// Salvaguarda de ADR-013 §2: tragarse una respuesta rara haría que una ingesta rota pareciera correcta.
		assertThatIllegalArgumentException()
				.isThrownBy(() -> translator.translate("{\"totalCount\":8001,\"mensaje\":\"error\"}"));
		assertThatIllegalArgumentException().isThrownBy(() -> translator.translate("\"una cadena\""));
	}

	@Test
	void elListadoRealDelSpikeSeTraduceEntero() {
		var ocids = translator.translate(Fixtures.text("ocds/contracting-process-list.json"));

		assertThat(ocids).isNotEmpty().doesNotHaveDuplicates()
				.allSatisfy(ocid -> assertThat(ocid).startsWith("ocds-"));
	}

}
