package es.zaragoza.observatory.spending.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * La regla de ADR-018 §5: el título dice para qué era la ayuda y se guarda; el documento de identidad que la
 * fuente mete dentro, no.
 */
class GrantTitleTest {

	@Test
	void quitaElDniYDejaElRestoDelTitulo() {
		var redacted = GrantTitle.redact("AYUDA REHABILITACION FINCA 2020 LINEA 2, EXPTE 12345678Z");

		assertThat(redacted.redacted()).isTrue();
		assertThat(redacted.text()).isEqualTo("AYUDA REHABILITACION FINCA 2020 LINEA 2, EXPTE "
				+ GrantTitle.MARKER);
		assertThat(GrantTitle.carriesIdentity(redacted.text())).isFalse();
	}

	@Test
	void quitaTambienElNie() {
		var redacted = GrantTitle.redact("1/2020, NIF X1234567L");

		assertThat(redacted.redacted()).isTrue();
		assertThat(redacted.text()).isEqualTo("1/2020, NIF " + GrantTitle.MARKER);
	}

	/**
	 * Por <b>forma</b>, no por validez: una letra de control incorrecta sigue siendo un documento de identidad
	 * mal escrito, y guardarlo por eso sería exactamente el fallo que esta regla evita.
	 */
	@Test
	void redactaAunqueLaLetraDeControlSeaIncorrecta() {
		assertThat(GrantTitle.redact("EXPTE 12345678A").redacted()).isTrue();
	}

	@Test
	void noTocaUnTituloQueNoLleveIdentidad() {
		var redacted = GrantTitle.redact("  SUBVENCIONES A LAS ASOCIACIONES DE VECINOS DE ALMOZARA  ");

		assertThat(redacted.redacted()).isFalse();
		assertThat(redacted.text()).isEqualTo("SUBVENCIONES A LAS ASOCIACIONES DE VECINOS DE ALMOZARA");
	}

	@Test
	void unTituloVacioNoEsUnTituloRedactado() {
		assertThat(GrantTitle.redact(null).text()).isNull();
		assertThat(GrantTitle.redact("   ").redacted()).isFalse();
		assertThat(GrantTitle.redact("   ").text()).isNull();
	}

	/** Los años sueltos y los expedientes no casan: ocho dígitos y una letra, no cualquier número. */
	@Test
	void noConfundeUnExpedienteConUnDocumento() {
		assertThat(GrantTitle.redact("EXPEDIENTE 0618143/2014").redacted()).isFalse();
		assertThat(GrantTitle.redact("LINEA 2.2 OBRAS 2026").redacted()).isFalse();
	}

	@Test
	void elModeloImpideConstruirUnaConcesionConIdentidadEnElTitulo() {
		assertThatThrownBy(() -> new Grant(1, null, "EXPTE 12345678Z", false, null, null, null, null, null, null,
				null, null)).isInstanceOf(IllegalArgumentException.class)
						.hasMessageContaining("identity document");
	}

}
