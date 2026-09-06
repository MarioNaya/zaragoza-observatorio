package es.zaragoza.observatory.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

class FreshnessPolicyTest {

	static final LocalDate TODAY = LocalDate.of(2026, 9, 6);

	@Test
	void categorizesByRatioAgainstDefaultThresholds() {
		var policy = FreshnessPolicy.DEFAULT;
		// P1Y modificado hace 100 días -> 0,27
		var onTime = policy.evaluate(dataset("P1Y", TODAY.minusDays(100)), TODAY);
		assertThat(onTime.category()).isEqualTo(DeclaredFreshness.ON_TIME);
		assertThat(onTime.ratio()).isCloseTo(100 / 365.0, within(1e-9));
		assertThat(onTime.ageDays()).isEqualTo(100);
		assertThat(onTime.periodicityDays()).isEqualTo(365);
		// P1M modificado hace 45 días -> 1,5
		assertThat(policy.evaluate(dataset("P1M", TODAY.minusDays(45)), TODAY).category())
				.isEqualTo(DeclaredFreshness.SLIGHT_DELAY);
		// P1M hace 120 días -> 4
		assertThat(policy.evaluate(dataset("P1M", TODAY.minusDays(120)), TODAY).category())
				.isEqualTo(DeclaredFreshness.DELAYED);
		// «Inspección de puentes»: P1M con modified en 2008 (S0.1) -> muy por encima de 5
		assertThat(policy.evaluate(dataset("P1M", LocalDate.of(2008, 1, 1)), TODAY).category())
				.isEqualTo(DeclaredFreshness.NOT_UPDATED);
		// exactamente en el umbral cuenta como dentro
		assertThat(policy.categorize(1.0)).isEqualTo(DeclaredFreshness.ON_TIME);
		assertThat(policy.categorize(5.0)).isEqualTo(DeclaredFreshness.DELAYED);
	}

	@Test
	void notEvaluableWithoutPeriodOrWithoutModified() {
		var policy = FreshnessPolicy.DEFAULT;
		for (String periodicity : List.of("NEVER", "IRREG", "P0DT1S")) {
			var evaluation = policy.evaluate(dataset(periodicity, TODAY.minusDays(10)), TODAY);
			assertThat(evaluation.category()).isEqualTo(DeclaredFreshness.NOT_EVALUABLE);
			assertThat(evaluation.ratio()).isNull();
			assertThat(evaluation.ageDays()).isEqualTo(10);
		}
		var noPeriodicity = policy.evaluate(dataset(null, TODAY.minusDays(10)), TODAY);
		assertThat(noPeriodicity.category()).isEqualTo(DeclaredFreshness.NOT_EVALUABLE);
		var noModified = policy.evaluate(dataset("P1Y", null), TODAY);
		assertThat(noModified.category()).isEqualTo(DeclaredFreshness.NOT_EVALUABLE);
		assertThat(noModified.ageDays()).isNull();
		assertThat(noModified.periodicityDays()).isEqualTo(365);
	}

	@Test
	void futureModifiedCountsAsZeroAge() {
		var evaluation = FreshnessPolicy.DEFAULT.evaluate(dataset("P1Y", TODAY.plusDays(30)), TODAY);
		assertThat(evaluation.ratio()).isZero();
		assertThat(evaluation.category()).isEqualTo(DeclaredFreshness.ON_TIME);
	}

	@Test
	void thresholdsMustBeIncreasing() {
		assertThatIllegalArgumentException().isThrownBy(() -> new FreshnessPolicy(2, 1, 5));
		assertThatIllegalArgumentException().isThrownBy(() -> new FreshnessPolicy(0, 1, 5));
		assertThat(new FreshnessPolicy(0.5, 1.5, 3).categorize(1.0)).isEqualTo(DeclaredFreshness.SLIGHT_DELAY);
	}

	static Dataset dataset(String periodicity, LocalDate modified) {
		Instant seen = Instant.parse("2026-09-06T10:00:00Z");
		return new Dataset(1, "t", null, null, modified == null ? null : modified.atStartOfDay(),
				LocalDateTime.of(2026, 1, 20, 13, 12, 38), periodicity, Periodicity.days(periodicity), "Finalizado",
				true, true, false, null, List.of(), seen, seen);
	}

}
