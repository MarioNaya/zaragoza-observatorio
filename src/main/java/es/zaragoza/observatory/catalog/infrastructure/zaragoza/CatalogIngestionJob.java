package es.zaragoza.observatory.catalog.infrastructure.zaragoza;

import java.time.Duration;
import java.util.Map;

import org.springframework.stereotype.Component;

import es.zaragoza.observatory.catalog.CatalogSources;
import es.zaragoza.observatory.catalog.application.RegisterDatasets;
import es.zaragoza.observatory.catalog.infrastructure.CatalogProperties;
import es.zaragoza.observatory.ingestion.IngestionJob;
import es.zaragoza.observatory.ingestion.RawPage;
import es.zaragoza.observatory.ingestion.SourceDescriptor;
import es.zaragoza.observatory.ingestion.SourceDescriptor.Pagination;
import es.zaragoza.observatory.ingestion.SourceDescriptor.ResponseShape;

/**
 * Job de ingesta del catálogo (S0.1 recomendación 1): {@code catalogo.json?rows=500&start=…&fl=…}, paginado por
 * {@code start} aunque hoy quepa en una página. Cada página se traduce y se registra en su propia transacción.
 */
@Component
class CatalogIngestionJob implements IngestionJob {

	private final CatalogProperties properties;
	private final CatalogJsonTranslator translator;
	private final RegisterDatasets registerDatasets;

	CatalogIngestionJob(CatalogProperties properties, CatalogJsonTranslator translator,
			RegisterDatasets registerDatasets) {
		this.properties = properties;
		this.translator = translator;
		this.registerDatasets = registerDatasets;
	}

	@Override
	public SourceDescriptor source() {
		return new SourceDescriptor(CatalogSources.CATALOG, properties.catalogUrl(),
				Map.of("fl", String.join(",", properties.fields())), Pagination.offset(SourceDescriptor.SEDE_MAX_ROWS),
				ResponseShape.ENVELOPE);
	}

	@Override
	public Duration interval() {
		return properties.interval();
	}

	@Override
	public void handle(RawPage page) {
		registerDatasets.register(translator.translate(page.body(), page.fetchedAt()), page.fetchedAt());
	}

}
