package es.zaragoza.observatory.citizen.infrastructure.zaragoza;

import java.time.Clock;
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
 * Cierres y actualizaciones: recorre el mismo listado por {@code updated_datetime}. Es el que captura el paso de
 * abierta a cerrada, incluido el de expedientes antiguos —S2.2 observó una queja de 2015 cerrada en septiembre
 * de 2026—, que la ingesta de altas no volvería a mirar nunca.
 * <p>
 * <b>Nunca barre el listado entero</b>, y esto no es una optimización sino una condición de corrección: por este
 * eje muchas quejas comparten {@code updated_datetime} (se cierran por lotes) y entre filas empatadas el orden
 * no es estable entre páginas, así que un barrido completo por offset repite unas y <b>se salta 8.804</b> de
 * 89.432 (S2.2 §10). Por eso su marca de agua no es opcional: si la tabla todavía no tiene ninguna fecha de
 * actualización, arranca <b>desde ahora</b> y deja el histórico para el job de altas, que sí lo recorre entero y
 * sin pérdidas. Las ventanas incrementales caben en una página y no sufren el problema.
 */
@Component
class ClosedRequestsIngestionJob extends ServiceRequestsIngestionJob {

	private final Clock clock;

	ClosedRequestsIngestionJob(CitizenProperties properties, ServiceRequestRepository requests,
			ServiceRequestJsonTranslator translator, RegisterServiceRequests register, Ingestion ingestion,
			Clock clock) {
		super(properties, requests, translator, register, ingestion);
		this.clock = clock;
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
		return Optional.of(requests.latestUpdatedAt().orElseGet(clock::instant));
	}

}
