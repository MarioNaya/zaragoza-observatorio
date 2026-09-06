package es.zaragoza.observatory.catalog.infrastructure;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Parámetros del módulo {@code catalog}. Endpoint y campos verificados en S0.1 (y {@code fl} con {@code formato}
 * comprobado con una petición real el 2026-09-06); umbrales de frescura de SPEC.md §4.6/§9.
 */
@ConfigurationProperties(prefix = "zaragoza.catalog")
public record CatalogProperties(
		@DefaultValue("https://www.zaragoza.es/web/espacio-de-datos/servicio/catalogo.json") URI catalogUrl,
		@DefaultValue({ "id", "title", "description_basic", "issued", "modified", "lastUpdated", "accrualPeriodicity",
				"status", "geo", "abierto", "explorable", "formato" }) List<String> fields,
		@DefaultValue("PT6H") Duration interval, @DefaultValue Freshness freshness) {

	public record Freshness(@DefaultValue("1.0") double onTimeMax, @DefaultValue("2.0") double slightDelayMax,
			@DefaultValue("5.0") double delayedMax) {
	}

}
