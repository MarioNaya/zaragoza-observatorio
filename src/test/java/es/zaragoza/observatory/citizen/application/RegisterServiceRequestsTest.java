package es.zaragoza.observatory.citizen.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import es.zaragoza.observatory.geo.Assignment;
import es.zaragoza.observatory.citizen.domain.ServiceRequest;
import es.zaragoza.observatory.citizen.domain.ServiceRequestDraft;
import es.zaragoza.observatory.citizen.domain.ServiceRequestStatus;
import es.zaragoza.observatory.citizen.support.FakeGeo;
import es.zaragoza.observatory.citizen.support.InMemoryServiceRequests;
import es.zaragoza.observatory.geo.GeoPoint;

/**
 * Lo que ADR-011 obliga a hacer con cada registro, comprobado uno a uno: cuatro estados de asignación, el
 * declarado que nunca sustituye al resuelto, y el upsert idempotente.
 */
class RegisterServiceRequestsTest {

	static final Instant SEEN = Instant.parse("2026-09-08T10:00:00Z");
	static final GeoPoint IN_DELICIAS = new GeoPoint(-0.90, 41.65);
	static final GeoPoint IN_RABAL = new GeoPoint(-0.87, 41.66);
	static final GeoPoint OVERLAP = new GeoPoint(-0.99, 41.74);
	static final GeoPoint IN_MADRID = new GeoPoint(-3.70, 40.42);

	final InMemoryServiceRequests requests = new InMemoryServiceRequests();
	final FakeGeo geo = new FakeGeo()
			.district(5, "Junta Municipal Delicias", IN_DELICIAS, 100_000)
			.district(6, "Junta Municipal El Rabal", IN_RABAL, 80_000)
			.district(14, "Junta Vecinal Alfocea", null, 200)
			.district(18, "Junta Vecinal Juslibol", null, 1_800)
			.district(30, "Junta Municipal Sur", null, 60_000);
	final RegisterServiceRequests register = new RegisterServiceRequests(requests, geo);

	{
		geo.overlap(OVERLAP, 14, 18);
	}

	@Test
	void resolvesAPointToItsDistrict() {
		register.register(List.of(draft(1, IN_DELICIAS, "DELICIAS")), SEEN);

		ServiceRequest stored = requests.byId.get(1L);
		assertThat(stored.district().status()).isEqualTo(Assignment.RESOLVED);
		assertThat(stored.district().districtId()).isEqualTo(5);
		assertThat(stored.district().declaredName()).isEqualTo("DELICIAS");
		assertThat(stored.district().declaredDistrictId()).isEqualTo(5);
		assertThat(stored.district().agrees()).isTrue();
	}

	@Test
	void marksTheOverlapInsteadOfChoosingInSilence() {
		register.register(List.of(draft(2, OVERLAP, null)), SEEN);

		var district = requests.byId.get(2L).district();
		assertThat(district.status()).isEqualTo(Assignment.AMBIGUOUS);
		assertThat(district.districtId()).as("la de menor id, con la ambigüedad marcada").isEqualTo(14);
	}

	@Test
	void aPointOutsideTheCityIsNotADistrict() {
		register.register(List.of(draft(3, IN_MADRID, "DELICIAS")), SEEN);

		var district = requests.byId.get(3L).district();
		assertThat(district.status()).isEqualTo(Assignment.OUTSIDE);
		assertThat(district.districtId()).isNull();
		assertThat(district.declaredDistrictId()).as("lo declarado se guarda, pero no asigna").isEqualTo(5);
	}

	@Test
	void withoutAPointThereIsNoDistrictEvenIfTheSourceDeclaresOne() {
		// El corazón de ADR-011 §2: no se geocodifica por dirección ni se cree al declarado para rellenar.
		register.register(List.of(draft(4, null, "DISTRITO SUR")), SEEN);

		var district = requests.byId.get(4L).district();
		assertThat(district.status()).isEqualTo(Assignment.NO_POINT);
		assertThat(district.districtId()).isNull();
		assertThat(district.declaredName()).isEqualTo("DISTRITO SUR");
		assertThat(district.declaredDistrictId()).as("el sinónimo casa, pero solo como contraste").isEqualTo(30);
	}

	@Test
	void recordsTheDisagreementBetweenTheTwoWays() {
		register.register(List.of(draft(5, IN_DELICIAS, "EL RABAL")), SEEN);

		var district = requests.byId.get(5L).district();
		assertThat(district.districtId()).isEqualTo(5);
		assertThat(district.declaredDistrictId()).isEqualTo(6);
		assertThat(district.disagrees()).isTrue();
		assertThat(district.agrees()).isFalse();
	}

	@Test
	void aDeclaredNameThatDoesNotMatchIsKeptAsIs() {
		register.register(List.of(draft(6, null, "BARRIO QUE NO EXISTE")), SEEN);

		var district = requests.byId.get(6L).district();
		assertThat(district.declaredName()).isEqualTo("BARRIO QUE NO EXISTE");
		assertThat(district.declaredDistrictId()).isNull();
	}

	@Test
	void resolvesAWholePageWithASingleSpatialQuery() {
		// Los registros sin punto no consumen posición del lote: si el emparejamiento se descuadrase, las juntas
		// saldrían cambiadas de sitio, que es el error silencioso que este test existe para impedir.
		register.register(List.of(draft(10, null, null), draft(11, IN_DELICIAS, null), draft(12, null, null),
				draft(13, IN_RABAL, null), draft(14, IN_DELICIAS, null)), SEEN);

		assertThat(requests.byId.get(10L).district().districtId()).isNull();
		assertThat(requests.byId.get(11L).district().districtId()).isEqualTo(5);
		assertThat(requests.byId.get(12L).district().districtId()).isNull();
		assertThat(requests.byId.get(13L).district().districtId()).isEqualTo(6);
		assertThat(requests.byId.get(14L).district().districtId()).isEqualTo(5);
	}

	@Test
	void isIdempotentAndKeepsTheFirstSighting() {
		register.register(List.of(draft(20, IN_DELICIAS, "DELICIAS")), SEEN);
		Instant later = SEEN.plusSeconds(86_400);
		register.register(List.of(closed(20, IN_DELICIAS)), later);

		assertThat(requests.count()).isEqualTo(1);
		ServiceRequest stored = requests.byId.get(20L);
		assertThat(stored.status()).isEqualTo(ServiceRequestStatus.CLOSED);
		assertThat(stored.firstSeenAt()).isEqualTo(SEEN);
		assertThat(stored.lastSeenAt()).isEqualTo(later);
		assertThat(stored.closedAt()).isNotNull();
		assertThat(stored.responseTime()).isNotNull();
	}

	@Test
	void anEmptyPageDoesNothing() {
		assertThat(register.register(List.of(), SEEN)).isZero();
		assertThat(requests.count()).isZero();
	}

	private static ServiceRequestDraft draft(long id, GeoPoint point, String declared) {
		return new ServiceRequestDraft(id, ServiceRequestStatus.OPEN, "250", "Acera",
				Instant.parse("2026-09-01T08:00:00Z"), null, point, declared);
	}

	private static ServiceRequestDraft closed(long id, GeoPoint point) {
		return new ServiceRequestDraft(id, ServiceRequestStatus.CLOSED, "250", "Acera",
				Instant.parse("2026-09-01T08:00:00Z"), Instant.parse("2026-09-03T08:00:00Z"), point, "DELICIAS");
	}

}
