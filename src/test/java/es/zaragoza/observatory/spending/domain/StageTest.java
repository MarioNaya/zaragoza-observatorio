package es.zaragoza.observatory.spending.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * ADR-017 §6: la etapa solo se deriva donde el documento la sostiene. Es la diferencia entre 3.410 procesos
 * comprometidos y 4.970, y la de 4.970 saldría de deducir que un contrato sin fecha de firma se firmó.
 */
class StageTest {

	@Test
	void comprometidoSoloConUnContratoFirmado() {
		assertThat(Stage.derive("complete", true)).isEqualTo(Stage.COMMITTED);
	}

	@Test
	void licitacionActivaEsPlanificado() {
		assertThat(Stage.derive("active", false)).isEqualTo(Stage.PLANNED);
	}

	@Test
	void unContratoQueEsUnaCascaraNoSostieneNingunaEtapa() {
		// Los 1.560: proceso completo, contrato con identificador y nada más. Sin etapa, y así se publica.
		assertThat(Stage.derive("complete", false)).isNull();
		assertThat(Stage.derive("unsuccessful", false)).isNull();
		assertThat(Stage.derive("cancelled", false)).isNull();
		assertThat(Stage.derive(null, false)).isNull();
	}

	@Test
	void unaLicitacionActivaConContratoFirmadoYaEstaComprometida() {
		assertThat(Stage.derive("active", true)).isEqualTo(Stage.COMMITTED);
	}

}
