package es.zaragoza.observatory.spending.domain;

import java.time.LocalDate;

/**
 * Un grupo de una agregación del presupuesto. Sin indicadores compuestos ni etiquetas interpretativas (regla 6):
 * las cuatro cifras del ciclo presupuestario, el número de partidas y la instantánea de la que salen.
 * <p>
 * {@code snapshotDate} viaja en cada grupo porque en el eje por año <b>cada grupo sale de una foto distinta</b>,
 * y sin decirlo dos años parecerían igual de cerrados cuando el último está a medio ejercicio.
 *
 * @param key clave del grupo; {@code null} es un grupo legítimo (partidas sin programa)
 * @param label etiqueta legible que publica el origen, {@code null} si no la hay
 * @param snapshotDate instantánea de la que sale el grupo
 * @param lines partidas del grupo
 * @param amounts los ocho importes sumados
 */
public record BudgetBucket(String key, String label, LocalDate snapshotDate, long lines, BudgetAmounts amounts) {

	public BudgetBucket {
		amounts = amounts == null ? BudgetAmounts.ZERO : amounts;
	}

}
