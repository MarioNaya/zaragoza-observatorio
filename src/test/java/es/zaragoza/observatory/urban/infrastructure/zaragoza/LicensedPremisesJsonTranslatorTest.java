package es.zaragoza.observatory.urban.infrastructure.zaragoza;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import es.zaragoza.observatory.support.Fixtures;
import es.zaragoza.observatory.urban.domain.Licence;
import es.zaragoza.observatory.urban.domain.LicensedPremisesDraft;
import tools.jackson.databind.json.JsonMapper;

/**
 * El traductor sobre la respuesta real grabada por S2.4 (500 registros completos, con sus licencias).
 * <p>
 * El test que más importa es {@link #neverReadsTheFreeText()}. En {@code citizen} la garantía era doble —no
 * pedir el texto y no leerlo—; aquí la primera mitad no existe, porque la fuente no deja proyectar sin romper
 * las licencias (S2.4 §2). Este test <b>es</b> la garantía entera de ADR-016 §3.
 */
class LicensedPremisesJsonTranslatorTest {

	static final String PAGE = "urban/registro-licencia_page0_rows-500.json";

	final LicensedPremisesJsonTranslator translator = new LicensedPremisesJsonTranslator(JsonMapper.shared());

	@Test
	void translatesTheRecordedPage() {
		List<LicensedPremisesDraft> drafts = translator.translate(Fixtures.text(PAGE));

		assertThat(drafts).hasSize(500);
		assertThat(drafts).allSatisfy(draft -> {
			assertThat(draft.sourceId()).isNotNegative();
			assertThat(draft.createdAt()).isNotNull();
			assertThat(draft.activity()).isNotNull();
		});
		// La cobertura de punto es alta pero no total (89,4 % en el registro entero, S2.4 §5).
		assertThat(drafts.stream().filter(d -> d.point() != null)).isNotEmpty();
		// Los puntos están en Zaragoza y en el orden lon/lat de srsname=wgs84 (S0.5).
		assertThat(drafts.stream().filter(d -> d.point() != null)).allSatisfy(draft -> {
			assertThat(draft.point().lon()).isBetween(-1.3, -0.6);
			assertThat(draft.point().lat()).isBetween(41.4, 42.0);
		});
		// El epígrafe IAE es la actividad del producto y lo traen todos (S2.4 §7).
		assertThat(drafts.stream().filter(d -> d.activity().code() != null)).hasSize(500);
		assertThat(drafts.stream().filter(d -> d.activity().group() != null)).isNotEmpty();
	}

	@Test
	void readsTheLicencesOfEachPremises() {
		List<LicensedPremisesDraft> drafts = translator.translate(Fixtures.text(PAGE));

		List<Licence> licences = drafts.stream().flatMap(draft -> draft.licences().stream()).toList();
		assertThat(licences).isNotEmpty();
		assertThat(licences).allSatisfy(licence -> {
			assertThat(licence.year()).isBetween(1900, 2100);
			assertThat(licence.fileNumber()).isNotNegative();
		});
		// (año, expediente) identifica la licencia dentro de su local, sin colisiones (S2.4 §7).
		assertThat(drafts).allSatisfy(draft -> assertThat(draft.licences().stream().map(Licence::key).distinct())
				.hasSize(draft.licences().size()));
		// Ordenadas por año y expediente, para que el orden no dependa de cómo las devuelva el origen.
		assertThat(drafts).allSatisfy(draft -> assertThat(draft.licences())
				.isSortedAccordingTo((a, b) -> a.year() != b.year() ? Integer.compare(a.year(), b.year())
						: Long.compare(a.fileNumber(), b.fileNumber())));
	}

