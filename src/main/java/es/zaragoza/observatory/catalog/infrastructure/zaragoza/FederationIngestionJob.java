package es.zaragoza.observatory.catalog.infrastructure.zaragoza;

import java.time.Duration;
import java.util.Map;

import org.springframework.stereotype.Component;

import es.zaragoza.observatory.catalog.CatalogSources;
import es.zaragoza.observatory.catalog.application.RegisterFederatedDatasets;
import es.zaragoza.observatory.catalog.infrastructure.CatalogProperties;
import es.zaragoza.observatory.ingestion.IngestionJob;
import es.zaragoza.observatory.ingestion.RawPage;
import es.zaragoza.observatory.ingestion.SourceDescriptor;
import es.zaragoza.observatory.ingestion.SourceDescriptor.Pagination;
import es.zaragoza.observatory.ingestion.SourceDescriptor.ResponseShape;

/**
 * Job de ingesta de la federación (S1.3): el listado del publicador municipal en datos.gob.es, con
 * {@code _pageSize} (tope 200) y {@code _page} desde 0 explícitos (el enlace {@code next} pierde el tamaño), sin
 * recuento total: se avanza mientras la página venga llena. Cada página hace upsert; la baja de lo que desaparece
 * la hace el listener de {@code DatasetIngested} al completarse la ejecución.
 */
@Component
class FederationIngestionJob implements IngestionJob {

	static final String PAGE_PARAM = "_page";
	static final String PAGE_SIZE_PARAM = "_pageSize";

	private final CatalogProperties properties;
	private final FederationJsonTranslator translator;
	private final RegisterFederatedDatasets registerFederatedDatasets;

	FederationIngestionJob(CatalogProperties properties, FederationJsonTranslator translator,
			RegisterFederatedDatasets registerFederatedDatasets) {
		this.properties = properties;
		this.translator = translator;
		this.registerFederatedDatasets = registerFederatedDatasets;
	}

	@Override
	public SourceDescriptor source() {
		var federation = properties.federation();
		return new SourceDescriptor(CatalogSources.FEDERATION, federation.url(), Map.of(),
				Pagination.pages(federation.pageSize(), PAGE_PARAM, PAGE_SIZE_PARAM), ResponseShape.RESULT_ITEMS);
	}

	@Override
	public Duration interval() {
		return properties.federation().interval();
	}

	@Override
	public void handle(RawPage page) {
		registerFederatedDatasets.register(translator.translate(page.body(), page.fetchedAt()), page.fetchedAt());
	}

}
