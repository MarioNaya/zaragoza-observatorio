package es.zaragoza.observatory.spending.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * La cadencia de reintento de ADR-017 §5, que es lo que hace asumible el coste de esta fuente: sin ella,
 * reintentar los 2.379 procesos sin release serían 2.379 peticiones diarias para nada.
 */
class RetryScheduleTest {

	static final Instant NOW = Instant.parse("2026-09-10T10:00:00Z");

	static final RetrySchedule SCHEDULE = new RetrySchedule(Duration.ofDays(1), Duration.ofDays(30),
			Duration.ofDays(7), Duration.ofDays(30));

	@Test
	void laEsperaDeUnProcesoSinReleaseSeDuplicaHastaElTope() {
		assertThat(SCHEDULE.backoff(1)).isEqualTo(Duration.ofDays(1));
		assertThat(SCHEDULE.backoff(2)).isEqualTo(Duration.ofDays(2));
		assertThat(SCHEDULE.backoff(3)).isEqualTo(Duration.ofDays(4));
		assertThat(SCHEDULE.backoff(5)).isEqualTo(Duration.ofDays(16));
		assertThat(SCHEDULE.backoff(6)).as("se topa, no crece hasta el infinito").isEqualTo(Duration.ofDays(30));
		assertThat(SCHEDULE.backoff(40)).isEqualTo(Duration.ofDays(30));
	}

	@Test
	void un404YUnReleaseVacioEsperanLoMismo() {
		assertThat(SCHEDULE.nextAttempt(ReleaseStatus.ABSENT, 1, NOW, false)).isEqualTo(NOW.plus(Duration.ofDays(1)));
		assertThat(SCHEDULE.nextAttempt(ReleaseStatus.EMPTY, 1, NOW, false)).isEqualTo(NOW.plus(Duration.ofDays(1)));
	}

	@Test
	void unProcesoConLicitacionActivaSeReleeAntesQueUnoCerrado() {
		assertThat(SCHEDULE.nextAttempt(ReleaseStatus.PUBLISHED, 1, NOW, true))
				.isEqualTo(NOW.plus(Duration.ofDays(7)));
		assertThat(SCHEDULE.nextAttempt(ReleaseStatus.PUBLISHED, 1, NOW, false))
				.isEqualTo(NOW.plus(Duration.ofDays(30)));
	}

	@Test
	void unaLecturaQueNoConcluyeSoloAplazaUnPoco() {
		// No dice nada del proceso: solo que ahora mismo no se pudo. No se castiga con una espera de días.
		Instant next = SCHEDULE.nextAttempt(null, 3, NOW, false);

		assertThat(next).isAfter(NOW).isBefore(NOW.plus(Duration.ofDays(1)));
	}

	@Test
	void unProcesoNuncaPedidoTocaYa() {
		assertThat(SCHEDULE.nextAttempt(ReleaseStatus.PENDING, 0, NOW, false)).isEqualTo(NOW);
	}

}
