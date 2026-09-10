package es.zaragoza.observatory.spending.infrastructure.zaragoza;

import java.time.Duration;
import java.util.Map;

import org.springframework.stereotype.Component;

import es.zaragoza.observatory.ingestion.IngestionJob;
import es.zaragoza.observatory.ingestion.RawPage;
import es.zaragoza.observatory.ingestion.SourceDescriptor;
import es.zaragoza.observatory.ingestion.SourceDescriptor.Pagination;
import es.zaragoza.observatory.ingestion.SourceDescriptor.ResponseShape;
import es.zaragoza.observatory.spending.SpendingSources;
import es.zaragoza.observatory.spending.application.RegisterBudgetSnapshots;
import es.zaragoza.observatory.spending.infrastructure.SpendingProperties;

/**
 * Censo de instantáneas del presupuesto (S3.2 §1 y §11). <b>Una sola petición</b>: el censo ignora {@code rows}
 * y devuelve las 140 fechas enteras en 11 KB.
 * <p>
 * Lo que trae son URL, no registros: de cada una se saca la fecha y nada más. Las partidas llegan después,
 * instantánea a instantánea, por el planificador del módulo, porque el histórico son 396 peticiones y no caben
 * en el ciclo de una página.
 * <p>
 * <b>La página cruda sí se guarda</b>, como en el censo de contratación: son 11 KB de URL, sin una línea de
 * texto ni un identificador de persona, y la retención de catorce días lo acota. Las páginas de partidas, que sí
 * traen texto, no pasan por aquí en ningún caso.
 */
@Component
class BudgetSnapshotListIngestionJob implements IngestionJob {

	private final SpendingProperties properties;
	private final BudgetDatesJsonTranslator translator;
	private final RegisterBudgetSnapshots register;

	BudgetSnapshotListIngestionJob(SpendingProperties properties, BudgetDatesJsonTranslator translator,
			RegisterBudgetSnapshots register) {
		this.properties = properties;
		this.translator = translator;
		this.register = register;
	}

	@Override
	public SourceDescriptor source() {
		return new SourceDescriptor(SpendingSources.BUDGET, properties.budget().censusUrl(), Map.of(),
				Pagination.none(properties.budget().rows()), ResponseShape.ENVELOPE);
	}

	@Override
	public Duration interval() {
		return properties.budget().interval();
	}

	@Override
	public void handle(RawPage page) {
		register.register(translator.translate(page.body()), page.fetchedAt());
	}

}
