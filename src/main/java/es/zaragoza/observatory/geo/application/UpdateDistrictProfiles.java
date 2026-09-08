package es.zaragoza.observatory.geo.application;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import es.zaragoza.observatory.geo.domain.District;
import es.zaragoza.observatory.geo.domain.DistrictProfile;
import es.zaragoza.observatory.geo.domain.DistrictProfileReader;
import es.zaragoza.observatory.geo.domain.DistrictRepository;
import es.zaragoza.observatory.geo.domain.PopulationRecord;
import es.zaragoza.observatory.geo.domain.PopulationRepository;

/**
 * Completa cada junta con lo que solo está en su detalle (S2.1): el {@code idpadron} —la numeración que usan los
 * datasets de población— y la serie de padrón. Son 29 peticiones, una por junta, después de cada ingesta de la
 * capa base.
 * <p>
 * Una junta que falle no aborta el resto: se registra y se reintenta al día siguiente. Todo es upsert, así que
 * repetirlo no duplica nada (regla 5).
 */
public class UpdateDistrictProfiles {

	private static final Logger log = LoggerFactory.getLogger(UpdateDistrictProfiles.class);

	private final DistrictRepository districts;
	private final PopulationRepository population;
	private final DistrictProfileReader reader;

	public UpdateDistrictProfiles(DistrictRepository districts, PopulationRepository population,
			DistrictProfileReader reader) {
		this.districts = Objects.requireNonNull(districts);
		this.population = Objects.requireNonNull(population);
		this.reader = Objects.requireNonNull(reader);
	}

	public Summary update() {
		int read = 0, withPadronId = 0, records = 0, failed = 0;
		for (District district : districts.findAll()) {
			DistrictProfile profile = reader.read(district.id()).orElse(null);
			if (profile == null) {
				failed++;
				continue;
			}
			read++;
			if (profile.padronId() != null) {
				withPadronId++;
			}
			records += apply(profile);
		}
		var summary = new Summary(read, withPadronId, records, failed);
		log.info("geo profiles: {} districts read, {} with padronId, {} population records, {} failed", read,
				withPadronId, records, failed);
		return summary;
	}

	/**
	 * Escribe lo leído de una junta. Sin transacción propia a propósito: cada escritura del adaptador va en la
	 * suya, así que una junta que falle no deshace las anteriores (y no se mantiene abierta una transacción
	 * mientras se hacen 29 peticiones HTTP).
	 */
	int apply(DistrictProfile profile) {
		if (profile.padronId() != null) {
			districts.updatePadronId(profile.districtId(), profile.padronId());
		}
		for (PopulationRecord record : profile.population()) {
			population.upsert(record);
		}
		return profile.population().size();
	}

	/**
	 * @param districtsRead juntas cuyo detalle respondió
	 * @param withPadronId de ellas, cuántas publican {@code idpadron}
	 * @param populationRecords filas de padrón escritas
	 * @param failed juntas cuyo detalle no se pudo leer
	 */
	public record Summary(int districtsRead, int withPadronId, int populationRecords, int failed) {
	}

}
