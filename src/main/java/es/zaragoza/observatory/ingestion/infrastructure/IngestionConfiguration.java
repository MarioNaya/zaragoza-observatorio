package es.zaragoza.observatory.ingestion.infrastructure;

import java.time.Clock;
import java.util.concurrent.Semaphore;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

import es.zaragoza.observatory.ingestion.Ingestion;
import es.zaragoza.observatory.ingestion.application.CompleteIngestionRun;
import es.zaragoza.observatory.ingestion.application.IngestionService;
import es.zaragoza.observatory.ingestion.application.PurgeRawPayloads;
import es.zaragoza.observatory.ingestion.application.RunIngestion;
import es.zaragoza.observatory.ingestion.domain.IngestionEventPublisher;
import es.zaragoza.observatory.ingestion.domain.IngestionRunRepository;
import es.zaragoza.observatory.ingestion.domain.RawPayloadStore;
import es.zaragoza.observatory.ingestion.domain.SourceGateway;
import es.zaragoza.observatory.ingestion.infrastructure.zaragoza.ZaragozaHttpClient;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import tools.jackson.databind.json.JsonMapper;

/** Cableado del módulo: casos de uso, cliente HTTP con su resiliencia (ADR-004) y planificación. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IngestionProperties.class)
@EnableScheduling
class IngestionConfiguration {

	@Bean
	Retry zaragozaRetry(IngestionProperties properties) {
		var retry = properties.retry();
		return Retry.of("zaragoza", RetryConfig.custom()
				.maxAttempts(retry.maxAttempts())
				.intervalFunction(IntervalFunction.ofExponentialBackoff(retry.initialBackoff(), retry.multiplier()))
				.retryOnException(ZaragozaHttpClient::isRetryable)
				.build());
	}

	@Bean
	CircuitBreakerRegistry zaragozaCircuitBreakers(IngestionProperties properties) {
		var cb = properties.circuitBreaker();
		return CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
				.slidingWindowSize(cb.slidingWindowSize())
				.minimumNumberOfCalls(cb.minimumCalls())
				.failureRateThreshold(cb.failureRateThreshold())
				.waitDurationInOpenState(cb.waitInOpenState())
				.recordException(ZaragozaHttpClient::isRetryable)
				.build());
	}

	@Bean
	SourceGateway sourceGateway(RestClient zaragozaRestClient, JsonMapper jsonMapper, Retry zaragozaRetry,
			CircuitBreakerRegistry zaragozaCircuitBreakers, IngestionProperties properties, Clock clock) {
		return new ZaragozaHttpClient(zaragozaRestClient, jsonMapper, zaragozaRetry, zaragozaCircuitBreakers,
				new Semaphore(properties.http().maxConcurrentRequests()), clock);
	}

	@Bean
	CompleteIngestionRun completeIngestionRun(IngestionRunRepository runs, IngestionEventPublisher events,
			Clock clock) {
		return new CompleteIngestionRun(runs, events, clock);
	}

	@Bean
	RunIngestion runIngestion(SourceGateway gateway, IngestionRunRepository runs, RawPayloadStore payloads,
			CompleteIngestionRun completion, Clock clock, IngestionProperties properties) {
		return new RunIngestion(gateway, runs, payloads, completion, clock, properties.pageDelay(),
				properties.maxPages());
	}

	@Bean
	Ingestion ingestion(RunIngestion runIngestion, IngestionRunRepository runs) {
		return new IngestionService(runIngestion, runs);
	}

	@Bean
	PurgeRawPayloads purgeRawPayloads(RawPayloadStore payloads, Clock clock, IngestionProperties properties) {
		return new PurgeRawPayloads(payloads, clock, properties.rawRetention());
	}

}
