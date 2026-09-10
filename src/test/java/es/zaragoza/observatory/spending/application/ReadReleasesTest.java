package es.zaragoza.observatory.spending.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import es.zaragoza.observatory.spending.domain.DueRelease;
import es.zaragoza.observatory.spending.domain.ReleaseContent;
import es.zaragoza.observatory.spending.domain.ReleaseRead;
import es.zaragoza.observatory.spending.domain.ReleaseSource;
import es.zaragoza.observatory.spending.domain.ReleaseStatus;
import es.zaragoza.observatory.spending.domain.RetrySchedule;
import es.zaragoza.observatory.spending.domain.Stage;
import es.zaragoza.observatory.spending.domain.Tender;
import es.zaragoza.observatory.spending.support.RecordingProcesses;

/**
 * El lote de lectura del detalle: qué se guarda de cada desenlace y, sobre todo, qué <b>no</b> pasa cuando la
 * fuente falla. Confundir un fallo de red con un 404 convertiría una caída del ayuntamiento en «estos procesos
 * no existen» (ADR-017 §5).
 */
class ReadReleasesTest {

	static final Instant NOW = Instant.parse("2026-09-10T10:00:00Z");

	static final RetrySchedule SCHEDULE = new RetrySchedule(Duration.ofDays(1), Duration.ofDays(30),
			Duration.ofDays(7), Duration.ofDays(30));

	final RecordingProcesses processes = new RecordingProcesses();

	final Map<String, ReleaseRead> answers = new LinkedHashMap<>();

	final ReleaseSource source = new ReleaseSource() {

		@Override
		public ReleaseRead read(String ocid) {
			return answers.getOrDefault(ocid, ReleaseRead.absent(ocid));
		}

		@Override
		public Optional<List<String>> documentedOcids() {
			return Optional.empty();
		}
	};

	final ReadReleases readReleases = new ReadReleases(processes, source, SCHEDULE,
			Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void guardaCadaDesenlaceConSuEstadoYSuProximoIntento() {
		processes.pending.addAll(List.of(new DueRelease("a", ReleaseStatus.PENDING, 0),
				new DueRelease("b", ReleaseStatus.PENDING, 0), new DueRelease("c", ReleaseStatus.ABSENT, 2)));
		answers.put("a", ReleaseRead.published("a", content("complete", true)));
		answers.put("b", ReleaseRead.empty("b"));
		answers.put("c", ReleaseRead.absent("c"));

		var summary = readReleases.readDue(10);

		assertThat(summary.requested()).isEqualTo(3);
		assertThat(summary.published()).isEqualTo(1);
		assertThat(summary.empty()).isEqualTo(1);
		assertThat(summary.absent()).isEqualTo(1);
		assertThat(summary.abandoned()).isFalse();

		var published = processes.recorded.get("a");
		assertThat(published.status()).isEqualTo(ReleaseStatus.PUBLISHED);
		assertThat(published.stage()).isEqualTo(Stage.COMMITTED);
		assertThat(published.attempts()).isEqualTo(1);
		assertThat(published.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));

		assertThat(processes.recorded.get("b").content()).isNull();
		// El tercero llevaba dos intentos: el suyo hace tres, y la espera ya va por cuatro días.
		assertThat(processes.recorded.get("c").attempts()).isEqualTo(3);
		assertThat(processes.recorded.get("c").nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofDays(4)));
	}

	@Test
	void unaLicitacionActivaSeReleeAntes() {
		processes.pending.add(new DueRelease("a", ReleaseStatus.PENDING, 0));
		answers.put("a", ReleaseRead.published("a", content("active", false)));

		readReleases.readDue(10);

		assertThat(processes.recorded.get("a").stage()).isEqualTo(Stage.PLANNED);
		assertThat(processes.recorded.get("a").nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
	}

	@Test
	void unaLecturaQueNoConcluyeNoCambiaElEstadoNiCuentaComoIntento() {
		processes.pending.add(new DueRelease("a", ReleaseStatus.PUBLISHED, 3));
		answers.put("a", ReleaseRead.unreadable("a"));

		var summary = readReleases.readDue(10);

		assertThat(summary.unreadable()).isEqualTo(1);
		assertThat(processes.recorded).as("nada se ha dado por sabido").isEmpty();
		assertThat(processes.failed).containsKey("a");
		assertThat(processes.failed.get("a")).isAfter(NOW).isBefore(NOW.plus(Duration.ofDays(1)));
	}

	@Test
	void siLaFuenteNoRespondeElLoteSeCorta() {
		// 30 procesos y una fuente caída: no se gastan 30 peticiones para averiguar lo mismo 30 veces.
		IntStream.range(0, 30)
				.forEach(i -> processes.pending.add(new DueRelease("ocid-" + i, ReleaseStatus.PENDING, 0)));
		processes.pending.forEach(due -> answers.put(due.ocid(), ReleaseRead.unreadable(due.ocid())));

		var summary = readReleases.readDue(30);

		assertThat(summary.abandoned()).isTrue();
		assertThat(summary.requested()).isLessThan(30).isGreaterThanOrEqualTo(10);
	}

	@Test
	void unLoteVacioNoPideNada() {
		var summary = readReleases.readDue(10);

		assertThat(summary.requested()).isZero();
		assertThat(processes.recorded).isEmpty();
	}

	private static ReleaseContent content(String tenderStatus, boolean signed) {
		var tender = new Tender("título", "descripción", tenderStatus, "open", "services", "priceOnly", 3, null,
				null);
		var contracts = signed
				? List.of(new es.zaragoza.observatory.spending.domain.Contract("c1", "a1", null, null, "terminated",
						NOW, null, null, null))
				: List.<es.zaragoza.observatory.spending.domain.Contract>of();
		return new ReleaseContent(NOW, "r1", "contract", "tender", tender, "Ayuntamiento", "L01502973", List.of(),
				contracts, List.of());
	}

}
