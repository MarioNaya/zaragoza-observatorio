package es.zaragoza.observatory.catalog.infrastructure;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Parámetros del módulo {@code catalog}. Endpoint y campos verificados en S0.1 (y {@code fl} con {@code formato}
 * comprobado con una petición real el 2026-09-06); umbrales de frescura de SPEC.md §4.6/§9; muestreo de
 * distribuciones según S1.1 (recomendaciones 4 y 5).
 */
@ConfigurationProperties(prefix = "zaragoza.catalog")
public record CatalogProperties(
		@DefaultValue("https://www.zaragoza.es/web/espacio-de-datos/servicio/catalogo.json") URI catalogUrl,
		@DefaultValue({ "id", "title", "description_basic", "issued", "modified", "lastUpdated", "accrualPeriodicity",
				"status", "geo", "abierto", "explorable", "formato" }) List<String> fields,
		@DefaultValue("PT6H") Duration interval, @DefaultValue Freshness freshness,
		@DefaultValue Sampling observation, @DefaultValue ApiInventory apiInventory,
		@DefaultValue Federation federation) {

	/**
	 * Federación en datos.gob.es (S1.3): listado del publicador municipal, páginas de hasta 200.
	 *
	 * @param url {@code apidata/catalog/dataset/publisher/L01502973.json}
	 * @param pageSize {@code _pageSize} (tope efectivo 200)
	 * @param interval intervalo mínimo entre ingestas
	 */
	public record Federation(
			@DefaultValue("https://datos.gob.es/apidata/catalog/dataset/publisher/L01502973.json") URI url,
			@DefaultValue("200") int pageSize, @DefaultValue("P1D") Duration interval) {
	}

	public record Freshness(@DefaultValue("1.0") double onTimeMax, @DefaultValue("2.0") double slightDelayMax,
			@DefaultValue("5.0") double delayedMax) {
	}

	/**
	 * Inventario de endpoints: el Swagger 2.0 de la API (S0.1, S1.2), documento único sin paginación.
	 *
	 * @param url {@code sede/servicio/catalogo/api.json}
	 * @param interval intervalo mínimo entre ingestas (el documento cambia rara vez)
	 */
	public record ApiInventory(@DefaultValue("https://www.zaragoza.es/sede/servicio/catalogo/api.json") URI url,
			@DefaultValue("P1D") Duration interval) {
	}

	/**
	 * Muestreo de distribuciones (eje observado).
	 *
	 * @param enabled permite desactivar el planificador (tests)
	 * @param interval cada cuánto se vuelve a observar una ficha
	 * @param tick cada cuánto se procesa un lote de fichas vencidas
	 * @param initialDelay espera tras el arranque antes del primer lote
	 * @param batchSize fichas por lote (436 fichas / 60 ≈ 8 lotes ≈ 80 min para todo el catálogo)
	 * @param requestDelay pausa entre peticiones (S0.5: 1–2 req/s por cortesía)
	 * @param maxFileDistributions tope de {@code HEAD} por ficha cuando tiene muchos ficheros
	 */
	public record Sampling(@DefaultValue("true") boolean enabled, @DefaultValue("P1D") Duration interval,
			@DefaultValue("PT10M") Duration tick, @DefaultValue("PT2M") Duration initialDelay,
			@DefaultValue("60") int batchSize, @DefaultValue("PT0.5S") Duration requestDelay,
			@DefaultValue("10") int maxFileDistributions) {
	}

}
