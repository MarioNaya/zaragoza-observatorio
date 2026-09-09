package es.zaragoza.observatory.urban.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import es.zaragoza.observatory.geo.Geo;
import es.zaragoza.observatory.urban.application.RegisterLicensedPremises;
import es.zaragoza.observatory.urban.domain.PremisesRepository;

/** Cableado del módulo {@code urban}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UrbanProperties.class)
class UrbanConfiguration {

	@Bean
	RegisterLicensedPremises registerLicensedPremises(PremisesRepository premises, Geo geo) {
		return new RegisterLicensedPremises(premises, geo);
	}

}
