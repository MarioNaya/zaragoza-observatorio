package es.zaragoza.observatory.geo.infrastructure;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Parámetros del módulo {@code geo}. Endpoints verificados en S0.4 y S2.1: el listado de juntas con geometría en
 * una petición y el detalle de cada junta para el padrón.
 *
 * @param districtsUrl {@code sede/servicio/distrito.json} (se pide con {@code srsname=wgs84})
 * @param districtDetailUrlTemplate plantilla del detalle; {@code {id}} se sustituye por {@code distrito.id}
 * @param interval intervalo mínimo entre ingestas de la capa base (las juntas cambian rara vez)
 * @param profileRequestDelay pausa entre las 29 peticiones de detalle, por cortesía con la API (S0.5)
 */
@ConfigurationProperties(prefix = "zaragoza.geo")
public record GeoProperties(@DefaultValue("https://www.zaragoza.es/sede/servicio/distrito.json") URI districtsUrl,
		@DefaultValue("https://www.zaragoza.es/sede/servicio/distrito/{id}.json") String districtDetailUrlTemplate,
		@DefaultValue("P1D") Duration interval, @DefaultValue("PT0.3S") Duration profileRequestDelay) {

	public GeoProperties {
		if (!districtDetailUrlTemplate.contains("{id}")) {
			throw new IllegalArgumentException("districtDetailUrlTemplate must contain {id}");
		}
	}

	public URI districtDetailUrl(int districtId) {
		return URI.create(districtDetailUrlTemplate.replace("{id}", Integer.toString(districtId)));
	}

}
