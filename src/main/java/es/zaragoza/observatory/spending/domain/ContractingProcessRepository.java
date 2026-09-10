package es.zaragoza.observatory.spending.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Puerto de persistencia de la contratación. El upsert por {@code ocid} es idempotente (regla 5): reejecutar una
 * ingesta no duplica nada y reingerir un proceso ya visto conserva su {@code firstSeenAt}.
 * <p>
 * Hay dos escrituras porque hay dos pasos, y no se pisan: el censo trae ocids y el detalle trae contenido. Un
 * proceso censado y aún sin leer es una fila legítima, no un registro a medias.
 * <p>
 * <b>Nada se borra nunca</b>, ni siquiera un ocid que dejara de aparecer en el listado. El histórico de un
 * proceso es el producto, como en ADR-013 con las fichas del catálogo; lo que dice si algo sigue ahí es
 * {@code lastSeenAt}.
 */
public interface ContractingProcessRepository {

	/**
	 * Da de alta los ocids nuevos como {@link ReleaseStatus#PENDING} y refresca {@code lastSeenAt} de los ya
	 * conocidos. No toca el detalle de nadie: el censo solo dice qué existe.
	 *
	 * @return cuántos ocids se han visto
	 */
	int upsertCensus(List<String> ocids, Instant seenAt);

	/**
	 * Marca qué procesos aparecen en el listado <b>sin filtro</b> y desmarca los que no, en las dos direcciones:
	 * si un proceso vuelve a aparecer, la marca vuelve. Es un hecho fechado sobre el listado, no sobre el dato
	 * (regla 6).
	 *
	 * @return cuántos procesos quedaron marcados como presentes en el listado documentado
	 */
	int markDocumentedList(Set<String> documented);

	/** Todos los ocids censados, para comprobar que el listado documentado sigue siendo subconjunto del ampliado. */
	Set<String> allOcids();

	/** Procesos a los que les toca lectura de detalle, los más atrasados primero. */
	List<DueRelease> due(Instant now, int limit);

	/**
	 * Guarda el resultado de una lectura concluyente: estado, contenido si lo hay, contabilidad del intento y
	 * cuándo toca el siguiente. Reemplaza enteras las adjudicaciones, los contratos y los CPV del proceso, por lo
	 * mismo que en {@code urban}: el documento las publica completas, así que lo que desaparece ha desaparecido.
	 */
	void recordRelease(String ocid, ReleaseStatus status, ReleaseContent content, Stage stage, int attempts,
			Instant attemptedAt, Instant nextAttemptAt);

	/**
	 * Guarda un intento que no concluyó (tiempo agotado, 5xx, JSON ilegible). <b>No cambia el estado ni cuenta
	 * como intento</b>: un fallo de red no es un 404, y tratarlo como tal convertiría una caída de la fuente en
	 * «estos procesos no existen».
	 */
	void recordFailedAttempt(String ocid, Instant attemptedAt, Instant nextAttemptAt);

	long count();

	long count(ProcessQuery filters);

	Optional<ContractingProcess> byOcid(String ocid);

	ProcessPage search(ProcessQuery query);

	/** Agrega por el eje pedido aplicando los filtros; el orden lo fija el adaptador (regla 8). */
	List<AggregationBucket> aggregate(AggregationAxis axis, ProcessQuery filters);

	/** El universo con sus huecos: recuentos por estado, importes y extremos de la serie. */
	SpendingTotals totals(ProcessQuery filters);

}
