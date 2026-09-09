package es.zaragoza.observatory.citizen.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Puerto de persistencia de las quejas. El upsert por {@code sourceId} es idempotente (regla 5): reejecutar una
 * ingesta no duplica nada, y reingerir un registro ya visto conserva su {@code firstSeenAt}.
 * <p>
 * Las dos marcas de agua son las que hacen incremental la ingesta (S2.2): la fecha de alta más reciente que se
 * ha guardado y la fecha de actualización más reciente. Se leen de esta tabla, no de un contador aparte, porque
 * un contador puede desincronizarse de los datos y la tabla no.
 */
public interface ServiceRequestRepository {

	/** Inserta o actualiza la página entera. Devuelve cuántos registros se escribieron. */
	int upsertAll(List<ServiceRequest> requests, Instant seenAt);

	/** Alta más reciente guardada; vacío si la tabla está vacía (primera ejecución: carga completa). */
	Optional<Instant> latestRequestedAt();

	/** {@code updated_datetime} más reciente guardado; vacío si ninguno lo trae. */
	Optional<Instant> latestUpdatedAt();

	/** Alta más antigua guardada, para declarar la cobertura temporal de lo ingerido. */
	Optional<Instant> earliestRequestedAt();

	long count();

	/** Cuántos registros pasan los filtros; con el filtro {@code ONLY} es el recuento de INTERNAL (ADR-015). */
	long count(ServiceRequestQuery filters);

	ServiceRequestPage search(ServiceRequestQuery query);

	/** Agrega por el eje pedido aplicando los filtros de la consulta; el orden lo fija el adaptador (regla 8). */
	List<AggregationBucket> aggregate(AggregationAxis axis, ServiceRequestQuery filters);

	/**
	 * Cobertura de punto por año sobre <b>todos</b> los registros que pasan los filtros, tengan junta o no. Es lo
	 * único que permite comparar dos años de una serie territorial: dentro de un grupo por junta la cobertura es
	 * siempre del 100 % por construcción (ADR-015).
	 */
	List<YearCoverage> pointCoverageByYear(ServiceRequestQuery filters);

	/** Reparto por estado de asignación territorial y contraste con lo declarado, con los mismos filtros. */
	AssignmentCounts assignmentCounts(ServiceRequestQuery filters);

	/** Recuento por estado publicado, con todos los estados presentes aunque valgan 0. */
	Map<ServiceRequestStatus, Long> statusCounts(ServiceRequestQuery filters);

}
