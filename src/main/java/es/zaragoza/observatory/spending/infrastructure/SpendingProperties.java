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
 * @param grants parámetros de la tercera, las subvenciones (S3.3, ADR-018)
 */
@ConfigurationProperties(prefix = "zaragoza.spending")
public record SpendingProperties(
		@DefaultValue("https://www.zaragoza.es/sede/servicio/contratacion-publica/ocds") String ocdsBaseUrl,
		@DefaultValue("20000") int rows, @DefaultValue("P1D") Duration interval,
		@DefaultValue Releases releases, @DefaultValue Budget budget, @DefaultValue Grants grants) {

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

	/**
	 * Las subvenciones (S3.3, ADR-018). Cuatro recursos y un solo parámetro crítico: la <b>proyección</b>.
	 *
	 * @param baseUrl raíz de la familia v1 de la sede, sin barra final
	 * @param v2BaseUrl raíz de la familia v2, de la que solo se usa el enlace con el beneficiario
	 * @param grantFields proyección de las concesiones. <b>Es la garantía de ADR-018 §3</b>: no incluye
	 * {@code adjudicatario}, así que el nombre de la persona física no se descarga. Cambiarla para añadirlo
	 * rompe el arranque a propósito
	 * @param callFields proyección de las convocatorias. Excluye {@code resolucion[]}, que sin ella trae el
	 * conjunto entero de concesiones dentro —con los nombres y los DNI— en 21 MB (S3.3 §8)
	 * @param rows tamaño de página de la v1; el tope de la sede son 500 (regla 18)
	 * @param pageSize tamaño de página de la v2, que no usa {@code rows}
	 * @param interval intervalo mínimo entre ingestas de cada recurso
	 * @param fullSweep si el barrido de concesiones ignora la marca de agua y recorre el censo entero. La carga
	 * inicial lo necesita; después basta con {@code id=gt=}, porque el identificador es creciente (S3.3 §9)
	 */
	public record Grants(@DefaultValue("https://www.zaragoza.es/sede/servicio/ayuda-subvencion") String baseUrl,
			@DefaultValue("https://www.zaragoza.es/sede/servicio/ayuda-subvencion-v2") String v2BaseUrl,
			@DefaultValue("id,title,expediente,importeSolicitado,importeConcedido,importeAnual,numAnualidades,"
					+ "fechaSolicitud,fechaConcesion,fechaAcuerdo,convocatoria") String grantFields,
			@DefaultValue("id,title,ejercicioClave,esPlurianual,fechaInicioVigencia,fechaFinVigencia,"
					+ "fechaInicioPresentacion,fechaFinPresentacion,presupuesto,porcentajeAnticipado,gestor,"
					+ "funciones,objetos,tipo,lineaEstrategica.lineaAuxiliar,lineaAmbito.ambito") String callFields,
			@DefaultValue("500") int rows, @DefaultValue("500") int pageSize,
			@DefaultValue("P1D") Duration interval, @DefaultValue("false") boolean fullSweep) {

		/**
		 * Campos que la proyección de concesiones no puede contener nunca (ADR-018 §3). {@code adjudicatario} es
		 * el objeto que lleva dentro el nombre y apellidos de 6.333 beneficiarios.
		 */
		static final String[] FORBIDDEN_GRANT_FIELDS = { "adjudicatario", "adjudicatario.nombre",
				"adjudicatario.beneficiario" };

		/**
		 * Y los que no puede contener la de convocatorias: {@code resolucion} trae dentro el conjunto entero de
		 * concesiones sin proyectar, con los nombres y los documentos de identidad.
		 */
		static final String[] FORBIDDEN_CALL_FIELDS = { "resolucion", "resolucion.adjudicatario" };

		public Grants {
			reject(grantFields, FORBIDDEN_GRANT_FIELDS, "grant-fields");
			reject(callFields, FORBIDDEN_CALL_FIELDS, "call-fields");
			if (rows <= 0 || rows > SourceDescriptor.SEDE_MAX_ROWS) {
				throw new IllegalArgumentException(
						"grants.rows must be between 1 and " + SourceDescriptor.SEDE_MAX_ROWS + " (S0.5)");
			}
			if (pageSize <= 0 || pageSize > SourceDescriptor.SEDE_MAX_ROWS) {
				throw new IllegalArgumentException("grants.page-size must be between 1 and "
						+ SourceDescriptor.SEDE_MAX_ROWS + " (ADR-018 §2: se pagina, no se pide el todo)");
			}
			baseUrl = baseUrl == null ? null : baseUrl.replaceAll("/+$", "");
			v2BaseUrl = v2BaseUrl == null ? null : v2BaseUrl.replaceAll("/+$", "");
		}

		private static void reject(String fields, String[] forbidden, String property) {
			for (String declared : fields.split(",")) {
				for (String banned : forbidden) {
					if (declared.strip().equalsIgnoreCase(banned)) {
						throw new IllegalArgumentException("zaragoza.spending.grants." + property
								+ " must not request the identity of a grant beneficiary (ADR-018): found '"
								+ banned + "'");
					}
				}
			}
		}

		/** Las concesiones, que son el censo completo: {@code …/ayuda-subvencion/resolucion.json}. */
		public URI grantsUrl() {
			return URI.create(baseUrl + "/resolucion.json");
		}

		/** Las convocatorias: {@code …/ayuda-subvencion/convocatoria.json}. */
		public URI callsUrl() {
			return URI.create(baseUrl + "/convocatoria.json");
		}

		/** El directorio de beneficiarios: {@code …/ayuda-subvencion-v2/organization.json}. */
		public URI beneficiariesUrl() {
			return URI.create(v2BaseUrl + "/organization.json");
		}

		/** El enlace concesión → beneficiario: {@code …/ayuda-subvencion-v2/concesion.json}. */
		public URI linksUrl() {
			return URI.create(v2BaseUrl + "/concesion.json");
		}
	}

}
