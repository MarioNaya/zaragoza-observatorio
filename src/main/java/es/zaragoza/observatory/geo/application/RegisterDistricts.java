package es.zaragoza.observatory.geo.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.transaction.annotation.Transactional;

import es.zaragoza.observatory.geo.domain.District;
import es.zaragoza.observatory.geo.domain.DistrictRepository;

/**
 * Registra la capa base de juntas con su geometría (upsert idempotente, regla 5). No borra lo que no venga: las
 * juntas son 29 y estables, y una respuesta incompleta no debe vaciar el territorio del observatorio.
 */
public class RegisterDistricts {

	private final DistrictRepository districts;

	public RegisterDistricts(DistrictRepository districts) {
		this.districts = Objects.requireNonNull(districts);
	}

	@Transactional
	public int register(List<District> page, Instant seenAt) {
		for (District district : page) {
			districts.upsert(district, seenAt);
		}
		return page.size();
	}

}
