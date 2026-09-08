package es.zaragoza.observatory.geo.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Puerto de persistencia de las juntas. El upsert es idempotente (regla 5): reejecutar la ingesta de la capa
 * base no duplica nada ni pierde el {@code padronId}, que llega después por otra petición (S2.1).
 */
public interface DistrictRepository {

	/**
	 * Inserta o actualiza nombre, tipo y contorno conservando {@code firstSeenAt} y el {@code padronId} que ya
	 * hubiera: el listado de juntas no lo trae.
	 */
	void upsert(District district, Instant seenAt);

	/** Fija el {@code idpadron} leído del detalle de la junta. Devuelve si cambió algo. */
	boolean updatePadronId(int districtId, int padronId);

	Optional<District> findById(int id);

	/** Todas las juntas ordenadas por id (orden explícito, regla 8). La geometría no viaja. */
	List<District> findAll();

	long count();

}
