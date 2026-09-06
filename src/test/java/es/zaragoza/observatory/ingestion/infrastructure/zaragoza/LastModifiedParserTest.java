package es.zaragoza.observatory.ingestion.infrastructure.zaragoza;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/** Valores reales observados en S0.1 y S0.5 (docs/spikes): zona CET/CEST en lugar de GMT. */
class LastModifiedParserTest {

	@Test
	void parsesCetAndCestAsLocalZaragozaTime() {
		assertThat(LastModifiedParser.parse("Tue, 20 Jan 2026 13:12:38 CET"))
				.isEqualTo(Instant.parse("2026-01-20T12:12:38Z"));
		assertThat(LastModifiedParser.parse("Wed, 24 Jun 2026 08:58:50 CEST"))
				.isEqualTo(Instant.parse("2026-06-24T06:58:50Z"));
		assertThat(LastModifiedParser.parse("Fri, 04 Sep 2026 13:23:36 CEST"))
				.isEqualTo(Instant.parse("2026-09-04T11:23:36Z"));
	}

	@Test
	void acceptsRfc1123Too() {
		assertThat(LastModifiedParser.parse("Wed, 21 Oct 2015 07:28:00 GMT"))
				.isEqualTo(Instant.parse("2015-10-21T07:28:00Z"));
	}

	@Test
	void unknownFormatsYieldNull() {
		assertThat(LastModifiedParser.parse(null)).isNull();
		assertThat(LastModifiedParser.parse("  ")).isNull();
		assertThat(LastModifiedParser.parse("2026-01-20T13:12:38")).isNull();
		assertThat(LastModifiedParser.parse("garbage")).isNull();
	}

}
