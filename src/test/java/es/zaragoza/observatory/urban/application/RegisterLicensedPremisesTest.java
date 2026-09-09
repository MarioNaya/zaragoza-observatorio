package es.zaragoza.observatory.urban.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import es.zaragoza.observatory.geo.Assignment;
import es.zaragoza.observatory.geo.GeoPoint;
import es.zaragoza.observatory.urban.domain.AggregationAxis;
import es.zaragoza.observatory.urban.domain.AggregationBucket;
import es.zaragoza.observatory.urban.domain.IaeActivity;
import es.zaragoza.observatory.urban.domain.Licence;
import es.zaragoza.observatory.urban.domain.LicensedPremises;
import es.zaragoza.observatory.urban.domain.LicensedPremisesDraft;
import es.zaragoza.observatory.urban.domain.PremisesPage;
import es.zaragoza.observatory.urban.domain.PremisesQuery;
import es.zaragoza.observatory.urban.domain.PremisesRepository;
import es.zaragoza.observatory.urban.domain.YearCoverage;
import es.zaragoza.observatory.urban.support.FakeGeo;

/**
 * La resolución territorial de una página, con los cuatro estados de ADR-011. Lo que se comprueba aquí es la
 * regla que no puede romperse nunca: <b>sin punto no hay junta</b>, y el resto no se decide en silencio.
 */
class RegisterLicensedPremisesTest {

	static final GeoPoint IN_CENTRO = new GeoPoint(-0.88, 41.65);
	static final GeoPoint IN_JUSLIBOL = new GeoPoint(-0.92, 41.70);
	static final GeoPoint NOWHERE = new GeoPoint(-1.20, 41.90);

	final FakeGeo geo = new FakeGeo().district(4, "Centro", IN_CENTRO, 50_000).district(18, "Juslibol", null, 1_000)
			.overlap(IN_JUSLIBOL, 1, 18);

	final RecordingRepository repository = new RecordingRepository();

	final RegisterLicensedPremises register = new RegisterLicensedPremises(repository, geo);

	@Test
	void resolvesEachPointAndLeavesTheRestUnassigned() {
		Instant seenAt = Instant.parse("2026-09-09T10:00:00Z");

		int written = register.register(List.of(draft(1, IN_CENTRO), draft(2, IN_JUSLIBOL), draft(3, NOWHERE),
				draft(4, null)), seenAt);

		assertThat(written).isEqualTo(4);
		Map<Integer, LicensedPremises> byId = repository.saved.stream()
				.collect(java.util.stream.Collectors.toMap(LicensedPremises::sourceId, p -> p));

		assertThat(byId.get(1).assignment()).isEqualTo(Assignment.RESOLVED);
		assertThat(byId.get(1).districtId()).isEqualTo(4);
		// El solape de polígonos se marca, no se resuelve en silencio: se toma la de menor id (S2.1).
		assertThat(byId.get(2).assignment()).isEqualTo(Assignment.AMBIGUOUS);
		assertThat(byId.get(2).districtId()).isEqualTo(1);
		// Un punto fuera de las 29 juntas es un hecho publicable, no un error.
		assertThat(byId.get(3).assignment()).isEqualTo(Assignment.OUTSIDE);
		assertThat(byId.get(3).districtId()).isNull();
		// Y sin punto no hay junta (ADR-011 §2): no se geocodifica la dirección para rellenar el hueco.
		assertThat(byId.get(4).assignment()).isEqualTo(Assignment.NO_POINT);
		assertThat(byId.get(4).districtId()).isNull();

		assertThat(byId.values()).allSatisfy(premises -> {
			assertThat(premises.firstSeenAt()).isEqualTo(seenAt);
			assertThat(premises.lastSeenAt()).isEqualTo(seenAt);
		});
	}

	@Test
	void keepsTheLicencesOfEachPremises() {
		register.register(List.of(draft(1, IN_CENTRO)), Instant.parse("2026-09-09T10:00:00Z"));

		assertThat(repository.saved).singleElement()
				.satisfies(premises -> assertThat(premises.licences()).singleElement()
						.satisfies(licence -> assertThat(licence.year()).isEqualTo(2021)));
	}

	@Test
	void writesNothingForAnEmptyPage() {
		assertThat(register.register(List.of(), Instant.now())).isZero();
		assertThat(repository.saved).isEmpty();
	}

	private static LicensedPremisesDraft draft(int id, GeoPoint point) {
		return new LicensedPremisesDraft(id, new IaeActivity("16732", "OTROS CAFES Y BARES", 1, 67), 1, "869",
				"17980", null, Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2024-01-01T00:00:00Z"), null,
				point, List.of(new Licence(2021, 100306, 1, 46, "RECURSO", null, null, null, null, null)));
	}

	/** Puerto en memoria: solo hace falta recoger lo que se le pasa. */
	static final class RecordingRepository implements PremisesRepository {

		final List<LicensedPremises> saved = new ArrayList<>();

		@Override
		public int upsertAll(List<LicensedPremises> premises, Instant seenAt) {
			saved.addAll(premises);
			return premises.size();
		}

		@Override
		public Optional<Instant> latestUpdatedAt() {
			return Optional.empty();
		}

		@Override
		public Optional<Instant> earliestCreatedAt() {
			return Optional.empty();
		}

		@Override
		public long count() {
			return saved.size();
		}

		@Override
		public long countLicences() {
			return saved.stream().mapToLong(premises -> premises.licences().size()).sum();
		}

		@Override
		public long count(PremisesQuery filters) {
			return saved.size();
		}

		@Override
		public PremisesPage search(PremisesQuery query) {
			return new PremisesPage(saved, saved.size(), 0, query.size());
		}

		@Override
		public List<AggregationBucket> aggregate(AggregationAxis axis, PremisesQuery filters) {
			return List.of();
		}

		@Override
		public List<YearCoverage> pointCoverageByLicenceYear(PremisesQuery filters) {
			return List.of();
		}

		@Override
		public Map<Assignment, Long> assignmentCounts(PremisesQuery filters) {
			return Map.of();
		}

		@Override
		public Map<Integer, Long> statusCounts(PremisesQuery filters) {
			return Map.of();
		}

	}

}
