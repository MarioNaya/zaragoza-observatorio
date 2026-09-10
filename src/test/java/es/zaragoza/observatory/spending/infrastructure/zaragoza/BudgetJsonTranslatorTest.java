package es.zaragoza.observatory.spending.infrastructure.zaragoza;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import es.zaragoza.observatory.spending.domain.BudgetAmounts;
import es.zaragoza.observatory.spending.domain.BudgetLine;
import es.zaragoza.observatory.support.Fixtures;
import tools.jackson.databind.json.JsonMapper;

/**
 * Los dos traductores del presupuesto contra los documentos reales que grabó S3.2. Lo que se comprueba aquí es
 * justo lo que el spike encontró y el traductor tiene que respetar: que el censo son URL y no objetos, que los
 * códigos antiguos llegan rellenos con espacios y que el nombre de una partida que nombra a una persona física
 * no se guarda.
 */
class BudgetJsonTranslatorTest {

	final JsonMapper json = JsonMapper.builder().build();

	final BudgetDatesJsonTranslator dates = new BudgetDatesJsonTranslator(json);

	final BudgetLineJsonTranslator lines = new BudgetLineJsonTranslator(json);

	@Test
	void elCensoSonUrlYSeQuedaConSuFecha() {
		List<LocalDate> result = dates.translate(Fixtures.text("budget/gasto-corriente_fecha.json"));

		assertThat(result).hasSize(140);
		assertThat(result.getFirst()).isEqualTo(LocalDate.of(2006, 12, 31));
		assertThat(result.getLast()).isEqualTo(LocalDate.of(2026, 8, 31));
		assertThat(result).isSorted().doesNotHaveDuplicates();
	}

	@Test
	void elCensoDescartaLoQueNoTerminaEnUnaFecha() {
		String body = """
				{"totalCount":3,"result":[
				  "https://www.zaragoza.es/sede/servicio/presupuesto/gasto-corriente/fecha/20260831",
				  "https://www.zaragoza.es/sede/servicio/presupuesto/gasto-corriente/fecha/ultima",
				  "https://www.zaragoza.es/sede/servicio/presupuesto/gasto-corriente/fecha/../../etc"
				]}
				""";

		assertThat(dates.translate(body)).containsExactly(LocalDate.of(2026, 8, 31));
	}

