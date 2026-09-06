package es.zaragoza.observatory.catalog.infrastructure;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import es.zaragoza.observatory.catalog.application.RegisterDatasets;
import es.zaragoza.observatory.catalog.application.TakeFreshnessSnapshots;
import es.zaragoza.observatory.catalog.domain.DatasetRepository;
import es.zaragoza.observatory.catalog.domain.FreshnessPolicy;
import es.zaragoza.observatory.catalog.domain.FreshnessSnapshotRepository;

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

}
