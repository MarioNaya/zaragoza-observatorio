package es.zaragoza.observatory.citizen.infrastructure.zaragoza;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Component;

import es.zaragoza.observatory.citizen.CitizenSources;
import es.zaragoza.observatory.citizen.application.RegisterServiceRequests;
import es.zaragoza.observatory.citizen.domain.ServiceRequestRepository;
import es.zaragoza.observatory.citizen.infrastructure.CitizenProperties;
import es.zaragoza.observatory.ingestion.Ingestion;
import es.zaragoza.observatory.shared.DatasetRef;

/**
 * Altas: recorre el listado por {@code requested_datetime}, que es el único eje por el que el barrido completo
 * sale exacto (S2.2 §10: 89.432 filas y 89.432 identificadores distintos, sin repeticiones). Por eso
 * <b>la carga del histórico es cosa suya</b>.
 * <p>
 * Mientras no haya completado un barrido con éxito no aplica marca de agua, aunque la tabla ya tenga datos: los
 * habrá puesto el job de cierres, y lo que ese haya visto no dice nada de lo que falta por aquí. Después de la
 * primera vez, unas decenas de registros al día (S2.2: ~48 altas diarias).
 */
@Component
class NewRequestsIngestionJob extends ServiceRequestsIngestionJob {

	NewRequestsIngestionJob(CitizenProperties properties, ServiceRequestRepository requests,
			ServiceRequestJsonTranslator translator, RegisterServiceRequests register, Ingestion ingestion) {
		super(properties, requests, translator, register, ingestion);
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
		return hasCompletedASweep() ? requests.latestRequestedAt() : Optional.empty();
	}

}
