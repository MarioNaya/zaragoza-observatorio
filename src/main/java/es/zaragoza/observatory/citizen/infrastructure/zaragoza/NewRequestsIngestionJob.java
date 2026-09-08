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
 * Altas: recorre el listado por {@code requested_datetime} desde la última alta guardada. En la primera
 * ejecución no hay marca y hace la carga completa del histórico (89.432 registros desde 2013-01-08, ~9 min con
 * el retardo de cortesía); después, unas decenas de registros al día (S2.2: ~48 altas diarias).
 */
@Component
class NewRequestsIngestionJob extends ServiceRequestsIngestionJob {

	NewRequestsIngestionJob(CitizenProperties properties, ServiceRequestRepository requests,
			ServiceRequestJsonTranslator translator, RegisterServiceRequests register) {
		super(properties, requests, translator, register);
	}

	@Override
	String dateField() {
		return "requested_datetime";
	}

	@Override
	DatasetRef dataset() {
		return CitizenSources.REQUESTS;
	}

	@Override
	Optional<Instant> watermark() {
		return requests.latestRequestedAt();
	}

}
