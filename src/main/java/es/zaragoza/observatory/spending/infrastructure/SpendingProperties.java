package es.zaragoza.observatory.spending.infrastructure;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import es.zaragoza.observatory.ingestion.SourceDescriptor;
import es.zaragoza.observatory.spending.infrastructure.zaragoza.BudgetListing;

/**
 * Parámetros del módulo {@code spending}. La fuente y sus reglas están verificadas en S3.1.
 *
 * @param ocdsBaseUrl raíz del servicio OCDS de la sede, sin barra final
 * @param rows tamaño de página del censo. Aquí <b>no hay tope de 500</b>: OCDS no es la familia de la sede,
 * ignora {@code start} y devuelve el listado entero en una petición (447 KB sin filtro, 622 KB con él)
 * @param interval intervalo mínimo entre censos
 * @param releases parámetros del planificador que lee el detalle proceso a proceso
 * @param budget parámetros de la segunda fuente del módulo, el presupuesto de gastos (S3.2)
 */
@ConfigurationProperties(prefix = "zaragoza.spending")
public record SpendingProperties(
		@DefaultValue("https://www.zaragoza.es/sede/servicio/contratacion-publica/ocds") String ocdsBaseUrl,
		@DefaultValue("20000") int rows, @DefaultValue("P1D") Duration interval,
		@DefaultValue Releases releases, @DefaultValue Budget budget) {

	/** Un ocid solo puede llevar estos caracteres; con cualquier otro no se construye una URL (S3.1 §1). */
	private static final Pattern SAFE_OCID = Pattern.compile("[A-Za-z0-9._-]{1,120}");

	public SpendingProperties {
		if (rows <= 0) {
			throw new IllegalArgumentException("rows must be positive");
		}
		ocdsBaseUrl = ocdsBaseUrl == null ? null : ocdsBaseUrl.replaceAll("/+$", "");
	}

	/** El censo de procesos: {@code …/ocds/contracting-process.json}. */
	public URI listUrl() {
		return URI.create(ocdsBaseUrl + "/contracting-process.json");
	}

	/**
	 * El release package de un proceso: {@code …/ocds/contracting-process/{ocid}.json}. Devuelve {@code null} si
	 * el ocid no tiene forma de ocid: el listado es entrada externa y no se mete en una ruta sin mirarla.
	 */
	public URI detailUrl(String ocid) {
		if (ocid == null || !SAFE_OCID.matcher(ocid).matches()) {
			return null;
		}
		return URI.create(ocdsBaseUrl + "/contracting-process/" + ocid + ".json");
	}

	/**
	 * Planificador del detalle (ADR-017 §5). Con los valores por defecto —200 por tick cada 10 minutos con pausa
	 * de 0,2 s— el histórico entero (8.001 procesos) se completa en unas siete horas, y el mantenimiento diario
	 * cuesta una fracción de un lote. Para cargarlo de una vez en desarrollo se sube {@code batch-size} y se baja
	 * {@code tick}, como con el muestreo observado de {@code catalog}.
	 *
	 * @param batchSize procesos por tick
	 * @param requestDelay pausa entre peticiones, por cortesía (S0.5). La latencia media medida es de 181 ms
	 * @param missingInitialBackoff primera espera tras un 404 o un release vacío
	 * @param missingMaxBackoff tope de esa espera
	 * @param activeRefresh cada cuánto se relee un proceso con licitación activa
	 * @param publishedRefresh cada cuánto se relee un proceso publicado y cerrado. La fuente no publica
	 * {@code ETag} ni {@code Last-Modified}, así que no hay forma de preguntar si cambió: solo releer
	 */
	public record Releases(@DefaultValue("true") boolean enabled, @DefaultValue("PT10M") Duration tick,
			@DefaultValue("PT3M") Duration initialDelay, @DefaultValue("200") int batchSize,
			@DefaultValue("PT0.2S") Duration requestDelay, @DefaultValue("P1D") Duration missingInitialBackoff,
			@DefaultValue("P30D") Duration missingMaxBackoff, @DefaultValue("P7D") Duration activeRefresh,
			@DefaultValue("P30D") Duration publishedRefresh) {

		public Releases {
			if (batchSize <= 0) {
				throw new IllegalArgumentException("releases.batch-size must be positive");
			}
			if (requestDelay == null || requestDelay.isNegative()) {
				throw new IllegalArgumentException("releases.request-delay must not be negative");
			}
		}
	}

	/**
	 * El presupuesto de gastos (S3.2). Aquí <b>sí</b> manda el tope de la sede: {@code rows} topa en 500 y
	 * {@code start} se aplica, al revés que en OCDS.
	 * <p>
	 * El histórico entero son 396 peticiones y 2,3 minutos, así que el planificador puede ir despacio sin que
	 * cueste nada: con los valores por defecto —10 instantáneas por tick cada 10 minutos— la carga inicial
	 * termina en unas dos horas y media, y después no queda casi nada que hacer, porque <b>una instantánea
	 * publicada no se reescribe</b> y solo se relee la más reciente.
	 *
	 * @param baseUrl raíz del servicio de presupuesto de la sede, sin barra final
	 * @param rows tamaño de página de las partidas; el tope de la sede son 500 (regla 18)
	 * @param interval intervalo mínimo entre censos de instantáneas
	 * @param batchSize instantáneas por tick; cada una cuesta unas tres peticiones
	 * @param requestDelay pausa entre peticiones, por cortesía (S0.5)
	 * @param missingInitialBackoff primera espera tras una instantánea ausente o vacía
	 * @param missingMaxBackoff tope de esa espera
	 * @param latestRefresh cada cuánto se relee la instantánea más reciente, que es la única que la fuente
	 * podría rehacer
	 */
	public record Budget(@DefaultValue("https://www.zaragoza.es/sede/servicio/presupuesto") String baseUrl,
			@DefaultValue("500") int rows, @DefaultValue("P1D") Duration interval,
			@DefaultValue("true") boolean enabled, @DefaultValue("PT10M") Duration tick,
			@DefaultValue("PT5M") Duration initialDelay, @DefaultValue("10") int batchSize,
			@DefaultValue("PT0.2S") Duration requestDelay, @DefaultValue("P1D") Duration missingInitialBackoff,
			@DefaultValue("P30D") Duration missingMaxBackoff, @DefaultValue("P7D") Duration latestRefresh) {

		public Budget {
			if (rows <= 0 || rows > SourceDescriptor.SEDE_MAX_ROWS) {
				throw new IllegalArgumentException(
						"budget.rows must be between 1 and " + SourceDescriptor.SEDE_MAX_ROWS + " (S0.5)");
			}
			if (batchSize <= 0) {
				throw new IllegalArgumentException("budget.batch-size must be positive");
			}
			if (requestDelay == null || requestDelay.isNegative()) {
				throw new IllegalArgumentException("budget.request-delay must not be negative");
			}
			baseUrl = baseUrl == null ? null : baseUrl.replaceAll("/+$", "");
		}

		/** El censo de instantáneas: {@code …/presupuesto/gasto-corriente/fecha.json}. */
		public URI censusUrl() {
			return URI.create(baseUrl + "/gasto-corriente/fecha.json");
		}

		/** Una instantánea: {@code …/presupuesto/gasto-corriente/fecha/{yyyyMMdd}.json}. */
		public URI snapshotUrl(LocalDate date) {
			return URI.create(baseUrl + "/gasto-corriente/fecha/" + BudgetListing.DATE.format(date) + ".json");
		}
	}

}
