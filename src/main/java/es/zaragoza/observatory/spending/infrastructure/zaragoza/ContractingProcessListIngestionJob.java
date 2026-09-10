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
import es.zaragoza.observatory.spending.application.RegisterProcesses;
import es.zaragoza.observatory.spending.infrastructure.SpendingProperties;

/**
 * Censo de procesos de contratación (S3.1, ADR-017 §1 y §5). <b>Una sola petición</b>: OCDS ignora
 * {@code start}, así que no hay paginación por desplazamiento y el listado se pide entero (622 KB, medio
 * segundo).
 * <p>
 * La petición lleva el <b>interruptor</b> {@code after=2030-01-01T00:00:00Z}, que no es una fecha: es lo que
 * abre los 2.271 procesos que el listado documentado esconde. Ver {@link OcdsListing} antes de tocarlo. Y no
 * lleva {@code sort}, porque {@code sort=id desc} se acepta y no se aplica; el orden se pone en casa.
 * <p>
 * El censo trae solo identificadores. El contenido llega después, proceso a proceso, por el planificador del
 * módulo: son 8.001 peticiones y no caben en el ciclo de una página.
 * <p>
 * <b>La página cruda sí se guarda</b>, al contrario que en {@code urban} (ADR-016 §3): son 622 KB de ocids, sin
 * una línea de texto ni un identificador de persona, y la retención de catorce días lo acota. El detalle, que sí
 * trae texto, no pasa por aquí en ningún caso, así que sus 25,2 MB no se guardan nunca.
 */
@Component
class ContractingProcessListIngestionJob implements IngestionJob {

	private final SpendingProperties properties;
	private final OcdsListJsonTranslator translator;
	private final RegisterProcesses register;

	ContractingProcessListIngestionJob(SpendingProperties properties, OcdsListJsonTranslator translator,
			RegisterProcesses register) {
		this.properties = properties;
		this.translator = translator;
		this.register = register;
	}

	@Override
	public SourceDescriptor source() {
		return new SourceDescriptor(SpendingSources.PROCESSES, properties.listUrl(),
				Map.of(OcdsListing.AFTER, OcdsListing.AFTER_SWITCH), Pagination.none(properties.rows()),
				ResponseShape.ARRAY);
	}

	@Override
	public Duration interval() {
		return properties.interval();
	}

	@Override
	public void handle(RawPage page) {
		register.register(translator.translate(page.body()), page.fetchedAt());
	}

}
