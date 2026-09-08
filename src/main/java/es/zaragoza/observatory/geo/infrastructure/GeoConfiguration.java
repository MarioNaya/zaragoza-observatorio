package es.zaragoza.observatory.geo.infrastructure;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import es.zaragoza.observatory.geo.application.RegisterDistricts;
import es.zaragoza.observatory.geo.application.UpdateDistrictProfiles;
import es.zaragoza.observatory.geo.domain.DistrictProfileReader;
import es.zaragoza.observatory.geo.domain.DistrictRepository;
import es.zaragoza.observatory.geo.domain.PopulationRepository;
import es.zaragoza.observatory.geo.infrastructure.zaragoza.DistrictProfileHttpReader;
import tools.jackson.databind.json.JsonMapper;

/** Cableado del módulo {@code geo}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GeoProperties.class)
class GeoConfiguration {

	@Bean
	RegisterDistricts registerDistricts(DistrictRepository districts) {
		return new RegisterDistricts(districts);
	}

	/**
	 * Lector del detalle de cada junta con el cliente HTTP común de la aplicación (regla 23): sin reintentos ni
	 * circuit breaker, como el muestreo observado de {@code catalog} (ADR-005).
	 */
	@Bean
	DistrictProfileReader districtProfileReader(RestClient zaragozaRestClient, JsonMapper jsonMapper,
			GeoProperties properties, Clock clock) {
		return new DistrictProfileHttpReader(zaragozaRestClient, jsonMapper, properties, clock);
	}

	@Bean
	UpdateDistrictProfiles updateDistrictProfiles(DistrictRepository districts, PopulationRepository population,
			DistrictProfileReader reader) {
		return new UpdateDistrictProfiles(districts, population, reader);
	}

}
