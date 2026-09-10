package es.zaragoza.observatory.spending.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Cuándo se vuelve a leer una instantánea del presupuesto (S3.2 §3 y §11). Es la hermana de
 * {@link RetrySchedule}, y es mucho más barata por un hecho medido: <b>una instantánea publicada no se
 * reescribe</b>, así que se lee una vez y se congela.
 * <p>
 * Cuatro situaciones:
 * <ul>
 * <li><b>Cargada y no es la última</b>: {@code null}, congelada. Ninguna petición más, nunca. Ahí está la
 * diferencia con OCDS, donde cada proceso hay que refrescarlo porque cambia de licitación a adjudicación.</li>
 * <li><b>Cargada y es la última</b>: se refresca con cadencia, porque la evidencia de que el pasado no cambia
 * llega hasta donde llega —cinco días sobre tres partidas— y la foto del mes en curso es la única que la fuente
 * podría rehacer.</li>
 * <li><b>Sin contenido</b> (404 o vacía): espera creciente con tope, como en OCDS.</li>
 * <li><b>Lectura no concluyente</b>: se aplaza lo justo para no repetirla dentro del mismo lote. Un fallo de red
 * no dice nada de la instantánea.</li>
 * </ul>
 *
 * @param missingInitialBackoff primera espera tras un 404 o una instantánea vacía
 * @param missingMaxBackoff tope de esa espera
 * @param latestRefresh cada cuánto se relee la instantánea más reciente
 */
public record SnapshotSchedule(Duration missingInitialBackoff, Duration missingMaxBackoff, Duration latestRefresh) {

	/** Tope de duplicaciones de la espera, para que el desplazamiento no desborde antes de toparse. */
	private static final int MAX_DOUBLINGS = 20;

	public SnapshotSchedule {
		requirePositive(missingInitialBackoff, "missingInitialBackoff");
		requirePositive(missingMaxBackoff, "missingMaxBackoff");
		requirePositive(latestRefresh, "latestRefresh");
		if (missingMaxBackoff.compareTo(missingInitialBackoff) < 0) {
			throw new IllegalArgumentException("missingMaxBackoff must not be shorter than missingInitialBackoff");
		}
	}

	/**
	 * Cuándo toca la siguiente lectura, o {@code null} si no toca nunca más.
	 *
	 * @param status desenlace observado, o {@code null} si la lectura no concluyó
	 * @param attempts intentos acumulados incluido este
	 * @param latest si es la instantánea más reciente del censo
	 */
	public Instant nextAttempt(SnapshotStatus status, int attempts, Instant attemptedAt, boolean latest) {
		if (status == null) {
			return attemptedAt.plus(missingInitialBackoff.dividedBy(24));
		}
		return switch (status) {
			case PENDING -> attemptedAt;
			case ABSENT, EMPTY -> attemptedAt.plus(backoff(attempts));
			case LOADED -> latest ? attemptedAt.plus(latestRefresh) : null;
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
