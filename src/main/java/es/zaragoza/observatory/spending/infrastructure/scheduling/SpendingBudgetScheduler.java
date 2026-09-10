package es.zaragoza.observatory.spending.infrastructure.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import es.zaragoza.observatory.spending.application.ReadBudgetSnapshots;
import es.zaragoza.observatory.spending.infrastructure.SpendingProperties;

/**
 * Lectura de las instantáneas del presupuesto por lotes (S3.2 §11), el mismo patrón que
 * {@link SpendingReleaseScheduler} y que el muestreo observado de {@code catalog} (ADR-005).
 * <p>
 * Comparte el planificador de una sola hebra con {@code ingestion} y con el detalle de la contratación
 * ({@code spring.task.scheduling.pool.size=1}), así que nunca solapa con ellos.
 */
@Component
@ConditionalOnProperty(prefix = "zaragoza.spending.budget", name = "enabled", havingValue = "true",
		matchIfMissing = true)
class SpendingBudgetScheduler {

	private static final Logger log = LoggerFactory.getLogger(SpendingBudgetScheduler.class);

	private final ReadBudgetSnapshots readSnapshots;
	private final SpendingProperties properties;

	SpendingBudgetScheduler(ReadBudgetSnapshots readSnapshots, SpendingProperties properties) {
		this.readSnapshots = readSnapshots;
		this.properties = properties;
	}

	@Scheduled(initialDelayString = "${zaragoza.spending.budget.initial-delay:PT5M}",
			fixedDelayString = "${zaragoza.spending.budget.tick:PT10M}")
	void tick() {
		try {
			readSnapshots.readDue(properties.budget().batchSize());
		}
		catch (RuntimeException ex) {
			log.error("spending budget batch failed", ex);
		}
	}

}
