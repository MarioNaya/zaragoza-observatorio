package es.zaragoza.observatory.ingestion;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import es.zaragoza.observatory.shared.DatasetRef;
import es.zaragoza.observatory.shared.Sources;

/**
 * Descripción de qué traer de la API municipal (SPEC.md §4.3: «ingestion recibe descripciones de qué traer»).
 * Codifica las reglas verificadas en S0.5 (docs/spikes/S0.5-api.md) para que no se puedan violar por descuido:
 * <ul>
 * <li>la URL lleva siempre extensión {@code .json} o {@code .geojson}; nunca se depende de {@code Accept};</li>
 * <li>en la sede y en el espacio de datos {@code rows} tiene tope 500;</li>
 * <li>la paginación por {@code start} solo se usa donde funciona ({@code OFFSET}); OCDS ignora {@code start} y se
 * trae en una petición ({@code NONE});</li>
 * <li>un documento único ({@code DOCUMENT}, como el Swagger de la API) se trae en una petición sin parámetros de
 * paginación (S1.2).</li>
 * </ul>
 *
 * @param dataset referencia estable del dataset
 * @param url URL absoluta sin query string, con extensión {@code .json}/{@code .geojson}
 * @param query parámetros fijos de la petición (p. ej. {@code fl}, {@code srsname=wgs84}); los de paginación los
 * añade {@code ingestion}
 * @param pagination estrategia de paginación
 * @param shape forma de la respuesta, necesaria para contar registros y saber si hay más páginas
 */
