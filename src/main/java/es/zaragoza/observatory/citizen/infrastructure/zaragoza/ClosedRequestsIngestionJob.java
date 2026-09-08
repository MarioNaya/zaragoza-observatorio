package es.zaragoza.observatory.citizen.infrastructure.zaragoza;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Component;

import es.zaragoza.observatory.citizen.CitizenSources;
import es.zaragoza.observatory.citizen.application.RegisterServiceRequests;
import es.zaragoza.observatory.citizen.domain.ServiceRequestRepository;
import es.zaragoza.observatory.citizen.infrastructure.CitizenProperties;
import es.zaragoza.observatory.shared.DatasetRef;

/**
 * Cierres y actualizaciones: recorre el mismo listado por {@code updated_datetime}. Es el que captura el paso de
 * abierta a cerrada, incluido el de expedientes antiguos —S2.2 observó una queja de 2015 cerrada en septiembre
 * de 2026—, que la ingesta de altas no volvería a mirar nunca.
 * <p>
 * Sin marca de agua pide todo el listado por {@code updated_datetime asc}; los registros que no traen esa fecha
 * (las abiertas) no aparecen en ese recorrido, y no hace falta que aparezcan: ya los trajo el job de altas.
 */
@Component
class ClosedRequestsIngestionJob extends ServiceRequestsIngestionJob {

	ClosedRequestsIngestionJob(CitizenProperties properties, ServiceRequestRepository requests,
			ServiceRequestJsonTranslator translator, RegisterServiceRequests register) {
		super(properties, requests, translator, register);
	}

	@Override
	String dateField() {
		return "updated_datetime";
	}

	@Override
	DatasetRef dataset() {
		return CitizenSources.CLOSURES;
	}

	@Override
	Optional<Instant> watermark() {
		return requests.latestUpdatedAt();
	}

}
