package es.zaragoza.observatory.ingestion.domain;

import es.zaragoza.observatory.shared.DatasetIngested;

/** Puerto de publicación de eventos de integración (SPEC.md §4.5 paso 5). */
public interface IngestionEventPublisher {

	void publish(DatasetIngested event);

}
