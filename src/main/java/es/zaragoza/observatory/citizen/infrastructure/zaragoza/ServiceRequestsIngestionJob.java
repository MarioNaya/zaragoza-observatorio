package es.zaragoza.observatory.citizen.infrastructure.zaragoza;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import es.zaragoza.observatory.citizen.application.RegisterServiceRequests;
import es.zaragoza.observatory.citizen.domain.ServiceRequestRepository;
import es.zaragoza.observatory.citizen.infrastructure.CitizenProperties;
import es.zaragoza.observatory.ingestion.IngestionJob;
import es.zaragoza.observatory.ingestion.RawPage;
import es.zaragoza.observatory.ingestion.SourceDescriptor;
import es.zaragoza.observatory.ingestion.SourceDescriptor.Pagination;
import es.zaragoza.observatory.ingestion.SourceDescriptor.ResponseShape;
import es.zaragoza.observatory.shared.DatasetRef;

/**
 * Base de los dos jobs de ingesta de quejas (S2.2). Los dos piden el mismo listado y se diferencian solo en el
 * campo de fecha por el que lo recorren: {@code requested_datetime} para las altas y {@code updated_datetime}
 * para los cierres.
 * <p>
 * Tres reglas verificadas en S2.2 y que este código no puede saltarse:
 * <ul>
 * <li><b>{@code sort} explícito siempre.</b> El orden por defecto no está documentado y ya ha dado dos
 * resultados distintos en tres días; paginar por offset sobre él se saltaría registros sin avisar. Con
 * {@code sort=<campo> asc} la paginación es repetible y dos páginas consecutivas no comparten ningún id.</li>
 * <li><b>Marca de agua ascendente.</b> Se pide desde la última fecha guardada hacia adelante, de modo que los
 * registros nuevos se añaden al final y los offsets no se mueven mientras se pagina. Sin marca —primera
 * ejecución— se hace la carga completa: 89.432 registros en 179 páginas.</li>
 * <li><b>Proyección de ocho campos</b> ({@code fl}): el texto libre no se pide (ADR-012).</li>
 * </ul>
 */
abstract class ServiceRequestsIngestionJob implements IngestionJob {

	/** Formato aceptado por el FIQL de la sede, verificado en S2.2 ({@code 2026-09-01T00:00:00Z}). */
	private static final DateTimeFormatter FIQL_INSTANT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
			.withZone(ZoneOffset.UTC);

	protected final CitizenProperties properties;
	protected final ServiceRequestRepository requests;
	private final ServiceRequestJsonTranslator translator;
	private final RegisterServiceRequests register;

	ServiceRequestsIngestionJob(CitizenProperties properties, ServiceRequestRepository requests,
			ServiceRequestJsonTranslator translator, RegisterServiceRequests register) {
		this.properties = properties;
		this.requests = requests;
		this.translator = translator;
		this.register = register;
	}

	/** Campo de fecha por el que se recorre el listado. */
	abstract String dateField();

	/** Referencia del dataset: una por eje, para que cada uno tenga su propio registro de ejecuciones. */
	abstract DatasetRef dataset();

	/** Última fecha guardada de ese eje; vacío en la primera ejecución. */
	abstract Optional<Instant> watermark();

	@Override
	public SourceDescriptor source() {
		var query = new LinkedHashMap<String, String>();
		query.put("srsname", "wgs84");
		query.put("fl", properties.fields());
		query.put("sort", dateField() + " asc");
		watermark().ifPresent(mark -> query.put("q", dateField() + "=ge=" + fiql(mark)));
		return new SourceDescriptor(dataset(), properties.listUrl(), Map.copyOf(query),
				Pagination.offset(properties.rows()), ResponseShape.ARRAY);
	}

	@Override
	public Duration interval() {
		return properties.interval();
	}

	@Override
	public void handle(RawPage page) {
		register.register(translator.translate(page.body()), page.fetchedAt());
	}

	/** La marca menos el margen de seguridad de {@link CitizenProperties#watermarkMargin()}. */
	private String fiql(Instant watermark) {
		return FIQL_INSTANT.format(watermark.minus(properties.watermarkMargin()));
	}

}
