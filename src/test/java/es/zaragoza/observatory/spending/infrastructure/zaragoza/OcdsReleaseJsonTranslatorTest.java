package es.zaragoza.observatory.spending.infrastructure.zaragoza;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import es.zaragoza.observatory.spending.domain.Contract;
import es.zaragoza.observatory.spending.domain.PartyIdentity;
import es.zaragoza.observatory.spending.domain.ReleaseContent;
import es.zaragoza.observatory.spending.domain.Stage;
import es.zaragoza.observatory.support.Fixtures;
import tools.jackson.databind.json.JsonMapper;

/**
 * El release package sobre los documentos reales que grabó S3.1: uno con solo licitación y otro con contrato,
 * más los que quedaron de S0.2. Aquí se comprueba lo que ADR-017 decidió, no lo que el traductor hace.
 */
class OcdsReleaseJsonTranslatorTest {

	final OcdsReleaseJsonTranslator translator = new OcdsReleaseJsonTranslator(JsonMapper.builder().build());

	@Test
	void traduceUnaLicitacionViva() {
		ReleaseContent content = translate("ocds/contracting-process-tender.json");

		assertThat(content.publishedAt()).isEqualTo(Instant.parse("2008-05-21T11:14:39Z"));
		assertThat(content.tags()).isEqualTo("tender");
		assertThat(content.tender().status()).isEqualTo("active");
		assertThat(content.tender().isActive()).isTrue();
		assertThat(content.tender().category()).isEqualTo("works");
		assertThat(content.tender().procurementMethod()).isEqualTo("open");
		assertThat(content.procuringEntityName()).isEqualTo("Ayuntamiento de Zaragoza");
		assertThat(content.procuringEntityId()).isEqualTo("L01502973");
		assertThat(content.awards()).isEmpty();
		assertThat(content.contracts()).isEmpty();
		// ADR-017 §3: el texto libre se guarda, porque es lo que dice qué se contrató.
		assertThat(content.tender().title()).contains("CLIMATIZACIÓN EN CENTRO CÍVICO RÍO EBRO");

		assertThat(Stage.derive(content.tender().status(), false)).isEqualTo(Stage.PLANNED);
	}

	@Test
	void traduceUnProcesoConContratoQueEsUnaCascara() {
		ReleaseContent content = translate("ocds/contracting-process-contract.json");

		assertThat(content.tender().status()).isEqualTo("complete");
		assertThat(content.tender().value().amount()).isEqualByComparingTo(new BigDecimal("440379.0"));
		assertThat(content.tender().value().currency()).isEqualTo("EUR");
		assertThat(content.contracts()).hasSize(1);

		Contract contract = content.contracts().get(0);
		assertThat(contract.contractId()).isEqualTo("8-contract");
		assertThat(contract.isEmptyShell()).as("uno de los 1.560 (S3.1 §4)").isTrue();
		assertThat(contract.signedOn()).isNull();
		// Y por tanto: proceso completo, sin etapa. Ponerle COMMITTED sería una conclusión (ADR-017 §6).
		assertThat(Stage.derive(content.tender().status(), contract.isSigned())).isNull();
	}

	@Test
	void elNifDelAdjudicatarioSeExtraeYElIdentificadorCrudoNoSeCopia() {
		ReleaseContent content = translate("ocds/contracting-process-ocds-1xraxc-6621-ContractingProcess.json");

		assertThat(content.awards()).hasSize(1);
		var award = content.awards().get(0);
		assertThat(award.awardId()).isEqualTo("65236-award");
		assertThat(award.status()).isEqualTo("active");
		assertThat(award.value().amount()).isEqualByComparingTo(new BigDecimal("106471.8"));

		PartyIdentity supplier = award.parties().get(0);
		assertThat(supplier.taxId()).isEqualTo("B50892819");
		assertThat(supplier.name()).isEqualTo("MARIANO-ESTAGE-SL");
		assertThat(supplier.naturalPerson()).isFalse();

		// Un contrato de verdad, con fecha de firma: este sí sostiene la etapa.
		Contract contract = content.contracts().get(0);
		assertThat(contract.isSigned()).isTrue();
		assertThat(contract.awardId()).isEqualTo("65236-award");
		assertThat(contract.isEmptyShell()).isFalse();
		assertThat(Stage.derive(content.tender().status(), contract.isSigned())).isEqualTo(Stage.COMMITTED);

		// Los CPV salen de los artículos, con la clasificación principal marcada.
		assertThat(content.cpvs()).extracting("code").contains("45232150", "45232410");
		assertThat(content.cpvs()).filteredOn("main", true).extracting("code").containsExactly("45232150");
	}

	@Test
	void unPackageSinReleasesNoEsUnError() {
		// 16 packages responden 200 con `releases` vacío: un 200 no garantiza release (S3.1 §3).
		assertThat(translator.translate("ocds-1", "{\"publishedDate\":\"2020-01-01T00:00:00Z\",\"releases\":[]}"))
				.isNull();
		assertThat(translator.translate("ocds-1", "{\"publishedDate\":\"2020-01-01T00:00:00Z\"}")).isNull();
	}

	@Test
	void ningunPackageDelSpikeTraeMasDeUnRelease() {
		// 5.606 traen exactamente uno y ninguno trae más: no hace falta implementar compiled releases.
		for (String fixture : new String[] { "ocds/contracting-process-tender.json",
				"ocds/contracting-process-contract.json",
				"ocds/contracting-process-ocds-1xraxc-6723-ContractingProcess.json",
				"ocds/contracting-process-ocds-1xraxc-6867-ContractingProcess.json" }) {
			assertThat(translate(fixture)).as(fixture).isNotNull();
		}
	}

	private ReleaseContent translate(String fixture) {
		return translator.translate("ocds-test", Fixtures.text(fixture));
	}

}
