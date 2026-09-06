package es.zaragoza.observatory.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/** Los dos formatos de {@code Last-Modified} de la infraestructura municipal (S0.5, S1.1). */
class HttpDatesTest {

	@Test
	void parsesRfc1123FromStaticContent() {
		assertThat(HttpDates.lastModified("Wed, 13 Feb 2013 09:30:24 GMT")).isEqualTo(Instant.parse("2013-02-13T09:30:24Z"));
		assertThat(HttpDates.lastModified("Mon, 15 Jun 2026 07:44:51 GMT")).isEqualTo(Instant.parse("2026-06-15T07:44:51Z"));
	}

	@Test
	void parsesNamedZonesFromTheSede() {
		assertThat(HttpDates.lastModified("Tue, 20 Jan 2026 13:12:38 CET")).isEqualTo(Instant.parse("2026-01-20T12:12:38Z"));
		assertThat(HttpDates.lastModified("Wed, 24 Jun 2026 08:58:50 CEST")).isEqualTo(Instant.parse("2026-06-24T06:58:50Z"));
	}

	@Test
	void returnsNullWhenMissingOrUnreadable() {
		assertThat(HttpDates.lastModified(null)).isNull();
		assertThat(HttpDates.lastModified("")).isNull();
		assertThat(HttpDates.lastModified("ayer")).isNull();
	}

}
