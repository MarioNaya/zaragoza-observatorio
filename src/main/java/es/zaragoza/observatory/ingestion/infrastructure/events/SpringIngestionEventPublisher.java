package es.zaragoza.observatory.ingestion.infrastructure.events;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import es.zaragoza.observatory.ingestion.domain.IngestionEventPublisher;
import es.zaragoza.observatory.shared.DatasetIngested;

/** Publica los eventos de integración en el contexto de Spring; Modulith los registra en {@code event_publication}. */
@Component
class SpringIngestionEventPublisher implements IngestionEventPublisher {

	private final ApplicationEventPublisher publisher;

	SpringIngestionEventPublisher(ApplicationEventPublisher publisher) {
		this.publisher = publisher;
	}

	@Override
	public void publish(DatasetIngested event) {
		publisher.publishEvent(event);
	}

}
