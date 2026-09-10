package es.zaragoza.observatory.spending.infrastructure.zaragoza;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Las reglas del presupuesto de gastos, medidas en S3.2, en un sitio donde se puedan leer antes de tocarlas.
 * <p>
 * La que importa de verdad: <b>toda petición manda {@code sort=id asc}</b>. El orden por defecto de este
 * endpoint sirve <b>dos ordenaciones distintas a la misma URL</b> —diez peticiones seguidas dieron cinco y
 * cinco—, así que paginar sin {@code sort} puede mezclar dos órdenes dentro del mismo barrido y perder y repetir
 * filas sin que nada lo diga. Es la misma familia que el orden no documentado de las quejas (S2.2), pero peor:
 * allí cambiaba entre dos días y aquí puede cambiar entre dos páginas.
 * <p>
 * Lo demás que hay que saber:
 * <ul>
 * <li>El censo {@code gasto-corriente/fecha.json} devuelve el envoltorio de la sede con un {@code result} que es
 * una <b>lista de URL</b>, no de objetos, e <b>ignora {@code rows}</b>: llega entero en una petición.</li>
 * <li>{@code gasto-corriente.json} <b>es</b> la instantánea más reciente, el mismo recurso que
 * {@code fecha/{fecha}.json} de esa fecha. No existe ningún recurso con el histórico junto.</li>
 * <li>{@code rows} topa en 500 y {@code start} <b>sí</b> se aplica, al revés que en OCDS.</li>
 * <li>Ni {@code ETag} ni {@code Last-Modified}: no hay firma de cambio que preguntar.</li>
 * <li>La fecha va en {@code yyyyMMdd} (S0.5), y es la única parte variable de la URL de una instantánea.</li>
 * </ul>
 */
public final class BudgetListing {

    /** Formato de fecha de esta fuente: {@code yyyyMMdd}, sin separadores (S0.5). */
	public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);

	/** Ordenación obligatoria del barrido. Ver la documentación de la clase antes de quitarla. */
	public static final String SORT = "sort";

	public static final String SORT_BY_ID = "id asc";

	/** Las URL del censo terminan en la fecha de la instantánea; lo demás no se acepta. */
	private static final Pattern TRAILING_DATE = Pattern.compile(".*/(\\d{8})/?$");

	/**
	 * La fecha que hay al final de una URL del censo, o {@code null} si esa URL no tiene la forma esperada. El
	 * censo es entrada externa: no se construye una ruta con lo que venga.
	 */
	public static LocalDate dateIn(String url) {
		if (url == null) {
			return null;
		}
		var matcher = TRAILING_DATE.matcher(url.strip());
		if (!matcher.matches()) {
			return null;
		}
		try {
			return LocalDate.parse(matcher.group(1), DATE);
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

	private BudgetListing() {
	}

}
