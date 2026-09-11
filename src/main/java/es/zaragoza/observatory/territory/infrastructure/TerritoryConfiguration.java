package es.zaragoza.observatory.territory.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import es.zaragoza.observatory.citizen.Citizen;
import es.zaragoza.observatory.geo.Geo;
import es.zaragoza.observatory.territory.application.ComposeCrossTab;
import es.zaragoza.observatory.urban.Urban;

/**
 * Cableado del módulo {@code territory}. Es el más corto del proyecto y tiene que serlo: sin repositorios, sin
 * propiedades, sin jobs de ingesta y sin planificador, porque el módulo no tiene estado (SPEC.md §4.8).
 * <p>
 * Sus tres dependencias son superficies públicas de otros módulos, nunca sus puertos internos (regla 3).
 */
@Configuration(proxyBeanMethods = false)
class TerritoryConfiguration {

	@Bean
	ComposeCrossTab composeCrossTab(Geo geo, Citizen citizen, Urban urban) {
		return new ComposeCrossTab(geo, citizen, urban);
	}

}
