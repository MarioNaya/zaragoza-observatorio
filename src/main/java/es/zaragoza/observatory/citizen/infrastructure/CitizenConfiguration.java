package es.zaragoza.observatory.citizen.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import es.zaragoza.observatory.citizen.application.RegisterServiceRequests;
import es.zaragoza.observatory.citizen.domain.ServiceRequestRepository;
import es.zaragoza.observatory.geo.Geo;

/** Cableado del módulo {@code citizen}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CitizenProperties.class)
class CitizenConfiguration {

	@Bean
	RegisterServiceRequests registerServiceRequests(ServiceRequestRepository requests, Geo geo) {
		return new RegisterServiceRequests(requests, geo);
	}

}
