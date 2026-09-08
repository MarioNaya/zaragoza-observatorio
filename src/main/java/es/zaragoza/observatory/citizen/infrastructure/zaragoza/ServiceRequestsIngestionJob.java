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
import es.zaragoza.observatory.ingestion.Ingestion;
import es.zaragoza.observatory.ingestion.IngestionJob;
import es.zaragoza.observatory.ingestion.RawPage;
import es.zaragoza.observatory.ingestion.SourceDescriptor;
import es.zaragoza.observatory.ingestion.SourceDescriptor.Pagination;
import es.zaragoza.observatory.ingestion.SourceDescriptor.ResponseShape;
import es.zaragoza.observatory.shared.DatasetRef;

/**
 * Base de los dos jobs de ingesta de quejas (S2.2). Los dos piden el mismo listado y se diferencian en el campo
 * de fecha por el que lo recorren: {@code requested_datetime} para las altas y {@code updated_datetime} para los
 * cierres. Esa diferencia no es cosmética, porque <b>los dos ejes no se comportan igual</b> (S2.2 §10):
 * <ul>
 * <li>por {@code requested_datetime asc} el barrido completo es <b>exacto</b>: 179 páginas, 89.432 filas y
 * 89.432 identificadores distintos, sin una sola repetición;</li>
 * <li>por {@code updated_datetime asc} el mismo barrido devuelve 89.432 filas pero solo <b>80.628</b>
 * identificadores distintos: 2.680 se repiten (alguno siete veces) y <b>8.804 registros no aparecen nunca</b>.
 * Muchas quejas comparten el mismo {@code updated_datetime} —se cierran por lotes—, y entre filas empatadas el
 * orden no es estable de una página a otra, así que la paginación por offset se salta unas y repite otras.</li>
 * </ul>
 * De ahí el reparto de papeles: <b>la carga completa del histórico la hace siempre el job de altas</b>, y el de
 * cierres solo recorre ventanas incrementales, que caben en una página y no sufren el problema.
 * <p>
 * El resto de reglas verificadas en S2.2 que este código no puede saltarse:
 * <ul>
 * <li><b>{@code sort} explícito siempre.</b> El orden por defecto no está documentado y ya ha dado dos
 * resultados distintos en tres días; paginar por offset sobre él se saltaría registros sin avisar.</li>
 * <li><b>Marca de agua propia de cada job.</b> La fecha sale de la tabla, pero la tabla la llenan los dos jobs y
 * lo que ha visto uno no dice nada de lo que ha visto el otro: cada uno decide si la aplica mirando su propio
 * registro de ejecuciones ({@link #hasCompletedASweep()}). Sin esa comprobación, el job de altas encontraba la
 * tabla ya poblada por el de cierres, concluía que tenía el histórico y se saltaba la carga completa (observado
 * en la primera ejecución real del 2026-09-08: 25 registros en vez de 89.432). Un barrido que falla a medias
 * tampoco cuenta como completado, así que el siguiente vuelve a empezar por el principio.</li>
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
	private final Ingestion ingestion;

	ServiceRequestsIngestionJob(CitizenProperties properties, ServiceRequestRepository requests,
			ServiceRequestJsonTranslator translator, RegisterServiceRequests register, Ingestion ingestion) {
		this.properties = properties;
		this.requests = requests;
		this.translator = translator;
		this.register = register;
		this.ingestion = ingestion;
	}

	/** Campo de fecha por el que se recorre el listado. */
	abstract String dateField();

	/** Referencia del dataset: una por eje, para que cada uno tenga su propio registro de ejecuciones. */
	abstract DatasetRef dataset();

	/**
	 * Desde dónde pedir. Vacío significa <b>barrer el listado entero</b>, y solo el job de altas puede permitirse
	 * decirlo: por el eje de cierres un barrido completo pierde registros.
	 */
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

	/** Si <b>este</b> job —no el otro— ha terminado antes un barrido con éxito. */
	protected boolean hasCompletedASweep() {
		return ingestion.lastSuccessful(dataset()).isPresent();
	}

	/** La marca menos el margen de seguridad de {@link CitizenProperties#watermarkMargin()}. */
	private String fiql(Instant watermark) {
		return FIQL_INSTANT.format(watermark.minus(properties.watermarkMargin()));
	}

}
