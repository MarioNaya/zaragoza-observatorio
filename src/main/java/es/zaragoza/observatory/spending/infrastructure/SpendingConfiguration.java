package es.zaragoza.observatory.spending.infrastructure;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import es.zaragoza.observatory.spending.application.CheckDocumentedListing;
import es.zaragoza.observatory.spending.application.ReadReleases;
import es.zaragoza.observatory.spending.application.RegisterProcesses;
import es.zaragoza.observatory.spending.domain.ContractingProcessRepository;
import es.zaragoza.observatory.spending.domain.ReleaseSource;
import es.zaragoza.observatory.spending.domain.RetrySchedule;
import es.zaragoza.observatory.spending.infrastructure.zaragoza.OcdsHttpReleaseSource;
import es.zaragoza.observatory.spending.infrastructure.zaragoza.OcdsListJsonTranslator;
import es.zaragoza.observatory.spending.infrastructure.zaragoza.OcdsReleaseJsonTranslator;

/** Cableado del módulo {@code spending}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SpendingProperties.class)
class SpendingConfiguration {

	/**
	 * El {@code RestClient} es el bean único de la aplicación (regla 23): timeouts, redirecciones y
	 * {@code User-Agent} vienen de la configuración común, y ningún módulo construye otro.
	 */
	@Bean
	ReleaseSource ocdsReleaseSource(RestClient rest, OcdsReleaseJsonTranslator releaseTranslator,
			OcdsListJsonTranslator listTranslator, SpendingProperties properties) {
		return new OcdsHttpReleaseSource(rest, releaseTranslator, listTranslator, properties);
	}

	@Bean
	RetrySchedule ocdsRetrySchedule(SpendingProperties properties) {
		var releases = properties.releases();
		return new RetrySchedule(releases.missingInitialBackoff(), releases.missingMaxBackoff(),
				releases.activeRefresh(), releases.publishedRefresh());
	}

	@Bean
	RegisterProcesses registerProcesses(ContractingProcessRepository processes) {
		return new RegisterProcesses(processes);
	}

	@Bean
	CheckDocumentedListing checkDocumentedListing(ContractingProcessRepository processes, ReleaseSource source) {
		return new CheckDocumentedListing(processes, source);
	}

	@Bean
	ReadReleases readReleases(ContractingProcessRepository processes, ReleaseSource source, RetrySchedule schedule,
			Clock clock) {
		return new ReadReleases(processes, source, schedule, clock);
	}

}
