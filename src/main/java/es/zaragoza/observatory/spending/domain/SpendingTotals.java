package es.zaragoza.observatory.spending.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * El universo de la contratación tal como se ha podido observar, con sus huecos delante y no restando (regla 7).
 * <p>
 * Es lo que hace publicable una fuente en la que el 29,7 % de los procesos no tiene detalle y el 28,4 % no
 * aparece en el listado que la propia API documenta: quien lee ve cuántos procesos hay, cuántos se han podido
 * leer y cuántos no, sin tener que deducirlo.
 *
 * @param processes procesos censados
 * @param byReleaseStatus reparto por situación del detalle, con los cuatro estados presentes aunque valgan 0
 * @param notInDocumentedList procesos que el listado sin filtro de la API no publica
 * @param withoutStage procesos con release cuya etapa el documento no sostiene
 * @param emptyContracts contratos que son una cáscara con identificador y nada más
 * @param awards adjudicaciones
 * @param naturalPersonParties partes adjudicatarias de persona física, de las que no se guarda identidad
 * @param tenderedAmount suma de importes licitados; <b>no</b> es dinero comprometido
 * @param awardedAmount suma de importes adjudicados; <b>no</b> es dinero pagado
 * @param earliestPublishedAt publicación más antigua ingerida
 * @param latestPublishedAt publicación más reciente ingerida
 */
public record SpendingTotals(long processes, Map<ReleaseStatus, Long> byReleaseStatus, long notInDocumentedList,
		long withoutStage, long emptyContracts, long awards, long naturalPersonParties, BigDecimal tenderedAmount,
		BigDecimal awardedAmount, Instant earliestPublishedAt, Instant latestPublishedAt) {

	public SpendingTotals {
		byReleaseStatus = byReleaseStatus == null ? Map.of() : Map.copyOf(byReleaseStatus);
	}

}
