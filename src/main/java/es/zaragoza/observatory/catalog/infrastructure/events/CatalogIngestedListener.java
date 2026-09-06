package es.zaragoza.observatory.catalog.infrastructure.events;

import java.time.Clock;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import es.zaragoza.observatory.catalog.CatalogSources;
import es.zaragoza.observatory.catalog.application.RegisterFederatedDatasets;
import es.zaragoza.observatory.catalog.application.TakeFreshnessSnapshots;
import es.zaragoza.observatory.ingestion.Ingestion;
import es.zaragoza.observatory.ingestion.IngestionRunSummary;
import es.zaragoza.observatory.shared.DatasetIngested;
import es.zaragoza.observatory.shared.ZaragozaTime;

/**
 * SPEC.md §4.5 paso 6: {@code catalog} escucha {@code DatasetIngested}. Tras cada ingesta del propio catálogo
 * recalcula las instantáneas de frescura del día (idempotente); tras cada ingesta de la federación da de baja
 * los datasets que datos.gob.es ya no lista (S1.3). Los eventos de otros datasets se ignoran.
 */
@Component
class CatalogIngestedListener {

	private static final Logger log = LoggerFactory.getLogger(CatalogIngestedListener.class);

	private final TakeFreshnessSnapshots takeFreshnessSnapshots;
	private final RegisterFederatedDatasets registerFederatedDatasets;
	private final Ingestion ingestion;
	private final Clock clock;

	CatalogIngestedListener(TakeFreshnessSnapshots takeFreshnessSnapshots,
			RegisterFederatedDatasets registerFederatedDatasets, Ingestion ingestion, Clock clock) {
		this.takeFreshnessSnapshots = takeFreshnessSnapshots;
		this.registerFederatedDatasets = registerFederatedDatasets;
		this.ingestion = ingestion;
		this.clock = clock;
	}

	@ApplicationModuleListener
	void on(DatasetIngested event) {
		if (CatalogSources.API_INVENTORY.equals(event.dataset())) {
			// El inventario se sincroniza entero en handle(); el cruce con las fichas se resuelve al leer (S1.2).
			log.info("api inventory run {} ingested the Swagger document", event.run());
			return;
		}
		if (CatalogSources.FEDERATION.equals(event.dataset())) {
			// Las páginas ya hicieron upsert; lo que no se ha visto desde el inicio de la ejecución ha desaparecido
			// de datos.gob.es (S1.3).
			ingestion.lastSuccessful(CatalogSources.FEDERATION).map(IngestionRunSummary::startedAt)
					.ifPresent(startedAt -> {
						int purged = registerFederatedDatasets.purgeNotSeenSince(startedAt);
						log.info("federation run {} listed {} datasets; {} no longer federated", event.run(),
								event.records(), purged);
					});
			return;
		}
		if (!CatalogSources.CATALOG.equals(event.dataset())) {
			log.debug("ignoring DatasetIngested for {}", event.dataset());
			return;
		}
		LocalDate today = LocalDate.ofInstant(clock.instant(), ZaragozaTime.ZONE);
		int taken = takeFreshnessSnapshots.take(today);
		log.info("catalog run {} ingested {} records; {} freshness snapshots for {}", event.run(),
				event.records(), taken, today);
	}

}
