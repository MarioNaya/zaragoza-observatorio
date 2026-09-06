package es.zaragoza.observatory.catalog.infrastructure.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import es.zaragoza.observatory.catalog.application.ObserveDatasets;
import es.zaragoza.observatory.catalog.infrastructure.CatalogProperties;

/**
 * Muestreo de distribuciones por lotes (S1.1 recomendación 5): en cada tick observa las fichas vencidas, en
 * serie y con pausa entre peticiones. Comparte el planificador de una sola hebra con {@code ingestion}
 * ({@code spring.task.scheduling.pool.size=1}), así que nunca solapa con una ingesta.
 */
@Component
@ConditionalOnProperty(prefix = "zaragoza.catalog.observation", name = "enabled", havingValue = "true",
		matchIfMissing = true)
class CatalogObservationScheduler {

	private static final Logger log = LoggerFactory.getLogger(CatalogObservationScheduler.class);

	private final ObserveDatasets observeDatasets;
	private final CatalogProperties properties;

	CatalogObservationScheduler(ObserveDatasets observeDatasets, CatalogProperties properties) {
		this.observeDatasets = observeDatasets;
		this.properties = properties;
	}

	@Scheduled(initialDelayString = "${zaragoza.catalog.observation.initial-delay:PT2M}",
			fixedDelayString = "${zaragoza.catalog.observation.tick:PT10M}")
	void tick() {
		try {
			observeDatasets.observeDue(properties.observation().batchSize());
		}
		catch (RuntimeException ex) {
			log.error("dataset observation batch failed", ex);
		}
	}

}
