package es.zaragoza.observatory.catalog.infrastructure.zaragoza;

import java.time.Duration;

import org.springframework.stereotype.Component;

import es.zaragoza.observatory.catalog.CatalogSources;
import es.zaragoza.observatory.catalog.application.RegisterApiEndpoints;
import es.zaragoza.observatory.catalog.infrastructure.CatalogProperties;
import es.zaragoza.observatory.ingestion.IngestionJob;
import es.zaragoza.observatory.ingestion.RawPage;
import es.zaragoza.observatory.ingestion.SourceDescriptor;

/**
 * Job de ingesta del inventario de endpoints (S1.2): el Swagger 2.0 de la API como documento único, sin
 * parámetros de paginación (la fuente los ignora y no publica {@code Last-Modified} ni {@code ETag}). Una vez al
 * día es de sobra: el documento del 2026-09-06 es idéntico al del 2026-09-05.
 */
@Component
class ApiInventoryIngestionJob implements IngestionJob {

	private final CatalogProperties properties;
	private final SwaggerJsonTranslator translator;
	private final RegisterApiEndpoints registerApiEndpoints;

	ApiInventoryIngestionJob(CatalogProperties properties, SwaggerJsonTranslator translator,
			RegisterApiEndpoints registerApiEndpoints) {
		this.properties = properties;
		this.translator = translator;
		this.registerApiEndpoints = registerApiEndpoints;
	}

	@Override
	public SourceDescriptor source() {
		return SourceDescriptor.document(CatalogSources.API_INVENTORY, properties.apiInventory().url());
	}

	@Override
	public Duration interval() {
		return properties.apiInventory().interval();
	}

	@Override
	public void handle(RawPage page) {
		registerApiEndpoints.register(translator.translate(page.body(), page.fetchedAt()), page.fetchedAt());
	}

}
