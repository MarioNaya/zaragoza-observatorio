package es.zaragoza.observatory;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Reloj único de la aplicación: todos los módulos reciben {@link Clock} por inyección y los tests lo fijan. */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