	@Test
	void neverReadsTheFreeText() {
		// La fuente devuelve siempre estos cuatro campos: no hay forma de no pedirlos (`fl` rompe los anidados y
		// `removeproperties` no hace nada, S2.4 §2). Lo que garantiza ADR-016 §3 es que el traductor no los mire.
		String withText = """
				{"totalCount":1,"start":0,"rows":1,"result":[
				 {"id":2,"emplazamiento":"CALLE FALSA, 1 (ZARAGOZA)","codPortal":"869","codVia":"17980",
				  "comments":"titular Nombre Apellido 12345678Z",
				  "actividad":"BAR DE Nombre Apellido 12345678Z",
				  "iae":{"id":{"seccion":1,"agrupacion":67,"licencia":32},"identifier":"16732",
				         "title":"OTROS CAFES Y BARES"},
				  "idIAE":"16732","idAgrupacion":"67","creationDate":"2007-07-25T14:12:26",
				  "lastUpdated":"2024-01-15T09:41:55","estado":3,
				  "licencias":[{"id":{"expediente":100306,"anyo":2021},"tipo":{"id":46,"title":"RECURSO"},
				                "orden":4,"idResolucion":3,
				                "comments":"a instancia de Nombre Apellido, con DNI 12345678Z",
				                "resolucion":"2022-04-04T00:00:00"}],
				  "geometry":{"type":"Point","coordinates":[-0.88,41.65]}}]}
				""";
		List<LicensedPremisesDraft> drafts = translator.translate(withText);

		assertThat(drafts).hasSize(1);
		LicensedPremisesDraft draft = drafts.get(0);
		assertThat(draft.toString())
				.as("ningún campo del local traducido puede contener texto libre del origen")
				.doesNotContain("Nombre Apellido", "12345678Z", "CALLE FALSA", "BAR DE", "titular",
						"a instancia de");
		// Lo que sí se queda: el epígrafe, que dice la actividad sin texto libre.
		assertThat(draft.activity().code()).isEqualTo("16732");
		assertThat(draft.activity().title()).isEqualTo("OTROS CAFES Y BARES");
		assertThat(draft.activity().section()).isEqualTo(1);
		assertThat(draft.activity().group()).isEqualTo(67);
		assertThat(draft.licences()).singleElement().satisfies(licence -> {
			assertThat(licence.year()).isEqualTo(2021);
			assertThat(licence.fileNumber()).isEqualTo(100306);
			assertThat(licence.typeName()).isEqualTo("RECURSO");
		});
		assertThat(LicensedPremisesJsonTranslator.FREE_TEXT_FIELDS)
				.containsExactlyInAnyOrder("comments", "actividad", "emplazamiento");
	}

	@Test
	void keepsTheImpossibleLicenceYearInsteadOfCorrectingIt() {
		// El origen publica una licencia fechada en 2033 (S2.4 §7). Corregirla sería inventarse el dato.
		String future = """
				{"result":[{"id":1,"creationDate":"2020-01-01T00:00:00","estado":1,
				  "licencias":[{"id":{"expediente":7,"anyo":2033},"tipo":{"id":1,"title":"X"}}]}]}
				""";
		List<LicensedPremisesDraft> drafts = translator.translate(future);

		assertThat(drafts).singleElement()
				.satisfies(draft -> assertThat(draft.licences()).singleElement()
						.satisfies(licence -> assertThat(licence.year()).isEqualTo(2033)));
	}

	@Test
	void skipsRecordsWithoutTheMinimum() {
		String broken = """
				{"result":[
				 {"creationDate":"2020-01-01T00:00:00","estado":1},
				 {"id":5,"estado":1},
				 {"id":7,"creationDate":"2020-01-01T00:00:00","estado":1,
				  "licencias":[{"id":{"expediente":9},"tipo":{"id":1}},
				               {"id":{"expediente":10,"anyo":2020},"tipo":{"id":1}}]}]}
				""";
		List<LicensedPremisesDraft> drafts = translator.translate(broken);

		// Sin id o sin fecha de alta el local se descarta; la licencia sin año se descarta y el local se queda.
		assertThat(drafts).singleElement().satisfies(draft -> {
			assertThat(draft.sourceId()).isEqualTo(7);
			assertThat(draft.licences()).singleElement()
					.satisfies(licence -> assertThat(licence.fileNumber()).isEqualTo(10));
		});
	}

	@Test
	void rejectsAResponseWithoutResult() {
		// Un listado sin `result` no es un listado vacío: tragárselo haría que una ingesta rota pareciera
		// correcta, que es lo que ADR-013 §2 evitó en el catálogo.
		assertThatThrownBy(() -> translator.translate("{\"totalCount\":0}"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("result");
	}

}
