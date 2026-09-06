package es.zaragoza.observatory.ingestion.infrastructure;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Parámetros del módulo {@code ingestion}. Los valores por defecto son las recomendaciones de S0.5 y ADR-004.
 * Los timeouts HTTP se configuran con las propiedades estándar {@code spring.http.clients.*} de Boot y el
 * {@code User-Agent} común con {@code zaragoza.http.user-agent}.
 */
@ConfigurationProperties(prefix = "zaragoza.ingestion")
public record IngestionProperties(@DefaultValue Http http, @DefaultValue Retry retry,
		@DefaultValue CircuitBreaker circuitBreaker, @DefaultValue("PT0.5S") Duration pageDelay,
		@DefaultValue("1000") int maxPages, @DefaultValue("P14D") Duration rawRetention,
		@DefaultValue Scheduler scheduler) {

	/**
	 * @param maxConcurrentRequests peticiones simultáneas al host municipal (S0.5: el servidor serializa; máximo 4)
	 */
	public record Http(@DefaultValue("4") int maxConcurrentRequests) {
	}

	/** Reintento con backoff exponencial solo ante fallos reintentables (5xx, timeouts, E/S, HTML). */
	public record Retry(@DefaultValue("3") int maxAttempts, @DefaultValue("PT1S") Duration initialBackoff,
			@DefaultValue("2.0") double multiplier) {
	}

	/** Circuit breaker por dataset: ventana por número de llamadas. */
	public record CircuitBreaker(@DefaultValue("6") int slidingWindowSize, @DefaultValue("3") int minimumCalls,
			@DefaultValue("50") float failureRateThreshold, @DefaultValue("PT2M") Duration waitInOpenState) {
	}

	/**
	 * @param enabled permite desactivar el planificador (tests)
	 * @param tick cada cuánto se comprueba qué jobs están vencidos
	 * @param initialDelay espera tras el arranque antes del primer tick
	 * @param rawPurgeCron expresión cron de la purga de {@code raw_payload}
	 */
	public record Scheduler(@DefaultValue("true") boolean enabled, @DefaultValue("PT10M") Duration tick,
			@DefaultValue("PT30S") Duration initialDelay, @DefaultValue("0 30 4 * * *") String rawPurgeCron) {
	}

}
