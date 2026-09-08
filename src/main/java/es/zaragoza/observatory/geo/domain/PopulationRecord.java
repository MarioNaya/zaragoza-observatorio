package es.zaragoza.observatory.geo.domain;

import java.time.Instant;

/**
 * El padrón de una junta en un año, tal como lo publica {@code distrito/{id}.indicadores} (S0.4, S2.1). Solo se
 * guardan los recuentos y la superficie: los índices derivados que trae la fuente (envejecimiento, dependencia,
 * feminidad…) son interpretaciones ya cocinadas y no se copian (regla 6); quien quiera un ratio lo compone con
 * el denominador que elija.
 * <p>
 * <b>La serie no es continua</b>: hay 2020, 2021, 2022 y 2024, y no 2023 (S2.1). Toda normalización debe decir
 * qué año usa.
 *
 * @param districtId junta ({@code distrito.id})
 * @param year año del padrón
 * @param total población total ({@code totpob})
 * @param spaniards nacionalidad española ({@code esp})
 * @param foreigners nacionalidad extranjera ({@code ext})
 * @param under16 menores de 16 ({@code menor16})
 * @param under18 menores de 18 ({@code menor18})
 * @param households hogares ({@code nhogar})
 * @param areaKm2 superficie en km² ({@code km2})
 * @param ingestedAt cuándo lo leyó este observatorio
 */
public record PopulationRecord(int districtId, int year, Integer total, Integer spaniards, Integer foreigners,
		Integer under16, Integer under18, Integer households, Double areaKm2, Instant ingestedAt) {

	public PopulationRecord {
		if (districtId <= 0) {
			throw new IllegalArgumentException("districtId must be positive");
		}
		if (year < 1900 || year > 2200) {
			throw new IllegalArgumentException("year out of range: " + year);
		}
		if (ingestedAt == null) {
			throw new IllegalArgumentException("ingestedAt must not be null");
		}
	}

	/** Densidad de población por km², o {@code null} si falta alguno de los dos datos o la superficie es 0. */
	public Double densityPerKm2() {
		if (total == null || areaKm2 == null || areaKm2 <= 0) {
			return null;
		}
		return total / areaKm2;
	}

}
