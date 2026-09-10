package es.zaragoza.observatory.spending.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Una foto datada del presupuesto de gastos entero (S3.2 §1). Es <b>la unidad</b> de esta fuente: 140 fotos de
 * 2006-12-31 a 2026-08-31, con entre 722 y 1.303 partidas cada una.
 * <p>
 * Los totales se guardan materializados junto a la fila. No es denormalización por comodidad: son la serie del
 * producto —presupuestado, comprometido, ejecutado y pagado a lo largo del tiempo— y calcularla sumando 154.508
 * filas en cada petición sería pagar el histórico entero por una gráfica.
 *
 * @param date fecha de la instantánea, tal como la publica el censo
 * @param status situación de su lectura
 * @param attempts lecturas concluyentes intentadas
 * @param lastAttemptAt cuándo se intentó por última vez
 * @param nextAttemptAt cuándo toca la siguiente lectura; <b>{@code null} significa congelada</b>: una
 * instantánea publicada no se reescribe (S3.2 §3), así que se lee una vez y no se vuelve a pedir
 * @param lines partidas cargadas, {@code null} mientras no se haya leído
 * @param reportedCount {@code totalCount} que declaró la fuente, para poder contrastarlo con lo cargado
 * @param totals suma de los ocho importes de sus partidas
 * @param firstSeenAt primera vez que el censo la publicó
 * @param lastSeenAt última vez que el censo la publicó
 */
public record BudgetSnapshot(LocalDate date, SnapshotStatus status, int attempts, Instant lastAttemptAt,
		Instant nextAttemptAt, Integer lines, Integer reportedCount, BudgetAmounts totals, Instant firstSeenAt,
		Instant lastSeenAt) {

	/** El año del ejercicio al que pertenece la foto. */
	public int year() {
		return date.getYear();
	}

	/**
	 * Si lo cargado cuadra con lo que la fuente dijo que había. Un desajuste no invalida la instantánea —se
	 * publica igual— pero se ve.
	 */
	public boolean complete() {
		return lines != null && reportedCount != null && lines.equals(reportedCount);
	}

}
