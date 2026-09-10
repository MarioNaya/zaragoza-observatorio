package es.zaragoza.observatory.spending.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * La política que hace barata a esta fuente (S3.2 §3): una instantánea publicada no se reescribe, así que se lee
 * una vez y se congela. Es la diferencia con {@link RetrySchedule}, donde todo hay que refrescarlo.
 */
class SnapshotScheduleTest {

	static final Instant NOW = Instant.parse("2026-09-10T10:00:00Z");

	final SnapshotSchedule schedule = new SnapshotSchedule(Duration.ofDays(1), Duration.ofDays(30),
			Duration.ofDays(7));

	@Test
	void unaInstantaneaCargadaQueNoEsLaUltimaSeCongela() {
		assertThat(schedule.nextAttempt(SnapshotStatus.LOADED, 1, NOW, false)).isNull();
	}

	@Test
	void laMasRecienteSeReleeConCadencia() {
		assertThat(schedule.nextAttempt(SnapshotStatus.LOADED, 1, NOW, true)).isEqualTo(NOW.plus(Duration.ofDays(7)));
	}

	@Test
	void unaInstantaneaAusenteOVaciaEsperaCadaVezMasConTope() {
		assertThat(schedule.nextAttempt(SnapshotStatus.ABSENT, 1, NOW, false)).isEqualTo(NOW.plus(Duration.ofDays(1)));
		assertThat(schedule.nextAttempt(SnapshotStatus.ABSENT, 2, NOW, false)).isEqualTo(NOW.plus(Duration.ofDays(2)));
		assertThat(schedule.nextAttempt(SnapshotStatus.EMPTY, 3, NOW, false)).isEqualTo(NOW.plus(Duration.ofDays(4)));
		assertThat(schedule.nextAttempt(SnapshotStatus.ABSENT, 40, NOW, false))
				.as("con tope, para no esperar años").isEqualTo(NOW.plus(Duration.ofDays(30)));
	}

	@Test
	void unaLecturaQueNoConcluyeSoloAplazaUnPoco() {
		// Un fallo de red no dice nada de la instantánea: solo que ahora no se pudo.
		assertThat(schedule.nextAttempt(null, 1, NOW, false)).isEqualTo(NOW.plus(Duration.ofHours(1)));
	}

	@Test
	void unaInstantaneaReciencensadaTocaYa() {
		assertThat(schedule.nextAttempt(SnapshotStatus.PENDING, 0, NOW, false)).isEqualTo(NOW);
	}

}
