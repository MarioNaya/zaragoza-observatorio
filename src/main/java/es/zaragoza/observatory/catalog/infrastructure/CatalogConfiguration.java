package es.zaragoza.observatory.catalog.infrastructure;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.client.RestClient;

import es.zaragoza.observatory.catalog.application.ObserveDatasets;
import es.zaragoza.observatory.catalog.application.RecordObservation;
import es.zaragoza.observatory.catalog.application.RegisterDatasets;
import es.zaragoza.observatory.catalog.application.TakeFreshnessSnapshots;
import es.zaragoza.observatory.catalog.domain.DatasetRepository;
import es.zaragoza.observatory.catalog.domain.DistributionObserver;
import es.zaragoza.observatory.catalog.domain.FreshnessPolicy;
import es.zaragoza.observatory.catalog.domain.FreshnessSnapshotRepository;
import es.zaragoza.observatory.catalog.infrastructure.zaragoza.DistributionHttpObserver;
import tools.jackson.databind.json.JsonMapper;

/** Cableado del módulo {@code catalog}. {@code @EnableAsync} da soporte al listener asíncrono de Modulith. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CatalogProperties.class)
@EnableAsync
class CatalogConfiguration {

	@Bean
	FreshnessPolicy freshnessPolicy(CatalogProperties properties) {
		var f = properties.freshness();
		return new FreshnessPolicy(f.onTimeMax(), f.slightDelayMax(), f.delayedMax());
	}

	@Bean
	RegisterDatasets registerDatasets(DatasetRepository datasets) {
		return new RegisterDatasets(datasets);
	}

	@Bean
	TakeFreshnessSnapshots takeFreshnessSnapshots(DatasetRepository datasets, FreshnessSnapshotRepository snapshots,
			FreshnessPolicy policy, Clock clock) {
		return new TakeFreshnessSnapshots(datasets, snapshots, policy, clock);
	}

	/**
	 * Observación de distribuciones con el cliente HTTP común de la aplicación ({@code HttpClientConfiguration}:
	 * timeouts, no seguir redirecciones, {@code User-Agent}), sin los reintentos ni el circuit breaker de
	 * {@code ingestion}: una observación fallida se repite al día siguiente (S1.1).
	 */
	@Bean
	DistributionObserver distributionObserver(RestClient zaragozaRestClient, JsonMapper jsonMapper, Clock clock,
			CatalogProperties properties) {
		var sampling = properties.observation();
		return new DistributionHttpObserver(zaragozaRestClient, jsonMapper, clock, sampling.requestDelay(),
				sampling.maxFileDistributions());
	}

	@Bean
	RecordObservation recordObservation(DatasetRepository datasets, FreshnessSnapshotRepository snapshots,
			FreshnessPolicy policy, Clock clock) {
		return new RecordObservation(datasets, snapshots, policy, clock);
	}

	@Bean
	ObserveDatasets observeDatasets(DatasetRepository datasets, DistributionObserver observer,
			RecordObservation recordObservation, Clock clock, CatalogProperties properties) {
		return new ObserveDatasets(datasets, observer, recordObservation, clock, properties.observation().interval());
	}

}
