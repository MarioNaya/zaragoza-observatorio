package es.zaragoza.observatory.spending.domain;

/**
 * Un proceso al que le toca que le pidan el detalle, con lo justo para decidir qué hacer con la respuesta: su
 * ocid y cuántas veces se ha intentado ya.
 *
 * @param attempts intentos acumulados <b>antes</b> del que va a hacerse
 */
public record DueRelease(String ocid, ReleaseStatus status, int attempts) {
}
