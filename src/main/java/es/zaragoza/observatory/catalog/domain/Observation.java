package es.zaragoza.observatory.catalog.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Resultado de observar una ficha (S1.1): qué se preguntó, a qué URL, cuándo y qué se obtuvo. Tres estados:
 * <em>medida</em> (sin error y con al menos {@code lastChange} o {@code records}), <em>fallida</em> (con
 * {@code error}: el método y la URL son los intentados) y <em>no observable</em> ({@link ObservationMethod#NOT_OBSERVABLE},
 * sin error ni medida). Un intento fallido es un hallazgo del monitor (servicio inexistente, redirección a HTML,
 * servicio de intranet), no un fallo del monitor.
 *
 * @param method método aplicado o intentado; {@code null} solo en fallos previos a elegir método
 * @param url URL consultada (la del {@code sort} en {@code API_MAX_DATE}), o {@code null}
 * @param observedAt instante de la observación
 * @param lastChange último cambio observado ({@code Last-Modified} o valor máximo del campo de fecha)
 * @param records registros observados ({@code totalCount}, {@code totalRecords} o {@code numberMatched})
 * @param detail qué se midió exactamente (campo de fecha usado, ficheros consultados, capa WFS)
 * @param error por qué no se pudo medir, o {@code null}
 */
public record Observation(ObservationMethod method, String url, Instant observedAt, Instant lastChange,
		Integer records, String detail, String error) {

	public Observation {
		Objects.requireNonNull(observedAt, "observedAt must not be null");
		if (method == null && error == null) {
			throw new IllegalArgumentException("an observation without method must carry an error");
		}
		if (error != null && (lastChange != null || records != null)) {
			throw new IllegalArgumentException("a failed observation cannot carry measures");
		}
		if (method == ObservationMethod.NOT_OBSERVABLE && (lastChange != null || records != null || error != null)) {
			throw new IllegalArgumentException("NOT_OBSERVABLE carries neither measures nor error");
		}
	}

	public static Observation measured(ObservationMethod method, String url, Instant observedAt, Instant lastChange,
			Integer records, String detail) {
		Objects.requireNonNull(method, "method must not be null");
		if (lastChange == null && records == null) {
			throw new IllegalArgumentException("a measured observation needs lastChange or records");
		}
		return new Observation(method, url, observedAt, lastChange, records, detail, null);
	}

	public static Observation failed(ObservationMethod attempted, String url, Instant observedAt, String error) {
		Objects.requireNonNull(error, "error must not be null");
		return new Observation(attempted, url, observedAt, null, null, null, error);
	}

	public static Observation notObservable(Instant observedAt) {
		return new Observation(ObservationMethod.NOT_OBSERVABLE, null, observedAt, null, null, null, null);
	}

	/** Hay al menos una medida y ningún error. */
	public boolean measured() {
		return error == null && (lastChange != null || records != null);
	}

	public boolean failed() {
		return error != null;
	}

}
