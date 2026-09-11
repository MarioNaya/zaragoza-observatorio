package es.zaragoza.observatory.territory.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import es.zaragoza.observatory.citizen.Citizen;
import es.zaragoza.observatory.geo.DistrictPopulation;
import es.zaragoza.observatory.geo.DistrictSummary;
import es.zaragoza.observatory.geo.Geo;
import es.zaragoza.observatory.shared.DateWindow;
import es.zaragoza.observatory.shared.TerritorialTally;
import es.zaragoza.observatory.territory.domain.CrossTab;
import es.zaragoza.observatory.territory.domain.CrossTab.DistrictRow;
import es.zaragoza.observatory.territory.domain.CrossTab.MeasureColumn;
import es.zaragoza.observatory.territory.domain.CrossTabSort;
import es.zaragoza.observatory.territory.domain.Denominator;
import es.zaragoza.observatory.territory.domain.Measure;
import es.zaragoza.observatory.urban.Urban;

/**
 * Compone el cruce llamando a las superficies públicas de los módulos implicados (SPEC.md §4.8, ADR-019 §9).
 * <p>
 * Sin estado y sin caché: cada petición son tantas agregaciones como medidas pedidas más la consulta del padrón.
 * Con 29 filas y las tablas ya indexadas es barato, y un caché aquí sería estado propio de un módulo que la
 * especificación define sin estado.
 */
public class ComposeCrossTab {

	private final Geo geo;
	private final Citizen citizen;
	private final Urban urban;

	public ComposeCrossTab(Geo geo, Citizen citizen, Urban urban) {
		this.geo = Objects.requireNonNull(geo);
		this.citizen = Objects.requireNonNull(citizen);
		this.urban = Objects.requireNonNull(urban);
	}

	/**
	 * @param measures medidas pedidas, en orden y sin repetir; el catálogo lo valida quien traduce la petición
	 * @param window ventana, que cada medida aplica sobre su propia fecha
	 * @param denominator denominador pedido
	 * @param populationYear año de padrón a usar en todas las juntas, o {@code null} para el último de cada una
	 * @param sort criterio explícito de ordenación de las filas
	 */
	public CrossTab compose(List<Measure> measures, DateWindow window, Denominator denominator,
			Integer populationYear, CrossTabSort sort) {
		List<Measure> wanted = measures == null ? List.of() : measures;
		DateWindow bounds = window == null ? DateWindow.open() : window;
		Denominator base = denominator == null ? Denominator.POPULATION : denominator;
		CrossTabSort order = sort == null ? CrossTabSort.byDistrict() : sort;

		Map<Measure, TerritorialTally> tallies = new EnumMap<>(Measure.class);
		List<MeasureColumn> columns = new ArrayList<>();
		for (Measure measure : wanted) {
			TerritorialTally tally = tallyOf(measure, bounds);
			tallies.put(measure, tally);
			columns.add(new MeasureColumn(measure, ingestedAt(measure), tally));
		}

		List<DistrictSummary> districts = geo.districts();
		Map<Integer, Integer> pinnedPopulation = populationYear == null ? Map.of() : populationOf(populationYear);

		List<DistrictRow> rows = new ArrayList<>(districts.size());
		for (DistrictSummary district : districts) {
			Map<Measure, Long> values = new EnumMap<>(Measure.class);
			for (Measure measure : wanted) {
				values.put(measure, tallies.get(measure).of(district.id()));
			}
			Integer population = null;
			Integer year = null;
			if (base == Denominator.POPULATION) {
				if (populationYear == null) {
					population = district.population();
					year = district.populationYear();
				}
				else {
					// Año fijado: la junta que no lo tenga sale sin denominador y sin tasa. No se cae al año más
					// reciente, que sería responder otra pregunta sin decirlo (ADR-015).
					population = pinnedPopulation.get(district.id());
					year = population == null ? null : populationYear;
				}
			}
			rows.add(new DistrictRow(district.id(), district.name(), district.shortName(), district.padronId(),
					population, year, values));
		}

		rows.sort(comparator(order));
		return new CrossTab(bounds, base, populationYear, columns, rows);
	}

	private TerritorialTally tallyOf(Measure measure, DateWindow window) {
		return switch (measure) {
			case CITIZEN_REQUESTS -> citizen.requestsByDistrict(window);
			case URBAN_PREMISES -> urban.premisesByDistrict(window);
			case URBAN_LICENCES -> urban.licencesByDistrict(window);
		};
	}

	private java.time.Instant ingestedAt(Measure measure) {
		return switch (measure) {
			case CITIZEN_REQUESTS -> citizen.ingestedAt();
			case URBAN_PREMISES, URBAN_LICENCES -> urban.ingestedAt();
		};
	}

	/** Las juntas del eje, ordenadas por id. Quien traduce la petición las necesita para saber si una existe. */
	public List<DistrictSummary> districts() {
		return geo.districts();
	}

	/**
	 * La serie de padrón de una junta, ordenada por año. Va en la ficha entera y no resumida porque el hueco de
	 * 2023 es parte del dato: verlo es lo que explica por qué una serie de tasas no es continua (S2.1).
	 */
	public List<DistrictPopulation> populationSeries(int districtId) {
		return geo.populations().stream().filter(record -> record.districtId() == districtId)
				.sorted(Comparator.comparingInt(DistrictPopulation::year)).toList();
	}

	private Map<Integer, Integer> populationOf(int year) {
		Map<Integer, Integer> byDistrict = new HashMap<>();
		for (DistrictPopulation record : geo.populations()) {
			if (record.year() == year) {
				byDistrict.put(record.districtId(), record.population());
			}
		}
		return byDistrict;
	}

	/**
	 * El desempate es siempre el id de junta ascendente, también al ordenar por una medida: dos juntas con el
	 * mismo valor tienen que salir siempre en el mismo orden o la paginación de quien lea deja de ser estable.
	 * Las juntas sin denominador no se ordenan por tasa: se ordena por el valor absoluto de la medida.
	 */
	private static Comparator<DistrictRow> comparator(CrossTabSort sort) {
		Comparator<DistrictRow> primary = sort.byMeasure()
				? Comparator.comparingLong(row -> row.value(sort.measure()))
				: Comparator.comparingInt(DistrictRow::districtId);
		if (!sort.ascending()) {
			primary = primary.reversed();
		}
		return primary.thenComparingInt(DistrictRow::districtId);
	}

}
