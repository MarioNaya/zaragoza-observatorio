package es.zaragoza.observatory.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/** Formatos de fecha observados en los registros de la API municipal (S0.5, S1.1). */
class ZaragozaTimeTest {

	@Test
	void parsesSedeLocalDateTimesAsZaragozaTime() {
		assertThat(ZaragozaTime.parseInstant("2026-06-12T10:57:57")).isEqualTo(Instant.parse("2026-06-12T08:57:57Z"));
		assertThat(ZaragozaTime.parseInstant("2013-07-08T00:00:00")).isEqualTo(Instant.parse("2013-07-07T22:00:00Z"));
		assertThat(ZaragozaTime.parseInstant("2026-01-20T13:12:38")).isEqualTo(Instant.parse("2026-01-20T12:12:38Z"));
	}

	@Test
	void parsesOffsetsDatesCompactDatesAndSlashedDates() {
		assertThat(ZaragozaTime.parseInstant("2026-09-04T12:07:57Z")).isEqualTo(Instant.parse("2026-09-04T12:07:57Z"));
		assertThat(ZaragozaTime.parseInstant("2021-11-04T19:00:00+02:00")).isEqualTo(Instant.parse("2021-11-04T17:00:00Z"));
		assertThat(ZaragozaTime.parseInstant("2014-06-25")).isEqualTo(Instant.parse("2014-06-24T22:00:00Z"));
		assertThat(ZaragozaTime.parseInstant("20260831")).isEqualTo(Instant.parse("2026-08-30T22:00:00Z"));
		assertThat(ZaragozaTime.parseInstant("25/06/2014")).isEqualTo(Instant.parse("2014-06-24T22:00:00Z"));
	}

	@Test
	void rejectsWhatItDoesNotRecognise() {
		assertThat(ZaragozaTime.parseInstant(null)).isNull();
		assertThat(ZaragozaTime.parseInstant("  ")).isNull();
		assertThat(ZaragozaTime.parseInstant("acto-313727")).isNull();
		assertThat(ZaragozaTime.parseInstant("12345")).isNull();
	}

}