public record SourceDescriptor(DatasetRef dataset, URI url, Map<String, String> query, Pagination pagination,
		ResponseShape shape) {

	/** Tope de {@code rows} en la sede y el espacio de datos (S0.1, S0.5). */
	public static final int SEDE_MAX_ROWS = 500;

	/** Tope efectivo de {@code _pageSize} en datos.gob.es: pedir más devuelve 200 (S1.3). */
	public static final int DATOS_GOB_ES_MAX_ROWS = 200;

	public SourceDescriptor {
		Objects.requireNonNull(dataset, "dataset must not be null");
		Objects.requireNonNull(url, "url must not be null");
		Objects.requireNonNull(pagination, "pagination must not be null");
		Objects.requireNonNull(shape, "shape must not be null");
		if (!url.isAbsolute() || url.getRawQuery() != null) {
			throw new IllegalArgumentException("url must be absolute and carry no query string: " + url);
		}
		String path = url.getPath() == null ? "" : url.getPath().toLowerCase(Locale.ROOT);
		if (!path.endsWith(".json") && !path.endsWith(".geojson")) {
			throw new IllegalArgumentException("url must end with .json or .geojson (S0.5): " + url);
		}
		if (isSedeFamily(dataset.source()) && pagination.rows() > SEDE_MAX_ROWS) {
			throw new IllegalArgumentException(
					"rows must be <= " + SEDE_MAX_ROWS + " for source " + dataset.source() + " (S0.5)");
		}
		if (Sources.DATOS_GOB_ES.equals(dataset.source()) && pagination.rows() > DATOS_GOB_ES_MAX_ROWS) {
			throw new IllegalArgumentException(
					"rows must be <= " + DATOS_GOB_ES_MAX_ROWS + " for source " + dataset.source() + " (S1.3)");
		}
		if (shape == ResponseShape.DOCUMENT && pagination.mode() != Pagination.Mode.NONE) {
			throw new IllegalArgumentException("a DOCUMENT is fetched in a single request: pagination must be NONE");
		}
		query = query == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(query));
	}

	/** Un documento JSON único (Swagger de la API, S1.2): una sola petición sin {@code rows} ni {@code start}. */
	public static SourceDescriptor document(DatasetRef dataset, URI url) {
		return new SourceDescriptor(dataset, url, Map.of(), Pagination.none(1), ResponseShape.DOCUMENT);
	}

	private static boolean isSedeFamily(String source) {
		return Sources.SEDE.equals(source) || Sources.DATA_SPACE.equals(source);
	}

	/** Forma de la respuesta JSON de la fuente (S0.5, tabla «Forma de la respuesta y paginación»). */
	public enum ResponseShape {
		/** {@code {"totalCount":N,"start":S,"rows":R,"result":[...]}}; {@code totalCount} puede faltar. */
		ENVELOPE,
		/** Array JSON en la raíz (quejas de sede, OCDS, Open311). */
		ARRAY,
		/**
		 * Un único objeto JSON que no es una lista de registros (el Swagger de la API, S1.2). Cuenta como un
		 * registro; no se envían parámetros de paginación (la fuente los ignora, y {@code HEAD} devuelve 400).
		 */
		DOCUMENT,
		/**
		 * Linked Data API de datos.gob.es (S1.3): {@code {"format":…,"result":{"items":[...],"next":…}}}; sin
		 * recuento total, así que se avanza mientras la página venga llena.
		 */
		RESULT_ITEMS,
		/**
		 * Envoltorio de páginas de la familia de subvenciones (S3.3 §1):
		 * {@code {"page":…,"pageSize":…,"totalRecords":N,"records":[...]}}. Es el mismo servicio que el
		 * {@link #ENVELOPE} y sirve los dos: la v1 usa {@code result} en sus subrecursos y la v2 usa
		 * {@code records} en todos, así que un adaptador para ese tag tiene que aceptar las dos formas.
		 * <p>
		 * El tamaño de página se manda en {@code pageSize} y no en {@code rows}, y el desplazamiento en
		 * {@code start}: mezclar {@code rows} con {@code page} solapa páginas en silencio (S3.3 §2).
		 */
		PAGED_RECORDS
	}

	/**
	 * Estrategia de paginación.
	 *
	 * @param mode {@code NONE}: una sola petición con {@code rows}; {@code OFFSET}: {@code start} creciente de
	 * {@code rows} en {@code rows} hasta agotar {@code totalCount} o recibir una página corta; {@code PAGE}:
	 * número de página desde 0 (datos.gob.es, S1.3) hasta recibir una página corta
	 * @param rows registros por petición (siempre se envía; el valor por defecto de la sede es 50)
	 * @param pageParam nombre del parámetro de posición ({@code start} en la sede, {@code _page} en
	 * datos.gob.es); {@code null} en {@code NONE}
	 * @param rowsParam nombre del parámetro de tamaño ({@code rows} en la sede, {@code _pageSize} en datos.gob.es)
	 */
	public record Pagination(Mode mode, int rows, String pageParam, String rowsParam) {

		public Pagination {
			Objects.requireNonNull(mode, "mode must not be null");
			if (rows <= 0) {
				throw new IllegalArgumentException("rows must be positive");
			}
			if (mode != Mode.NONE && (pageParam == null || pageParam.isBlank())) {
				throw new IllegalArgumentException("pageParam is required for " + mode);
			}
			if (rowsParam == null || rowsParam.isBlank()) {
				throw new IllegalArgumentException("rowsParam must not be blank");
			}
		}

		public Pagination(Mode mode, int rows) {
			this(mode, rows, mode == Mode.NONE ? null : "start", "rows");
		}

		public static Pagination none(int rows) {
			return new Pagination(Mode.NONE, rows);
		}

		public static Pagination offset(int rows) {
			return new Pagination(Mode.OFFSET, rows);
		}

		/** Páginas numeradas desde 0 con nombres de parámetro propios ({@code _page}/{@code _pageSize} en datos.gob.es). */
		public static Pagination pages(int rows, String pageParam, String rowsParam) {
			return new Pagination(Mode.PAGE, rows, pageParam, rowsParam);
		}

		public enum Mode {
			NONE, OFFSET, PAGE
		}
	}

}
