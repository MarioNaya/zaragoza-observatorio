package es.zaragoza.observatory.geo.infrastructure.zaragoza;

import java.time.Duration;
import java.util.Map;

import org.springframework.stereotype.Component;

import es.zaragoza.observatory.geo.GeoSources;
import es.zaragoza.observatory.geo.application.RegisterDistricts;
import es.zaragoza.observatory.geo.infrastructure.GeoProperties;
import es.zaragoza.observatory.ingestion.IngestionJob;
import es.zaragoza.observatory.ingestion.RawPage;
import es.zaragoza.observatory.ingestion.SourceDescriptor;
import es.zaragoza.observatory.ingestion.SourceDescriptor.Pagination;
import es.zaragoza.observatory.ingestion.SourceDescriptor.ResponseShape;

/**
 * Job de ingesta de la capa base territorial (S0.4, S2.1): las 29 juntas con su geometría en una sola petición.
 * {@code srsname=wgs84} es obligatorio —por defecto la API devuelve UTM30N (S0.5)— y {@code rows} basta con que
 * pase de 29; se pide 100 para que un alta en origen entre sin cambiar nada.
 * <p>
 * El {@code idpadron} y el padrón no están en este listado: los completa {@code UpdateDistrictProfiles} después,
 * junta a junta (ADR-011).
 */
@Component
class DistrictsIngestionJob implements IngestionJob {

	static final int ROWS = 100;

	private final GeoProperties properties;
	private final DistrictJsonTranslator translator;
	private final RegisterDistricts registerDistricts;

	DistrictsIngestionJob(GeoProperties properties, DistrictJsonTranslator translator,
			RegisterDistricts registerDistricts) {
		this.properties = properties;
		this.translator = translator;
		this.registerDistricts = registerDistricts;
	}

	@Override
	public SourceDescriptor source() {
		return new SourceDescriptor(GeoSources.DISTRICTS, properties.districtsUrl(), Map.of("srsname", "wgs84"),
				Pagination.none(ROWS), ResponseShape.ENVELOPE);
	}

	@Override
	public Duration interval() {
		return properties.interval();
	}

	@Override
	public void handle(RawPage page) {
		registerDistricts.register(translator.translate(page.body(), page.fetchedAt()), page.fetchedAt());
	}

}
