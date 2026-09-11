package es.zaragoza.observatory.spending.infrastructure.zaragoza;

/**
 * Las reglas de las subvenciones, medidas en S3.3, en un sitio donde se puedan leer antes de tocarlas.
 * <p>
 * La que importa de verdad: <b>nunca se mandan {@code rows} y {@code page} juntos</b>. El desplazamiento lo
 * calcula {@code pageSize} (50 por defecto) aunque el tamaño lo haya fijado {@code rows}, así que
 * {@code rows=100&page=2} devuelve cien registros que <b>solapan cincuenta</b> con la página anterior, y nada en
 * la respuesta lo dice. Aquí se pagina siempre por <b>desplazamiento</b> ({@code start}), que funciona en las dos
 * versiones y no depende de ese cálculo.
 * <p>
 * Lo demás que hay que saber:
 * <ul>
 * <li><b>El censo completo es la versión vieja.</b> {@code ayuda-subvencion/resolucion} trae 46.925 concesiones
 * de 2013 a 2026 y {@code ayuda-subvencion-v2/concesion} es un <b>subconjunto estricto</b> (44.316, sin 2013 ni
 * 2014, cero registros propios). De la v2 solo se usa el enlace con el beneficiario, que la v1 no publica.</li>
 * <li><b>{@code rows} topa en 500 y {@code pageSize} no topa en nada</b>: el listado entero cabe en una
 * petición. No se hace: se pagina y se respeta la memoria (ADR-009).</li>
 * <li><b>{@code fl} recorta de verdad en la v1</b> —y eso es lo que permite no descargar nunca el nombre del
 * beneficiario (ADR-018 §3)— pero <b>recorta por subárbol y hasta dos niveles</b>: nombrar un hijo trae al padre
 * entero y vacía a los nietos. La salida es la <b>ruta con punto</b>, que el Swagger no documenta.</li>
 * <li><b>{@code fl} sobre la v2 devuelve {@code &#123;&#125;}</b>: no recorta, vacía la respuesta. Por eso la v2 se pide
 * entera y su página cruda no se guarda.</li>
 * <li><b>{@code sort} se aplica de verdad</b> (al revés que en OCDS) y un campo inexistente responde 400. Todo
 * barrido va con {@code sort=id asc}.</li>
 * <li><b>{@code q} (FIQL) filtra de verdad</b>, y {@code id=gt=N} es la marca de agua de la ingesta diaria: el
 * identificador es creciente.</li>
 * <li>Ni {@code ETag} ni {@code Last-Modified} ni campo de modificación en ningún recurso.</li>
 * </ul>
 */
public final class GrantListing {

	/** Ordenación obligatoria de todo barrido. Ver la documentación de la clase antes de quitarla. */
	public static final String SORT = "sort";

	public static final String SORT_BY_ID = "id asc";

	/** Proyección: el parámetro que hace posible no descargar el nombre de una persona física (ADR-018 §3). */
	public static final String FIELDS = "fl";

	/** Filtro FIQL. */
	public static final String FILTER = "q";

	/** Tamaño de página de la familia v2, que no usa {@code rows}. */
	public static final String PAGE_SIZE = "pageSize";

	/** Marca de agua de la ingesta incremental: solo lo que tenga identificador mayor que el guardado. */
	public static String above(long watermark) {
		return "id=gt=" + watermark;
	}

	private GrantListing() {
	}

}
