package es.zaragoza.observatory.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Los 13 valores distintos de accrualPeriodicity observados en el catálogo (S0.1). */
class PeriodicityTest {

	@ParameterizedTest
	@CsvSource({ "P1Y,365", "P1M,30", "P1D,1", "P3M,90", "P1W,7", "P2Y,730", "P4Y,1460", "P5Y,1825", "P6M,180" })
	void evaluablePeriodsInDays(String value, int expectedDays) {
		assertThat(Periodicity.days(value)).isEqualTo(expectedDays);
	}

	@ParameterizedTest
	@ValueSource(strings = { "P0DT1S", "NEVER", "IRREG", "PT1H", "garbage", "P" })
	@NullAndEmptySource
	void nonEvaluableValuesYieldNull(String value) {
		assertThat(Periodicity.days(value)).isNull();
	}

}
