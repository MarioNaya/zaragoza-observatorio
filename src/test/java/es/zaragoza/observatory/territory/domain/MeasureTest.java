package es.zaragoza.observatory.territory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** El catálogo de medidas es cerrado y cada entrada se describe a sí misma (ADR-019 §2). */
class MeasureTest {

	@Test
	void resolvesOnlyTheIdsOfTheCatalogue() {
		assertThat(Measure.byId("citizen.requests")).contains(Measure.CITIZEN_REQUESTS);
		assertThat(Measure.byId("  URBAN.Licences ")).contains(Measure.URBAN_LICENCES);
		assertThat(Measure.byId("citizen.text")).isEmpty();
		assertThat(Measure.byId("spending.awarded")).isEmpty();
		assertThat(Measure.byId(null)).isEmpty();
	}

	@Test
	void namesTheModuleOfAnIdEvenWhenTheMeasureDoesNotExist() {
		// Lo necesita el 400 de ADR-019 §8: sin esto, pedir gasto por junta respondería «medida desconocida» en
		// vez de decir que ninguna fuente de gasto publica territorio.
		assertThat(Measure.moduleOf("spending.awarded")).isEqualTo("spending");
		assertThat(Measure.MODULES_WITHOUT_TERRITORY).contains("spending");
		assertThat(Measure.moduleOf("suelto")).isEqualTo("suelto");
		assertThat(Measure.moduleOf(null)).isEmpty();
	}

	@Test
	void everyMeasureDeclaresItsUnitAndTheDateItsWindowFalls() {
		assertThat(Measure.values()).allSatisfy(measure -> {
			assertThat(measure.id()).startsWith(measure.module() + ".");
			assertThat(measure.unit()).isNotNull();
			assertThat(measure.dateField()).isNotBlank();
			assertThat(measure.dateFieldMeaning()).isNotBlank();
		});
		// Las dos medidas de urban cuentan unidades distintas: un local con doce licencias es un local y son doce
		// licencias (ADR-016 §7). Si alguna vez coincidieran, una de las dos sobraría.
		assertThat(Measure.URBAN_PREMISES.unit()).isNotEqualTo(Measure.URBAN_LICENCES.unit());
		// Y las dos aplican la ventana sobre el alta del local, no sobre el año del expediente (ADR-019 §5).
		assertThat(Measure.URBAN_LICENCES.dateFieldMeaning()).contains("no el año de licencia");
	}

}
