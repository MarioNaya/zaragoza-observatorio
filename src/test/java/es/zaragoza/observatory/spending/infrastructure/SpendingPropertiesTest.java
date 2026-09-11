package es.zaragoza.observatory.spending.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import es.zaragoza.observatory.spending.infrastructure.SpendingProperties.Grants;

/**
 * La primera mitad de la garantía de ADR-018 §3, y la que se nota antes: si alguien añade a la proyección el
 * campo que lleva el nombre del beneficiario, <b>la aplicación no arranca</b>. Es la misma figura que
 * {@code zaragoza.citizen.fields} en ADR-012.
 */
class SpendingPropertiesTest {

	static final String CALLS = "id,title,ejercicioClave,presupuesto";

	@ParameterizedTest
	@ValueSource(strings = { "id,title,adjudicatario", "adjudicatario.nombre,id", "id, ADJUDICATARIO ,granted" })
	void laProyeccionDeConcesionesNoPuedePedirLaIdentidadDelBeneficiario(String fields) {
		assertThatThrownBy(() -> grants(fields, CALLS)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("ADR-018");
	}

	/** Sin proyección, la convocatoria trae dentro el conjunto entero de concesiones con los nombres (S3.3 §8). */
	@ParameterizedTest
	@ValueSource(strings = { "id,title,resolucion", "resolucion.adjudicatario" })
	void laProyeccionDeConvocatoriasNoPuedePedirSusResoluciones(String fields) {
		assertThatThrownBy(() -> grants("id,title", fields)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("ADR-018");
	}

	@Test
	void laProyeccionPorDefectoEsLaQueMidioElSpike() {
		Grants defaults = grants(
				"id,title,expediente,importeSolicitado,importeConcedido,importeAnual,numAnualidades,"
						+ "fechaSolicitud,fechaConcesion,fechaAcuerdo,convocatoria",
				"id,title,ejercicioClave,lineaEstrategica.lineaAuxiliar");

		assertThat(defaults.grantFields()).doesNotContain("adjudicatario");
		assertThat(defaults.callFields()).doesNotContain("resolucion");
		assertThat(defaults.grantsUrl().toString())
				.isEqualTo("https://www.zaragoza.es/sede/servicio/ayuda-subvencion/resolucion.json");
		assertThat(defaults.callsUrl().toString())
				.isEqualTo("https://www.zaragoza.es/sede/servicio/ayuda-subvencion/convocatoria.json");
		assertThat(defaults.linksUrl().toString())
				.isEqualTo("https://www.zaragoza.es/sede/servicio/ayuda-subvencion-v2/concesion.json");
		assertThat(defaults.beneficiariesUrl().toString())
				.isEqualTo("https://www.zaragoza.es/sede/servicio/ayuda-subvencion-v2/organization.json");
	}

	/** `pageSize` no tiene tope en la fuente; aquí sí, porque se pagina y no se pide el todo (ADR-018 §2). */
	@Test
	void elTamanoDePaginaRespetaElTopeDeLaSedeAunqueLaFuenteNoLoImponga() {
		assertThatThrownBy(() -> new Grants(null, null, "id", CALLS, 5000, 500, Duration.ofDays(1), false))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Grants(null, null, "id", CALLS, 500, 20000, Duration.ofDays(1), false))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private static Grants grants(String grantFields, String callFields) {
		return new Grants("https://www.zaragoza.es/sede/servicio/ayuda-subvencion/",
				"https://www.zaragoza.es/sede/servicio/ayuda-subvencion-v2", grantFields, callFields, 500, 500,
				Duration.ofDays(1), false);
	}

}
