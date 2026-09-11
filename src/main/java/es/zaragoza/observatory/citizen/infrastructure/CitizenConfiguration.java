package es.zaragoza.observatory.citizen.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import es.zaragoza.observatory.citizen.application.CitizenService;
import es.zaragoza.observatory.citizen.application.RegisterServiceRequests;
import es.zaragoza.observatory.citizen.domain.ServiceRequestRepository;
import es.zaragoza.observatory.geo.Geo;
import es.zaragoza.observatory.ingestion.Ingestion;

/** Cableado del módulo {@code citizen}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CitizenProperties.class)
class CitizenConfiguration {

	@Bean
	RegisterServiceRequests registerServiceRequests(ServiceRequestRepository requests, Geo geo) {
		return new RegisterServiceRequests(requests, geo);
	}

	/** Superficie pública del módulo para el cruce territorial (ADR-019 §9). */
	@Bean
	CitizenService citizenService(ServiceRequestRepository requests, Ingestion ingestion) {
		return new CitizenService(requests, ingestion);
	}

}
