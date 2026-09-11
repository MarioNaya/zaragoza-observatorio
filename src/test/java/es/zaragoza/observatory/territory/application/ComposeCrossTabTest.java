package es.zaragoza.observatory.territory.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import es.zaragoza.observatory.citizen.Citizen;
import es.zaragoza.observatory.geo.DistrictLocation;
import es.zaragoza.observatory.geo.DistrictNames;
import es.zaragoza.observatory.geo.DistrictPopulation;
import es.zaragoza.observatory.geo.DistrictSummary;
import es.zaragoza.observatory.geo.Geo;
import es.zaragoza.observatory.geo.GeoPoint;
import es.zaragoza.observatory.shared.DateWindow;
import es.zaragoza.observatory.shared.TerritorialTally;
import es.zaragoza.observatory.territory.domain.CrossTab;
import es.zaragoza.observatory.territory.domain.CrossTabSort;
import es.zaragoza.observatory.territory.domain.Denominator;
import es.zaragoza.observatory.territory.domain.Measure;
import es.zaragoza.observatory.urban.Urban;

/**
 * Las reglas de composición de ADR-019 sobre datos de laboratorio, donde se puede montar el caso raro: la junta
 * sin padrón del año pedido, el empate al ordenar por una medida y la junta sin un solo registro.
 */
class ComposeCrossTabTest {

	/** Tres juntas: la 1 con padrón en los dos años, la 2 solo en el viejo y la 3 sin padrón ninguno. */
	private static final List<DistrictSummary> DISTRICTS = List.of(
			new DistrictSummary(1, "Junta Municipal Centro", "Centro", 101, 2_000, 2024),
			new DistrictSummary(2, "Junta Municipal Sur", "Sur", 102, 1_000, 2022),
			new DistrictSummary(3, "Junta Vecinal Norte", "Norte", 103, null, null));

	private static final List<DistrictPopulation> PADRON = List.of(new DistrictPopulation(1, 2022, 1_800),
			new DistrictPopulation(1, 2024, 2_000), new DistrictPopulation(2, 2022, 1_000));

	@Test
	void putsEveryDistrictInARowEvenWithoutRecords() {
		var compose = composer(tally(Map.of(1, 30L, 2, 10L), 100, 50, 40), TerritorialTally.empty());

		CrossTab tab = compose.compose(List.of(Measure.CITIZEN_REQUESTS, Measure.URBAN_PREMISES), DateWindow.open(),
				Denominator.POPULATION, null, CrossTabSort.byDistrict());

		assertThat(tab.rows()).extracting(CrossTab.DistrictRow::districtId).containsExactly(1, 2, 3);
		// La junta sin un solo registro sale con 0, no se omite: una junta vacía es información (ADR-019 §1).
		assertThat(tab.rows().get(2).value(Measure.CITIZEN_REQUESTS)).isZero();
		assertThat(tab.columns()).extracting(column -> column.measure().id())
				.containsExactly("citizen.requests", "urban.premises");
	}

	@Test
	void carriesTheCoverageOfEachColumnUntouched() {
		var compose = composer(tally(Map.of(1, 30L, 2, 10L), 100, 50, 40), tally(Map.of(1, 5L), 10, 9, 9));

		CrossTab tab = compose.compose(List.of(Measure.CITIZEN_REQUESTS, Measure.URBAN_PREMISES), DateWindow.open(),
				Denominator.POPULATION, null, CrossTabSort.byDistrict());

		var citizen = tab.columns().get(0).tally();
		// La suma de las filas es `assigned`, no `total`: esa diferencia es la que obliga a publicar la cobertura.
		assertThat(tab.rows().stream().mapToLong(row -> row.value(Measure.CITIZEN_REQUESTS)).sum())
				.isEqualTo(citizen.assigned());
		assertThat(citizen.total()).isEqualTo(100);
		assertThat(citizen.unassigned()).isEqualTo(60);
		assertThat(citizen.pointCoverage()).isEqualTo(0.5);
		// Nada se reescala por cobertura, ni aquí ni en ningún sitio (ADR-015, ADR-019 §7).
		assertThat(tab.rows().get(0).value(Measure.CITIZEN_REQUESTS)).isEqualTo(30);
	}

	@Test
	void normalizesWithEachDistrictsOwnPopulationYear() {
		var compose = composer(tally(Map.of(1, 20L, 2, 20L), 40, 40, 40), TerritorialTally.empty());

		CrossTab tab = compose.compose(List.of(Measure.CITIZEN_REQUESTS), DateWindow.open(), Denominator.POPULATION,
				null, CrossTabSort.byDistrict());

		assertThat(tab.rows().get(0).populationYear()).isEqualTo(2024);
		assertThat(tab.rows().get(0).perThousandInhabitants(Measure.CITIZEN_REQUESTS)).isEqualTo(10.0);
		// La junta 2 solo tiene 2022 y se normaliza con ese año, que la fila declara: la serie del padrón no es
		// continua y el año más reciente no es el mismo en todas (S2.1).
		assertThat(tab.rows().get(1).populationYear()).isEqualTo(2022);
		assertThat(tab.rows().get(1).perThousandInhabitants(Measure.CITIZEN_REQUESTS)).isEqualTo(20.0);
		// La junta sin padrón sale sin denominador y sin tasa. El hueco se ve, no se interpola (ADR-015).
		assertThat(tab.rows().get(2).population()).isNull();
		assertThat(tab.rows().get(2).perThousandInhabitants(Measure.CITIZEN_REQUESTS)).isNull();
	}

