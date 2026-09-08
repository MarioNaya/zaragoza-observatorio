package es.zaragoza.observatory.geo.infrastructure.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import es.zaragoza.observatory.geo.GeoSources;
import es.zaragoza.observatory.geo.application.UpdateDistrictProfiles;
import es.zaragoza.observatory.shared.DatasetIngested;

/**
 * SPEC.md §4.5 paso 6: tras cada ingesta de la capa base de juntas se lee el detalle de cada una para completar
 * el {@code idpadron} y el padrón (S2.1). Son 29 peticiones con pausa entre ellas, por eso van en el listener
 * asíncrono y no dentro del {@code handle} de la página.
 */
@Component
class GeoIngestedListener {

	private static final Logger log = LoggerFactory.getLogger(GeoIngestedListener.class);

	private final UpdateDistrictProfiles updateDistrictProfiles;

	GeoIngestedListener(UpdateDistrictProfiles updateDistrictProfiles) {
		this.updateDistrictProfiles = updateDistrictProfiles;
	}

	@ApplicationModuleListener
	void on(DatasetIngested event) {
		if (!GeoSources.DISTRICTS.equals(event.dataset())) {
			log.debug("ignoring DatasetIngested for {}", event.dataset());
			return;
		}
		var summary = updateDistrictProfiles.update();
		log.info("geo run {} ingested {} districts; profiles: {} read, {} with padronId, {} population records, "
				+ "{} failed", event.run(), event.records(), summary.districtsRead(), summary.withPadronId(),
				summary.populationRecords(), summary.failed());
	}

}
