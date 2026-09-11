package es.zaragoza.observatory.territory.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import es.zaragoza.observatory.shared.DateWindow;
import es.zaragoza.observatory.shared.TerritorialTally;

/**
 * El cruce: una fila por junta, una columna por medida (ADR-019 §1).
 * <p>
 * Cada columna viene con su cobertura del conjunto, y esa es la pieza que hace que la tabla se pueda leer:
 * <b>la suma de las 29 filas de una columna no es el total de esa medida</b> —sin punto no hay junta, ADR-011
 * §2—, y la diferencia no es la misma en las dos fuentes (del 16 % al 45 % de cobertura en {@code citizen} según
 * el año, 89,4 % en {@code urban}).
 *
 * @param window ventana pedida, que cada medida aplica sobre su propia fecha (ADR-019 §5)
 * @param denominator denominador pedido
 * @param populationYear año de padrón fijado por quien pregunta, {@code null} si se toma el último de cada junta
 * @param columns una por medida, en el orden en que se pidieron
 * @param rows una por junta, ya ordenadas (regla 8: el criterio es explícito y lo fija el backend)
 */
public record CrossTab(DateWindow window, Denominator denominator, Integer populationYear,
		List<MeasureColumn> columns, List<DistrictRow> rows) {

	public CrossTab {
		columns = columns == null ? List.of() : List.copyOf(columns);
		rows = rows == null ? List.of() : List.copyOf(rows);
	}

	/**
	 * Una columna: la medida, cuándo se leyó su origen por última vez y la cobertura con la que hay que leerla.
	 *
	 * @param ingestedAt fin de la última ingesta con éxito de la fuente; {@code null} si ninguna ha terminado
	 */
	public record MeasureColumn(Measure measure, Instant ingestedAt, TerritorialTally tally) {
	}

	/**
	 * Una fila: la junta, su denominador y el valor de cada medida.
	 *
	 * @param values valor por medida; todas las medidas pedidas están presentes, con 0 si la junta no tiene nada
	 * @param population padrón de la junta, {@code null} si no hay para el año que corresponde
	 * @param populationYear año de ese padrón, {@code null} si no hay
	 */
	public record DistrictRow(int districtId, String name, String shortName, Integer padronId, Integer population,
			Integer populationYear, Map<Measure, Long> values) {

		public DistrictRow {
			values = values == null ? Map.of() : Map.copyOf(values);
			if ((population == null) != (populationYear == null)) {
				throw new IllegalArgumentException("population and populationYear go together (district "
						+ districtId + ")");
			}
		}

		public long value(Measure measure) {
			return values.getOrDefault(measure, 0L);
		}

		/**
		 * Valor por mil habitantes, {@code null} si la junta no tiene padrón para el año usado. Se publica porque
		 * el denominador y su año viajan al lado, así que quien lea puede rehacerlo o ignorarlo (regla 7).
		 */
		public Double perThousandInhabitants(Measure measure) {
			if (population == null || population == 0) {
				return null;
			}
			return value(measure) * 1000.0 / population;
		}

	}

}
