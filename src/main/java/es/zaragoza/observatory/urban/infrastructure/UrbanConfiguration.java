package es.zaragoza.observatory.urban.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import es.zaragoza.observatory.geo.Geo;
import es.zaragoza.observatory.ingestion.Ingestion;
import es.zaragoza.observatory.urban.application.RegisterLicensedPremises;
import es.zaragoza.observatory.urban.application.UrbanService;
import es.zaragoza.observatory.urban.domain.PremisesRepository;

/** Cableado del módulo {@code urban}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UrbanProperties.class)
class UrbanConfiguration {

	@Bean
	RegisterLicensedPremises registerLicensedPremises(PremisesRepository premises, Geo geo) {
		return new RegisterLicensedPremises(premises, geo);
	}

	/** Superficie pública del módulo para el cruce territorial (ADR-019 §9). */
	@Bean
	UrbanService urbanService(PremisesRepository premises, Ingestion ingestion) {
		return new UrbanService(premises, ingestion);
	}

}
