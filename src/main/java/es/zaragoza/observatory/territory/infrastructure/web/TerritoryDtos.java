package es.zaragoza.observatory.territory.infrastructure.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import es.zaragoza.observatory.shared.DateWindow;
import es.zaragoza.observatory.shared.TerritorialTally;
import es.zaragoza.observatory.territory.domain.CrossTab;
import es.zaragoza.observatory.territory.domain.CrossTab.DistrictRow;
import es.zaragoza.observatory.territory.domain.CrossTab.MeasureColumn;
import es.zaragoza.observatory.territory.domain.Measure;

/**
 * Cuerpos de respuesta del módulo {@code territory} (ADR-019).
 * <p>
 * El sobre no es el de los módulos de dominio y no puede serlo: aquellos tienen <b>una</b> fuente y publican
 * {@code source} e {@code ingestedAt} en la raíz. Un cruce tiene una por medida, así que aquí cada medida lleva
 * los suyos y en la raíz no hay un {@code ingestedAt} único que sería mentira.
 */
final class TerritoryDtos {

	private TerritoryDtos() {
	}

	record WindowDto(Instant from, Instant to) {

		static WindowDto of(DateWindow window) {
			return new WindowDto(window.from(), window.to());
		}
	}

	/**
	 * Cobertura de una columna sobre la ciudad entera (ADR-019 §6).
	 *
	 * @param total registros en la ventana, tengan junta o no
	 * @param withPoint los que traen punto, únicos que pueden tener junta
	 * @param assigned los que cayeron dentro de una junta: <b>este</b> es el que suman las 29 filas
	 * @param unassigned los que no, y que por eso no aparecen en ninguna fila
	 * @param pointCoverage proporción con punto, {@code null} si no hay registros que medir
	 */
	record CoverageDto(long total, long withPoint, long assigned, long unassigned, Double pointCoverage) {

		static CoverageDto of(TerritorialTally tally) {
			return new CoverageDto(tally.total(), tally.withPoint(), tally.assigned(), tally.unassigned(),
					tally.pointCoverage());
		}
	}

	/**
	 * Una columna del cruce, descrita por sí misma: qué cuenta, sobre qué fecha aplica la ventana, cuándo se leyó
	 * su origen y con qué cobertura hay que leerla.
	 */
	record MeasureDto(String id, String module, String unit, String dateField, String dateFieldMeaning,
			Instant ingestedAt, CoverageDto coverage) {

		static MeasureDto of(MeasureColumn column) {
			Measure measure = column.measure();
			return new MeasureDto(measure.id(), measure.module(), measure.unit().name().toLowerCase(Locale.ROOT),
					measure.dateField(), measure.dateFieldMeaning(), column.ingestedAt(),
					CoverageDto.of(column.tally()));
		}
	}

	/**
	 * Una fila: la junta con su denominador y el valor de cada medida pedida.
	 *
	 * @param population padrón de la junta en {@code populationYear}, {@code null} si no hay
	 * @param perThousandInhabitants valor por mil habitantes de cada medida; vacío si la junta no tiene padrón
	 */
	record RowDto(int districtId, String name, String shortName, Integer padronId, Integer population,
			Integer populationYear, Map<String, Long> values, Map<String, Double> perThousandInhabitants) {

		static RowDto of(DistrictRow row, List<Measure> measures) {
			var values = new LinkedHashMap<String, Long>();
			var rates = new LinkedHashMap<String, Double>();
			for (Measure measure : measures) {
				values.put(measure.id(), row.value(measure));
				Double rate = row.perThousandInhabitants(measure);
				if (rate != null) {
					rates.put(measure.id(), rate);
				}
			}
			return new RowDto(row.districtId(), row.name(), row.shortName(), row.padronId(), row.population(),
					row.populationYear(), values, rates);
		}
	}

	/** El padrón de una junta en un año, para la serie de la ficha. */
	record PopulationDto(int year, int population) {
	}

	/**
	 * La matriz: las 29 juntas por las medidas pedidas.
	 *
	 * @param axis la unidad territorial de las filas; hoy solo {@code district} (no hay secciones censales, S0.4)
	 * @param districts cuántas filas, que son siempre las juntas completas aunque alguna valga 0
	 */
	record CrossTabDto(String axis, WindowDto window, String denominator, Integer populationYear, String sort,
			List<MeasureDto> measures, int districts, List<RowDto> items) {

		static CrossTabDto of(CrossTab tab, List<Measure> measures, String sort) {
			return new CrossTabDto("district", WindowDto.of(tab.window()),
					tab.denominator().name().toLowerCase(Locale.ROOT), tab.populationYear(), sort,
					tab.columns().stream().map(MeasureDto::of).toList(), tab.rows().size(),
					tab.rows().stream().map(row -> RowDto.of(row, measures)).toList());
		}
	}

	/** La ficha de una junta: la misma fila, con la serie de padrón entera al lado. */
	record DistrictCardDto(WindowDto window, String denominator, Integer populationYear, List<MeasureDto> measures,
			RowDto item, List<PopulationDto> populationSeries) {
	}

	/**
	 * Sobre de respuesta. Mismo nombre y misma forma que en los módulos de dominio salvo por lo que aquí no
	 * existe: no hay {@code source} ni {@code ingestedAt} en la raíz porque hay uno por medida (ADR-019 §2).
	 */
	record ApiItem<T>(List<String> caveats, T item) {
	}

}
