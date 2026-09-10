package es.zaragoza.observatory.spending.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * La regla de ADR-017 §2, que es la decisión de datos personales de esta fuente y por eso se prueba aparte del
 * traductor: el NIF de persona jurídica entra, el de persona física no entra <b>ni con su nombre</b>.
 */
class PartyIdentityTest {

	@Test
	void extraeElNifDePersonaJuridicaDelIdentificadorDelOrigen() {
		var party = PartyIdentity.from("12619-NIF-B50892819-award-65236", "MARIANO-ESTAGE-SL");

		assertThat(party.taxId()).isEqualTo("B50892819");
		assertThat(party.name()).isEqualTo("MARIANO-ESTAGE-SL");
		assertThat(party.naturalPerson()).isFalse();
		assertThat(party.groupingKey()).isEqualTo("B50892819");
	}

	@Test
	void tambienConLaOtraFormaDelIdentificador() {
		var party = PartyIdentity.from("2558-NIF-B99539991-65237", "FIRMEZA SOLUTIONS S.L.");

		assertThat(party.taxId()).isEqualTo("B99539991");
	}

	@ParameterizedTest
	@ValueSource(strings = { "A", "B", "G", "N", "F", "U", "J", "Q", "V", "W", "I", "E", "R", "P", "D", "L", "S" })
	void todasLasLetrasDePersonaJuridicaObservadasEntran(String letter) {
		var party = PartyIdentity.from("1-NIF-" + letter + "12345678-award-1", "UNA EMPRESA SL");

		assertThat(party.taxId()).isEqualTo(letter + "12345678");
		assertThat(party.naturalPerson()).isFalse();
	}

	@Test
	void deUnaPersonaFisicaNoSeGuardaNiNifNiNombre() {
		// El único identificador que empieza por dígito en los 17.614 de la fuente (S3.1 §6). La regla no depende
		// de que sea uno: si mañana fueran quinientos, ninguno entraría.
		var party = PartyIdentity.from("999-NIF-12345678Z-award-1", "UN NOMBRE Y DOS APELLIDOS");

		assertThat(party.naturalPerson()).isTrue();
		assertThat(party.taxId()).isNull();
		assertThat(party.name()).as("el nombre es el identificador fuerte de una persona física").isNull();
	}

	@Test
	void unIdentificadorSinNifConservaElNombre() {
		// Son el ayuntamiento y sus unidades compradoras: el identificador no dice nada de nadie.
		var party = PartyIdentity.from("1-Ayuntamiento de Zaragoza", "Ayuntamiento de Zaragoza");

		assertThat(party.taxId()).isNull();
		assertThat(party.name()).isEqualTo("Ayuntamiento de Zaragoza");
		assertThat(party.naturalPerson()).isFalse();
		assertThat(party.groupingKey()).isEqualTo("Ayuntamiento de Zaragoza");
	}

	@Test
	void normalizaElNifAMayusculas() {
		assertThat(PartyIdentity.from("1-NIF-b50892819-award-1", "x").taxId()).isEqualTo("B50892819");
	}

	@Test
	void unaParteSinIdentificadorNiNombreEsUnHechoNoUnError() {
		var party = PartyIdentity.from(null, null);

		assertThat(party.taxId()).isNull();
		assertThat(party.name()).isNull();
		assertThat(party.naturalPerson()).isFalse();
	}

	@Test
	void elModeloNoAdmiteUnaPersonaFisicaConIdentidad() {
		// La otra mitad de la garantía la impone la base de datos; esta la impone el tipo.
		assertThatIllegalArgumentException().isThrownBy(() -> new PartyIdentity("12345678Z", null, true));
		assertThatIllegalArgumentException().isThrownBy(() -> new PartyIdentity(null, "Un nombre", true));
	}

}
