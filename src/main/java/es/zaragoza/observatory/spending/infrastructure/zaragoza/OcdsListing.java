package es.zaragoza.observatory.spending.infrastructure.zaragoza;

/**
 * Las reglas del listado de OCDS, medidas en S3.1 §1, en un sitio donde se puedan leer antes de tocarlas.
 * <p>
 * La única que importa de verdad: <b>{@link #AFTER_SWITCH} no es una fecha</b>. El parámetro {@code after} del
 * listado no filtra por fecha de publicación; es un interruptor. Por debajo de un umbral situado entre el 1 y el
 * 2 de enero de 2017 no hace nada, y por encima el valor da igual: {@code after=2030-01-01} devuelve procesos
 * publicados en 2008. Lo que hace es abrir los <b>2.271 procesos que el listado sin filtro esconde</b>, el 28,4 %
 * del histórico.
 * <p>
 * Se manda un valor claramente futuro para no depender de dónde esté el umbral, y en cada ingesta se comprueba
 * que el listado sin filtro sigue siendo subconjunto del ampliado ({@code CheckDocumentedListing}). Si algún día
 * deja de serlo, la ingesta falla en vez de adivinar.
 * <p>
 * Lo demás que hay que saber para no perder el tiempo:
 * <ul>
 * <li>{@code start} se ignora: {@code start=1000} devuelve lo mismo que {@code start=0}. No hay paginación por
 * desplazamiento; el listado se pide entero.</li>
 * <li>{@code sort=id desc} <b>se acepta y no se aplica</b>: devuelve 8148, 8142, 8136, 8134, 8133, 8139. Es la
 * familia de {@code q=junta.id==N} (S2.1), {@code status=rejected} (S2.2) y {@code removeproperties} (S2.4). El
 * orden se pone en casa.</li>
 * <li>El formato de fecha admitido es solo {@code yyyy-MM-ddTHH:mm:ssZ}; con offset, compacto o sin hora
 * responde 400.</li>
 * <li>El listado publica <b>solo {@code ocid} e {@code id}</b>: ni fecha, ni importe, ni objeto. Todo lo demás
 * está en el detalle, y por eso el histórico son 8.001 peticiones.</li>
 * <li>{@code before} sí filtra por fecha de publicación, pero solo el conjunto base: los 2.271 ocultos entran o
 * no entran y ningún filtro de fecha los toca. Por eso <b>ninguno de los dos parámetros sirve de marca de
 * agua</b> y la ingesta no manda {@code before}.</li>
 * </ul>
 */
public final class OcdsListing {

	/** Nombre del parámetro interruptor. */
	public static final String AFTER = "after";

	/**
	 * El valor del interruptor. <b>No es una fecha</b>: no se «actualiza» ni se calcula a partir de hoy. Ver la
	 * documentación de la clase antes de cambiarlo.
	 */
	public static final String AFTER_SWITCH = "2030-01-01T00:00:00Z";

	private OcdsListing() {
	}

}