	@Test
	void unCensoSinResultHaceFallarLaIngestaEnVezDePasarPorVacio() {
		assertThatThrownBy(() -> dates.translate("{\"totalCount\":140}"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("result");
	}

	@Test
	void unaPartidaRecienteTraeSusOchoImportesYSuClasificacion() {
		LocalDate date = LocalDate.of(2026, 8, 31);

		List<BudgetLine> result = lines.translate(date,
				Fixtures.text("budget/gasto-corriente_20260831_rows-2.json"));

		assertThat(result).hasSize(2);
		BudgetLine first = result.getFirst();
		assertThat(first.snapshotDate()).isEqualTo(date);
		// La clave es (fecha, concepto): el `id` del origen es esta misma clave con la fecha delante.
		assertThat(first.concept()).isEqualTo("26ACS--2311-21200");
		assertThat(first.chapterId()).isEqualTo(2);
		assertThat(first.programmeId()).isEqualTo("2311");
		assertThat(first.organId()).isEqualTo("ACS");
		assertThat(first.heading()).startsWith("MANTENIMIENTO, FUNCIONAMIENTO");
		assertThat(first.headingRedacted()).isFalse();

		BudgetAmounts amounts = first.amounts();
		assertThat(amounts.creditFinal()).isEqualByComparingTo("45000");
		assertThat(amounts.obligations()).isEqualByComparingTo("26097.77");
		assertThat(amounts.payments()).isEqualByComparingTo("24906.85");
		// Las tres identidades contables de S3.2 §5, sobre la partida.
		assertThat(amounts.creditInitial().add(amounts.creditModification()))
				.isEqualByComparingTo(amounts.creditFinal());
		assertThat(amounts.creditFinal().subtract(amounts.obligations()))
				.isEqualByComparingTo(amounts.creditRemaining());
		assertThat(amounts.obligations().subtract(amounts.payments()))
				.isEqualByComparingTo(amounts.paymentsPending());
	}

	@Test
	void losCodigosAntiguosLleganRellenosDeEspaciosYSeRecortan() {
		List<BudgetLine> result = lines.translate(LocalDate.of(2006, 12, 31),
				Fixtures.text("budget/gasto-corriente_20061231_rows-2.json"));

		assertThat(result).hasSize(2);
		// En el documento son "22690  " y un epígrafe con decenas de blancos a la derecha.
		assertThat(result.getFirst().itemId()).isEqualTo("22690");
		assertThat(result.getFirst().item()).isEqualTo("OTROS GASTOS DIVERSOS");
	}

	@Test
	void elTotalCountDeLaPaginaSeLeeYSeDistingueDeSuAusencia() {
		assertThat(lines.totalCount(Fixtures.text("budget/gasto-corriente_20260831_rows-2.json"))).isEqualTo(1251);
		assertThat(lines.totalCount("{\"result\":[]}")).isEqualTo(-1);
	}

	@Test
	void unaPartidaSinProgramaSeGuardaConNuloYNoEsUnFallo() {
		// La clasificación por programa no existe en 2010-2014 (S3.2 §7): el nulo es el dato.
		String body = """
				{"totalCount":1,"result":[{"id":"20121231-12ALC--1111120200","idArea":"01","area":"ALCALDIA",
				"partida":"ALQUILER EDIFICIOS","idCapitulo":2,"capitulo":"Gastos en Bienes Corrientes y Servicios",
				"idEpigrafe":"20200","epigrafe":"ALQUILER EDIFICIOS","idOrgano":"ALC","organo":"ALCALDIA",
				"creditoInicial":100,"creditoModificacion":0,"creditoDefinitivo":100,"gastoComprometido":0,
				"obligacionNeta":0,"pagoNeto":0,"obligacionPendientePago":0,"remanenteDeCredito":100,
				"fecha":"20121231","concepto":"12ALC--1111120200"}]}
				""";

		BudgetLine line = lines.translate(LocalDate.of(2012, 12, 31), body).getFirst();

		assertThat(line.programmeId()).isNull();
		assertThat(line.programme()).isNull();
		// Y el cero SÍ es un valor: una partida sin ejecutar tiene 0 € de obligación neta.
		assertThat(line.amounts().obligations()).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	void elNombreDeUnaPartidaQueNombraAUnaPersonaFisicaNoSeGuarda() {
		// La fórmula es la de S3.2 §8; el nombre de este documento es inventado.
		String body = """
				{"totalCount":1,"result":[{"id":"20061231-06HAC--0111116000","idArea":"02","area":"HACIENDA",
				"partida":"PENSION A LA VIUDA DE D. NOMBRE APELLIDO APELLIDO","idCapitulo":1,
				"capitulo":"Gasto de Personal","idEpigrafe":"16000","epigrafe":"PENSIONES","idOrgano":"HAC",
				"organo":"HACIENDA","creditoInicial":1200,"creditoModificacion":0,"creditoDefinitivo":1200,
				"gastoComprometido":1200,"obligacionNeta":1200,"pagoNeto":1200,"obligacionPendientePago":0,
				"remanenteDeCredito":0,"fecha":"20061231","concepto":"06HAC--0111116000"}]}
				""";

		BudgetLine line = lines.translate(LocalDate.of(2006, 12, 31), body).getFirst();

		assertThat(line.headingRedacted()).isTrue();
		assertThat(line.heading()).isNull();
		// Todo lo demás de la fila sigue estando: se omite el nombre, no la partida.
		assertThat(line.concept()).isEqualTo("06HAC--0111116000");
		assertThat(line.amounts().obligations()).isEqualByComparingTo("1200");
	}

	@Test
	void unaPartidaSinConceptoSeDescartaPorqueNoTieneClave() {
		String body = """
				{"totalCount":1,"result":[{"id":"20261231-x","idArea":"01","area":"ALCALDIA","creditoInicial":1}]}
				""";

		assertThat(lines.translate(LocalDate.of(2026, 12, 31), body)).isEmpty();
	}

	@Test
	void unaPaginaSinResultHaceFallarLaLectura() {
		assertThatThrownBy(() -> lines.translate(LocalDate.of(2026, 8, 31), "[]"))
				.isInstanceOf(IllegalArgumentException.class);
	}

}
