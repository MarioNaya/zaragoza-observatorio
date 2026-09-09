package es.zaragoza.observatory.urban.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import es.zaragoza.observatory.geo.Assignment;

/**
 * Puerto de persistencia de los locales con licencia. El upsert por {@code sourceId} es idempotente (regla 5):
 * reejecutar una ingesta no duplica nada y reingerir un local ya visto conserva su {@code firstSeenAt}.
 * <p>
 * Las licencias de un local se reemplazan enteras en cada upsert, no se acumulan: el origen las publica siempre
 * completas dentro del local, así que una que desaparezca de la respuesta ha desaparecido de verdad y quedarse
 * con la copia vieja sería inventar un expediente que ya no existe.
 * <p>
 * La marca de agua sale de la propia tabla ({@code max(updated_at)}), no de un contador aparte, porque un
 * contador puede desincronizarse de los datos y la tabla no.
 */
public interface PremisesRepository {

	/** Inserta o actualiza la página entera con sus licencias. Devuelve cuántos locales se escribieron. */
	int upsertAll(List<LicensedPremises> premises, Instant seenAt);

	/** {@code lastUpdated} más reciente guardado; vacío si la tabla está vacía (primera ejecución: barrido). */
	Optional<Instant> latestUpdatedAt();

	/** Alta más antigua guardada, para declarar la cobertura temporal de lo ingerido. */
	Optional<Instant> earliestCreatedAt();

	long count();

	long countLicences();

	long count(PremisesQuery filters);

	PremisesPage search(PremisesQuery query);

	/** Agrega por el eje pedido aplicando los filtros; el orden lo fija el adaptador (regla 8). */
	List<AggregationBucket> aggregate(AggregationAxis axis, PremisesQuery filters);

	/**
	 * Cobertura de punto por año de licencia sobre <b>todos</b> los locales que pasan los filtros, tengan junta o
	 * no. Es lo único que permite comparar dos años de una serie territorial (ADR-015).
	 */
	List<YearCoverage> pointCoverageByLicenceYear(PremisesQuery filters);

	/** Reparto por estado de asignación territorial, con todos los estados presentes aunque valgan 0 (regla 7). */
	Map<Assignment, Long> assignmentCounts(PremisesQuery filters);

	/** Recuento por {@code estado} del origen, publicado como código (ADR-016 §6). */
	Map<Integer, Long> statusCounts(PremisesQuery filters);

}
