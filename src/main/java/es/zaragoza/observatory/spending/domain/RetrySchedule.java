package es.zaragoza.observatory.spending.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Cuándo se vuelve a pedir el detalle de un proceso (ADR-017 §5). Es la política que hace asumible el coste de
 * esta fuente: reintentar cada día los 2.379 procesos sin release serían 2.379 peticiones diarias para nada.
 * <p>
 * Distingue tres situaciones porque no son la misma:
 * <ul>
 * <li><b>Nunca pedido</b>: ya toca.</li>
 * <li><b>Sin release</b> (404 o {@code releases} vacío): espera creciente, {@code 1, 2, 4, 8, 16…} días con
 * tope. Como el 404 se concentra en los expedientes recientes, los que más probabilidad tienen de publicarse son
 * justo los que menos tiempo llevan esperando.</li>
 * <li><b>Publicado</b>: se refresca, porque la fuente no publica {@code ETag} ni {@code Last-Modified} y un
 * proceso cambia de licitación a adjudicación sin avisar. Con licitación <b>activa</b>, antes que los demás: es
 * lo único que puede cambiar pronto.</li>
 * </ul>
 * La política vive aquí y el adaptador la materializa en una columna, para que el planificador pueda pedir «los
 * que tocan» con un índice en vez de traerse la tabla entera y filtrarla en memoria.
 *
 * @param missingInitialBackoff primera espera tras un 404 o un release vacío
 * @param missingMaxBackoff tope de esa espera
 * @param activeRefresh cada cuánto se refresca un proceso con licitación activa
 * @param publishedRefresh cada cuánto se refresca un proceso publicado y cerrado
 */
public record RetrySchedule(Duration missingInitialBackoff, Duration missingMaxBackoff, Duration activeRefresh,
		Duration publishedRefresh) {

	/** Tope de duplicaciones de la espera, para que el desplazamiento no desborde antes de toparse. */
	private static final int MAX_DOUBLINGS = 20;

	public RetrySchedule {
		requirePositive(missingInitialBackoff, "missingInitialBackoff");
		requirePositive(missingMaxBackoff, "missingMaxBackoff");
		requirePositive(activeRefresh, "activeRefresh");
		requirePositive(publishedRefresh, "publishedRefresh");
		if (missingMaxBackoff.compareTo(missingInitialBackoff) < 0) {
			throw new IllegalArgumentException("missingMaxBackoff must not be shorter than missingInitialBackoff");
		}
	}

	/**
	 * Cuándo toca el siguiente intento después de uno que acaba de ocurrir.
	 *
	 * @param status estado observado, o {@code null} si la lectura no concluyó
	 * @param attempts intentos acumulados incluido este
	 * @param tenderActive si la licitación del proceso está activa
	 */
	public Instant nextAttempt(ReleaseStatus status, int attempts, Instant attemptedAt, boolean tenderActive) {
		if (status == null) {
			// Lectura no concluyente: no dice nada del proceso, solo que ahora mismo no se pudo. Se aplaza lo
			// justo para no reintentarlo dentro del mismo lote.
			return attemptedAt.plus(missingInitialBackoff.dividedBy(24));
		}
		return switch (status) {
			case PENDING -> attemptedAt;
			case ABSENT, EMPTY -> attemptedAt.plus(backoff(attempts));
			case PUBLISHED -> attemptedAt.plus(tenderActive ? activeRefresh : publishedRefresh);
		};
	}

	/** La espera del intento {@code n}: la inicial duplicada {@code n-1} veces, con tope. */
	Duration backoff(int attempts) {
		int doublings = Math.min(Math.max(attempts, 1) - 1, MAX_DOUBLINGS);
		Duration backoff = missingInitialBackoff.multipliedBy(1L << doublings);
		return backoff.compareTo(missingMaxBackoff) > 0 ? missingMaxBackoff : backoff;
	}

	private static void requirePositive(Duration value, String name) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException(name + " must be a positive duration");
		}
	}

}
