package es.zaragoza.observatory.geo.domain;

import java.util.List;
import java.util.Optional;

/** Puerto de persistencia del padrón por junta y año (S0.4, S2.1). Upsert idempotente por (junta, año). */
public interface PopulationRepository {

	void upsert(PopulationRecord record);

	/** Serie de una junta, del año más reciente al más antiguo (orden explícito, regla 8). */
	List<PopulationRecord> findByDistrict(int districtId);

	/** El año más reciente disponible para una junta; vacío si no hay padrón. */
	Optional<PopulationRecord> findLatest(int districtId);

	/** Toda la serie, ordenada por junta y por año descendente (orden explícito, regla 8). */
	List<PopulationRecord> findAll();

	/** Los años presentes en la serie, descendente. La serie no es continua: falta 2023 (S2.1). */
	List<Integer> years();

	long count();

}
