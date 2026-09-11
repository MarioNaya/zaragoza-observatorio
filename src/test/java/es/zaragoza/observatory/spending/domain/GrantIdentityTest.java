package es.zaragoza.observatory.spending.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * La regla de ADR-018 §4, que es la decisión de esta fuente: seudónimo siempre, identidad solo si no es una
 * persona física, y persona física por <b>cualquiera</b> de las dos señales.
 */
class GrantIdentityTest {

	@ParameterizedTest
	@ValueSource(strings = { "***332**", "***720**", "*********", "12***45**" })
	void reconoceElIdentificadorEnmascaradoConElQueLaFuenteMarcaAUnaPersona(String masked) {
		assertThat(GrantIdentity.isMaskedIdentifier(masked)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "G50423219", "A50005602", "B50892819", "NIF", "" })
	void noConfundeConEnmascaradoLoQueNoLoEs(String value) {
		assertThat(GrantIdentity.isMaskedIdentifier(value)).isFalse();
	}

	@Test
	void personaFisicaEsLaUnionDeLasDosSenales() {
		// Lo que dice el directorio.
		assertThat(GrantIdentity.isNaturalPerson("personas-fisicas", false)).isTrue();
		// Lo que dice el identificador, aunque el directorio clasifique de otra forma: son 107 casos.
		assertThat(GrantIdentity.isNaturalPerson("otros", true)).isTrue();
		// Y solo si ninguna lo dice, no lo es.
		assertThat(GrantIdentity.isNaturalPerson("entidad-deportiva", false)).isFalse();
	}

	@Test
	void deUnaPersonaFisicaNoEntraNiNombreNiIdentificador() {
		assertThat(GrantIdentity.name("Datos de caracter personal", true)).isNull();
		assertThat(GrantIdentity.name("UN NOMBRE CUALQUIERA", true)).isNull();
		assertThat(GrantIdentity.legalNif("G50423219", true)).isNull();
		assertThat(GrantIdentity.legalNif("***332**", true)).isNull();
	}

	@Test
	void deUnaEntidadEntranElNombreYElNifPeroNoLosMarcadoresDeLaFuente() {
		assertThat(GrantIdentity.name("ASOCIACION DE VECINOS", false)).isEqualTo("ASOCIACION DE VECINOS");
		assertThat(GrantIdentity.legalNif("G50423219", false)).isEqualTo("G50423219");
		// Los dos textos constantes con los que la fuente rellena lo que no publica son ruido, no datos.
		assertThat(GrantIdentity.name("RAZÓN SOCIAL", false)).isNull();
		assertThat(GrantIdentity.name("Datos de caracter personal", false)).isNull();
		assertThat(GrantIdentity.legalNif("NIF", false)).isNull();
		// Y un DNI en claro nunca es un NIF de persona jurídica.
		assertThat(GrantIdentity.legalNif("12345678Z", false)).isNull();
		assertThat(GrantIdentity.legalNif("***332**", false)).isNull();
	}

	@Test
	void elModeloImpideConstruirUnaPersonaFisicaConIdentidad() {
		assertThatThrownBy(() -> new GrantBeneficiary("626", "UN NOMBRE", null, true, "personas-fisicas"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("natural person");
		assertThatThrownBy(() -> new GrantBeneficiary("626", null, "G50423219", true, "personas-fisicas"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(new GrantBeneficiary("626", null, null, true, "personas-fisicas").naturalPerson()).isTrue();
	}

}
