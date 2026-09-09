package es.zaragoza.observatory.urban.infrastructure.zaragoza;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import es.zaragoza.observatory.ingestion.Ingestion;
import es.zaragoza.observatory.ingestion.IngestionJob;
import es.zaragoza.observatory.ingestion.RawPage;
import es.zaragoza.observatory.ingestion.SourceDescriptor;
import es.zaragoza.observatory.ingestion.SourceDescriptor.Pagination;
import es.zaragoza.observatory.ingestion.SourceDescriptor.ResponseShape;
import es.zaragoza.observatory.shared.ZaragozaTime;
import es.zaragoza.observatory.urban.UrbanSources;
import es.zaragoza.observatory.urban.application.RegisterLicensedPremises;
import es.zaragoza.observatory.urban.domain.PremisesRepository;
import es.zaragoza.observatory.urban.infrastructure.UrbanProperties;

/**
 * Ingesta de los locales con licencia (S2.4, ADR-016 §4). <b>Un solo job</b>, al contrario que en
 * {@code citizen}, y la razón es una decisión de diseño que aquella fuente no permitía:
 * <p>
 * <b>El filtro y el orden van en campos distintos.</b> Se filtra por {@code q=lastUpdated=ge=<marca>}, que
 * recorta de verdad, y se ordena siempre por {@code id asc}, que es el único eje sin empates. Así la ventana es
 * exacta y la paginación también, con o sin marca de agua. En las quejas filtro y orden compartían campo, y
 * como por {@code updated_datetime} la paginación por offset pierde registros, hubo que repartir el trabajo
 * entre dos jobs y prohibirle a uno de ellos la carga del histórico (S2.2 §10).
 * <p>
 * Lo que aquí se evita, medido: un barrido completo ordenado por {@code lastUpdated} devuelve 42.342 filas y
 * solo <b>39.414</b> identificadores distintos, porque 22.044 locales comparten un mismo instante de carga.
 * <p>
 * Sin marca de agua, el barrido son 85 páginas y 40 MB. Con ella, decenas de registros: 54 en ocho días.
 * <b>Se piden los registros completos</b>, sin {@code fl}, porque la proyección vacía los objetos anidados
 * (S2.4 §2); el texto libre lo descarta el traductor.
 */
@Component
class LicensedPremisesIngestionJob implements IngestionJob {

	/** Formato aceptado por el FIQL de la sede, verificado en S2.4 ({@code lastUpdated=ge=2026-09-01T00:00:00}). */
	private static final DateTimeFormatter FIQL_LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
			.withZone(ZaragozaTime.ZONE);

	private final UrbanProperties properties;
	private final PremisesRepository premises;
	private final LicensedPremisesJsonTranslator translator;
	private final RegisterLicensedPremises register;
	private final Ingestion ingestion;

	LicensedPremisesIngestionJob(UrbanProperties properties, PremisesRepository premises,
			LicensedPremisesJsonTranslator translator, RegisterLicensedPremises register, Ingestion ingestion) {
		this.properties = properties;
		this.premises = premises;
		this.translator = translator;
		this.register = register;
		this.ingestion = ingestion;
	}

	@Override
	public SourceDescriptor source() {
		var query = new LinkedHashMap<String, String>();
		query.put("srsname", "wgs84");
		query.put("sort", "id asc");
		watermark().ifPresent(mark -> query.put("q", "lastUpdated=ge=" + FIQL_LOCAL.format(mark)));
		return new SourceDescriptor(UrbanSources.PREMISES, properties.listUrl(), Map.copyOf(query),
				Pagination.offset(properties.rows()), ResponseShape.ENVELOPE);
	}

	@Override
	public Duration interval() {
		return properties.interval();
	}

	/**
	 * <b>La página cruda no se guarda.</b> Contiene el texto libre del origen con los datos personales que
	 * ADR-016 §3 decidió no tener, y aquí no se puede evitar descargarlo: dejarlo catorce días en
	 * {@code raw_payload} sería guardarlo por la puerta de atrás. De paso se ahorran los 40 MB por barrido
	 * completo. El precio, consciente, es no poder reprocesar una página sin volver a pedirla.
	 */
	@Override
	public boolean keepsRawPayload() {
		return false;
	}

	@Override
	public void handle(RawPage page) {
		register.register(translator.translate(page.body()), page.fetchedAt());
	}

	/**
	 * Desde dónde pedir. Vacío significa barrer el registro entero, y es lo que pasa mientras este job no haya
	 * terminado un barrido con éxito: una tabla con datos de una ejecución que falló a medias no prueba que el
	 * histórico esté completo (la lección que costó una carga de 25 registros en {@code citizen}, S2.2).
	 */
	private Optional<Instant> watermark() {
		if (ingestion.lastSuccessful(UrbanSources.PREMISES).isEmpty()) {
			return Optional.empty();
		}
		return premises.latestUpdatedAt().map(mark -> mark.minus(properties.watermarkMargin()));
	}

}
