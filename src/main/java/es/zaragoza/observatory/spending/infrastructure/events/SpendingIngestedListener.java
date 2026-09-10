package es.zaragoza.observatory.spending.infrastructure.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import es.zaragoza.observatory.shared.DatasetIngested;
import es.zaragoza.observatory.spending.SpendingSources;
import es.zaragoza.observatory.spending.application.CheckDocumentedListing;

/**
 * SPEC.md §4.5 paso 6: tras cada censo se pide el listado <b>sin filtro</b> para comprobar que sigue siendo
 * subconjunto del ampliado y para marcar qué procesos esconde (ADR-017 §1).
 * <p>
 * Va aquí y no dentro del {@code handle} de la página porque es otra petición a la fuente, y el {@code handle}
 * es el sitio donde se traduce y se guarda lo que ya llegó.
 * <p>
 * <b>Si la ejecución no trajo registros, no se comprueba nada.</b> Es la salvaguarda de ADR-013 §2: un censo
 * vacío por un fallo de la fuente no puede acabar desmarcando los 5.730 procesos que sí están en el listado
 * documentado.
 */
@Component
class SpendingIngestedListener {

	private static final Logger log = LoggerFactory.getLogger(SpendingIngestedListener.class);

	private final CheckDocumentedListing checkDocumentedListing;

	SpendingIngestedListener(CheckDocumentedListing checkDocumentedListing) {
		this.checkDocumentedListing = checkDocumentedListing;
	}

	@ApplicationModuleListener
	void on(DatasetIngested event) {
		if (!SpendingSources.PROCESSES.equals(event.dataset())) {
			log.debug("ignoring DatasetIngested for {}", event.dataset());
			return;
		}
		if (event.records() == 0) {
			log.warn("spending: el censo {} no trajo procesos; no se comprueba el listado documentado",
					event.run());
			return;
		}
		var summary = checkDocumentedListing.check();
		log.info("spending run {} censó {} procesos; listado documentado: {} ocids, {} marcados, {} escondidos",
				event.run(), event.records(), summary.documented(), summary.marked(), summary.hidden());
	}

}
