package es.zaragoza.observatory.spending.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * La regla de dato personal del presupuesto (S3.2 §8, regla 22). Los nombres de estas pruebas son inventados: lo
 * que se comprueba es la <b>fórmula</b>, que es lo único que la lista cerrada reconoce.
 */
class BudgetHeadingTest {

	@ParameterizedTest
	@ValueSource(strings = { "PENSION A LA VIUDA DE D. NOMBRE APELLIDO", "A LA VDA. DE NOMBRE APELLIDO",
			"HEREDEROS DE NOMBRE APELLIDO", "PAGO A HDROS DE NOMBRE APELLIDO" })
	void lasFormulasQueNombranAUnaPersonaFisicaNoSeGuardan(String heading) {
		assertThat(BudgetHeading.namesNaturalPerson(heading)).isTrue();
		assertThat(BudgetHeading.sanitize(heading)).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = { "MANTENIMIENTO GENERAL INFRAESTRUCTURAS", "CONVENIO CON FUNDACION",
			"D.F. CONEXION PARQUE", "ADQUISICION DE SUELO" })
	void elRestoDelTextoAdministrativoSeGuardaTalCual(String heading) {
		assertThat(BudgetHeading.namesNaturalPerson(heading)).isFalse();
		assertThat(BudgetHeading.sanitize(heading)).isEqualTo(heading);
	}

	@Test
	void elTextoSeRecortaYElVacioEsNulo() {
		assertThat(BudgetHeading.sanitize("  ALQUILER EDIFICIOS  ")).isEqualTo("ALQUILER EDIFICIOS");
		assertThat(BudgetHeading.sanitize("   ")).isNull();
		assertThat(BudgetHeading.sanitize(null)).isNull();
	}

	@Test
	void laListaEsCerradaYAmpliarlaExigeVolverAMedir() {
		// El recuento de filas redactadas se publica; si esta lista crece, el recuento cambia y se ve.
		assertThat(BudgetHeading.PERSON_FORMULAS).containsExactly("viuda de", "vda. de", "herederos de", "hdros");
	}

}
