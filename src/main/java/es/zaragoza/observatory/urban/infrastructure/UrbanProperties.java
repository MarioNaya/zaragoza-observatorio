package es.zaragoza.observatory.urban.infrastructure;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Parámetros del módulo {@code urban}. La fuente y sus reglas están verificadas en S2.4.
 * <p>
 * Aquí <b>no hay proyección {@code fl}</b>, y su ausencia es deliberada: la fuente vacía los objetos anidados
 * cuando se proyecta —una licencia proyectada llega sin año, sin expediente y sin tipo— y {@code removeproperties}
 * se acepta sin aplicarse (S2.4 §2). Se piden los registros completos y el texto libre se descarta al traducir,
 * que es donde está la garantía (ADR-016 §3).
 *
 * @param listUrl {@code sede/servicio/registro-licencia.json}
 * @param rows registros por página (tope 500 en la sede, S0.5). El barrido completo son 85 páginas y 40 MB
 * @param interval intervalo mínimo entre ingestas. El incremental diario son decenas de registros: 54 en ocho
 * días medidos en S2.4
 * @param watermarkMargin cuánto se retrocede la marca de agua al construir el filtro FIQL. Las fechas del origen
 * no llevan zona (S0.5) y el filtro se envía en hora local: se retrocede lo suficiente para que la duda no pueda
 * saltarse registros. Reingerir unos cuantos ya vistos no cuesta nada, porque el upsert es idempotente
 */
@ConfigurationProperties(prefix = "zaragoza.urban")
public record UrbanProperties(
		@DefaultValue("https://www.zaragoza.es/sede/servicio/registro-licencia.json") URI listUrl,
		@DefaultValue("500") int rows, @DefaultValue("P1D") Duration interval,
		@DefaultValue("PT6H") Duration watermarkMargin) {

	public UrbanProperties {
		if (rows <= 0) {
			throw new IllegalArgumentException("rows must be positive");
		}
		if (watermarkMargin.isNegative()) {
			throw new IllegalArgumentException("watermarkMargin must not be negative");
		}
	}

}
