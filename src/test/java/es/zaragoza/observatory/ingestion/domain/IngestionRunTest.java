package es.zaragoza.observatory.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import es.zaragoza.observatory.ingestion.RawPage;
import es.zaragoza.observatory.ingestion.RunStatus;
import es.zaragoza.observatory.shared.DatasetRef;
import es.zaragoza.observatory.shared.IngestionRunId;
import es.zaragoza.observatory.shared.Sources;

class IngestionRunTest {

	static final DatasetRef DATASET = DatasetRef.of(Sources.DATA_SPACE, "catalogo");
	static final Instant T0 = Instant.parse("2026-09-06T10:00:00Z");

	@Test
	void accumulatesPagesAndKeepsLastSourceLastModified() {
		var run = IngestionRun.start(IngestionRunId.newId(), DATASET, T0);
		Instant lm = Instant.parse("2026-01-20T12:12:38Z");

		run.pageFetched(page(0, 500, lm));
		run.pageFetched(page(1, 36, null));

		assertThat(run.status()).isEqualTo(RunStatus.RUNNING);
		assertThat(run.pages()).isEqualTo(2);
		assertThat(run.records()).isEqualTo(536);
		assertThat(run.sourceLastModified()).isEqualTo(lm);
		assertThat(run.finishedAt()).isNull();
	}

	@Test
	void succeedAndFailAreTerminal() {
		var ok = IngestionRun.start(IngestionRunId.newId(), DATASET, T0);
		ok.succeed(T0.plusSeconds(5));
		assertThat(ok.status()).isEqualTo(RunStatus.SUCCEEDED);
		assertThat(ok.finishedAt()).isEqualTo(T0.plusSeconds(5));
		assertThatIllegalStateException().isThrownBy(() -> ok.fail(T0, "x"));
		assertThatIllegalStateException().isThrownBy(() -> ok.pageFetched(page(0, 1, null)));

		var ko = IngestionRun.start(IngestionRunId.newId(), DATASET, T0);
		ko.fail(T0.plusSeconds(1), " ");
		assertThat(ko.status()).isEqualTo(RunStatus.FAILED);
		assertThat(ko.error()).isEqualTo("unknown error");
		assertThat(ko.toSummary().status()).isEqualTo(RunStatus.FAILED);
	}

	static RawPage page(int number, int records, Instant lastModified) {
		return new RawPage(number, number * 500, URI.create("https://example.test/x.json?rows=500"),
				"application/json", "{}", records, null, lastModified, null, T0, Duration.ofMillis(10));
	}

}
