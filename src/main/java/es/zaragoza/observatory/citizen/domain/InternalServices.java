package es.zaragoza.observatory.citizen.domain;

/**
 * El servicio {@code INTERNAL} del origen, que no son quejas ciudadanas (S0.3, S2.2, S2.3).
 * <p>
 * Lo que se sabe de él, todo medido, ninguno supuesto (regla 1):
 * <ul>
 * <li>lleva siempre {@code service_code} <b>2</b>: en 2025, los 247 registros con nombre {@code INTERNAL} tienen
 * código 2 y los 247 con código 2 se llaman {@code INTERNAL} (S2.3); sobre los 89.432 ingeridos, la agregación
 * por categoría devuelve una sola categoría con ese nombre y su clave es el 2 (2.449 registros, 2,7 %);</li>
 * <li>{@code open311/services.json} <b>no documenta</b> la categoría, ni por nombre ni por código;</li>
 * <li>Open311 <b>no publica</b> ninguno de esos registros, ni en su listado ni en su detalle, que responde 404:
 * es el propio ayuntamiento quien los deja fuera de su API ciudadana (S2.3).</li>
 * </ul>
 * Nada de eso los excluye por sí solo. La decisión (ADR-015) es <b>contarlos aparte, no esconderlos</b>: siguen
 * dentro de las cifras por defecto, cada grupo publica cuántos son y quien quiera puede pedirlos o quitarlos con
 * el filtro. Excluirlos en silencio sería que el observatorio decidiese qué es una queja (regla 6).
 * <p>
 * El criterio es el <b>código</b>, no el nombre: el código es la clave de la taxonomía del origen y el nombre es
 * texto libre que en esta misma fuente ya ha llegado con caracteres de control (ADR-011 §5).
 */
public final class InternalServices {

	/** Código del servicio {@code INTERNAL} en la taxonomía del origen. */
	public static final String CODE = "2";

	/** Nombre con el que el origen lo publica, para comprobarlo en los tests. */
	public static final String NAME = "INTERNAL";

	private InternalServices() {
	}

	public static boolean isInternal(String serviceCode) {
		return CODE.equals(serviceCode);
	}

	/** Qué hacer con estos registros en una consulta. Por defecto, {@link #INCLUDE}: cuentan. */
	public enum Filter {

		/** Cuentan, como cualquier otro registro. Es el comportamiento por defecto. */
		INCLUDE,
		/** Se dejan fuera. Lo pide quien lee, nunca lo decide el producto. */
		EXCLUDE,
		/** Solo ellos, para poder mirarlos. */
		ONLY

	}

}
