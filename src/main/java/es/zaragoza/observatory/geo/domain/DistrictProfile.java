package es.zaragoza.observatory.geo.domain;

import java.util.List;
import java.util.Objects;

/**
 * Lo que aporta el detalle de una junta sobre lo que ya trae el listado (S2.1): la segunda numeración y el
 * padrón. El {@code padronId} viene repetido en cada año de {@code indicadores} y es estable entre años; si
 * llegara a variar, la traducción falla en vez de elegir uno.
 *
 * @param districtId junta ({@code distrito.id}, comprobado contra {@code iddatosab})
 * @param padronId {@code idpadron}; {@code null} si la junta no publica indicadores
 * @param population serie de padrón, del año más reciente al más antiguo
 */
public record DistrictProfile(int districtId, Integer padronId, List<PopulationRecord> population) {

	public DistrictProfile {
		if (districtId <= 0) {
			throw new IllegalArgumentException("districtId must be positive");
		}
		population = population == null ? List.of() : List.copyOf(population);
		Objects.requireNonNull(population);
	}

}
