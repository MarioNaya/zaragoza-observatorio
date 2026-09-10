package es.zaragoza.observatory.spending.domain;

import java.time.LocalDate;

/**
 * Una instantánea a la que le toca lectura, con lo justo para decidir qué hacer con la respuesta.
 *
 * @param attempts intentos acumulados <b>antes</b> del que va a hacerse
 */
public record DueSnapshot(LocalDate date, SnapshotStatus status, int attempts) {
}
