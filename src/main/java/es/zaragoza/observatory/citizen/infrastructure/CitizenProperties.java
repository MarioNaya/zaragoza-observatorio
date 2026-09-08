package es.zaragoza.observatory.citizen.infrastructure;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Parámetros del módulo {@code citizen}. La fuente y sus reglas están verificadas en S0.3 y S2.2.
 *
 * @param listUrl {@code sede/servicio/quejas-sugerencias/list.json}
 * @param fields proyección {@code fl}. <b>Es la garantía de ADR-012</b>: ocho campos y ninguno de texto libre.
 * Cambiarla para añadir {@code title}, {@code description} o {@code service_notice} rompe un test a propósito.
 * @param rows registros por página (tope 500 en la sede, S0.5)
 * @param interval intervalo mínimo entre ingestas de cada eje
 * @param watermarkMargin cuánto se retrocede la marca de agua al construir el filtro FIQL. Las fechas del origen
 * no llevan zona y el filtro se envía con {@code Z}: no está comprobado cómo interpreta el servidor esa {@code Z}
 * (S2.2), así que se retrocede lo suficiente para que la duda no pueda saltarse registros. Reingerir unos
 * cientos de registros ya vistos no cuesta nada: el upsert es idempotente.
 */
@ConfigurationProperties(prefix = "zaragoza.citizen")
public record CitizenProperties(
		@DefaultValue("https://www.zaragoza.es/sede/servicio/quejas-sugerencias/list.json") URI listUrl,
		@DefaultValue("service_request_id,status,service_code,service_name,requested_datetime,updated_datetime,geometry,district") String fields,
		@DefaultValue("500") int rows, @DefaultValue("P1D") Duration interval,
		@DefaultValue("PT6H") Duration watermarkMargin) {

	/** Campos que la proyección no puede contener nunca (ADR-012). */
	static final String[] FORBIDDEN_FIELDS = { "title", "description", "service_notice" };

	public CitizenProperties {
		for (String forbidden : FORBIDDEN_FIELDS) {
			for (String declared : fields.split(",")) {
				if (declared.strip().equalsIgnoreCase(forbidden)) {
					throw new IllegalArgumentException("zaragoza.citizen.fields must not request the free text of "
							+ "complaints (ADR-012): found '" + forbidden + "'");
				}
			}
		}
		if (rows <= 0) {
			throw new IllegalArgumentException("rows must be positive");
		}
		if (watermarkMargin.isNegative()) {
			throw new IllegalArgumentException("watermarkMargin must not be negative");
		}
	}

}