	@Test
	void pinnedPopulationYearNeverFallsBackToAnother() {
		var compose = composer(tally(Map.of(1, 20L, 2, 20L), 40, 40, 40), TerritorialTally.empty());

		CrossTab tab = compose.compose(List.of(Measure.CITIZEN_REQUESTS), DateWindow.open(), Denominator.POPULATION,
				2024, CrossTabSort.byDistrict());

		assertThat(tab.populationYear()).isEqualTo(2024);
		assertThat(tab.rows().get(0).population()).isEqualTo(2_000);
		// La junta 2 no tiene 2024: se queda sin denominador en vez de caer al 2022 sin decirlo, que sería
		// responder otra pregunta (ADR-015).
		assertThat(tab.rows().get(1).population()).isNull();
		assertThat(tab.rows().get(1).populationYear()).isNull();
		assertThat(tab.rows().get(1).perThousandInhabitants(Measure.CITIZEN_REQUESTS)).isNull();
		// Y el valor absoluto sigue estando: lo que falta es el denominador, no la medida.
		assertThat(tab.rows().get(1).value(Measure.CITIZEN_REQUESTS)).isEqualTo(20);
	}

	@Test
	void withoutDenominatorThereIsNoPopulationAndNoRate() {
		var compose = composer(tally(Map.of(1, 20L), 20, 20, 20), TerritorialTally.empty());

		CrossTab tab = compose.compose(List.of(Measure.CITIZEN_REQUESTS), DateWindow.open(), Denominator.NONE, null,
				CrossTabSort.byDistrict());

		assertThat(tab.rows()).allSatisfy(row -> {
			assertThat(row.population()).isNull();
			assertThat(row.perThousandInhabitants(Measure.CITIZEN_REQUESTS)).isNull();
		});
	}

	@Test
	void sortsByAMeasureAndBreaksTiesByDistrictId() {
		// Las juntas 2 y 3 empatan a 10: el desempate tiene que ser estable o la lectura cambia entre peticiones.
		var compose = composer(tally(Map.of(1, 5L, 2, 10L, 3, 10L), 25, 25, 25), TerritorialTally.empty());

		CrossTab descending = compose.compose(List.of(Measure.CITIZEN_REQUESTS), DateWindow.open(),
				Denominator.NONE, null, new CrossTabSort(Measure.CITIZEN_REQUESTS, false));
		assertThat(descending.rows()).extracting(CrossTab.DistrictRow::districtId).containsExactly(2, 3, 1);

		CrossTab ascending = compose.compose(List.of(Measure.CITIZEN_REQUESTS), DateWindow.open(), Denominator.NONE,
				null, new CrossTabSort(Measure.CITIZEN_REQUESTS, true));
		assertThat(ascending.rows()).extracting(CrossTab.DistrictRow::districtId).containsExactly(1, 2, 3);
	}

	@Test
	void thePopulationSeriesOfADistrictKeepsItsGaps() {
		var compose = composer(TerritorialTally.empty(), TerritorialTally.empty());

		assertThat(compose.populationSeries(1)).extracting(DistrictPopulation::year).containsExactly(2022, 2024);
		assertThat(compose.populationSeries(3)).isEmpty();
	}

	// --- dobles ----------------------------------------------------------------------------------------

	private static TerritorialTally tally(Map<Integer, Long> byDistrict, long total, long withPoint, long assigned) {
		return new TerritorialTally(byDistrict, total, withPoint, assigned);
	}

	private static ComposeCrossTab composer(TerritorialTally requests, TerritorialTally premises) {
		return new ComposeCrossTab(new FakeGeo(), new FakeCitizen(requests), new FakeUrban(premises));
	}

	private static final class FakeGeo implements Geo {

		@Override
		public DistrictLocation locate(GeoPoint point) {
			throw new UnsupportedOperationException();
		}

		@Override
		public List<DistrictLocation> locateAll(List<GeoPoint> points) {
			throw new UnsupportedOperationException();
		}

		@Override
		public DistrictNames districtNames() {
			throw new UnsupportedOperationException();
		}

		@Override
		public List<DistrictSummary> districts() {
			return DISTRICTS;
		}

		@Override
		public List<DistrictPopulation> populations() {
			return PADRON;
		}

	}

	private record FakeCitizen(TerritorialTally tally) implements Citizen {

		@Override
		public TerritorialTally requestsByDistrict(DateWindow window) {
			return tally;
		}

		@Override
		public Instant ingestedAt() {
			return Instant.parse("2026-09-12T04:00:00Z");
		}

	}

	private record FakeUrban(TerritorialTally tally) implements Urban {

		@Override
		public TerritorialTally premisesByDistrict(DateWindow window) {
			return tally;
		}

		@Override
		public TerritorialTally licencesByDistrict(DateWindow window) {
			return tally;
		}

		@Override
		public Instant ingestedAt() {
			return Instant.parse("2026-09-12T05:00:00Z");
		}

	}

}
