package es.zaragoza.observatory.spending.infrastructure.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import es.zaragoza.observatory.spending.application.ReadReleases;
import es.zaragoza.observatory.spending.infrastructure.SpendingProperties;

/**
 * Lectura del detalle por lotes (ADR-017 §5), el mismo patrón que el muestreo observado de {@code catalog}
 * (ADR-005) y por la misma razón: el histórico son 8.001 peticiones y no caben en el ciclo de una página.
 * <p>
 * Comparte el planificador de una sola hebra con {@code ingestion}
 * ({@code spring.task.scheduling.pool.size=1}), así que nunca solapa con una ingesta.
 */
@Component
@ConditionalOnProperty(prefix = "zaragoza.spending.releases", name = "enabled", havingValue = "true",
		matchIfMissing = true)
class SpendingReleaseScheduler {

	private static final Logger log = LoggerFactory.getLogger(SpendingReleaseScheduler.class);

	private final ReadReleases readReleases;
	private final SpendingProperties properties;

	SpendingReleaseScheduler(ReadReleases readReleases, SpendingProperties properties) {
		this.readReleases = readReleases;
		this.properties = properties;
	}

	@Scheduled(initialDelayString = "${zaragoza.spending.releases.initial-delay:PT3M}",
			fixedDelayString = "${zaragoza.spending.releases.tick:PT10M}")
	void tick() {
		try {
			readReleases.readDue(properties.releases().batchSize());
		}
		catch (RuntimeException ex) {
			log.error("spending release batch failed", ex);
		}
	}

}
