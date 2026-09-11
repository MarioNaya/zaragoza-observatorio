package es.zaragoza.observatory.spending.domain;

import java.math.BigDecimal;

/**
 * Un grupo de una agregación de subvenciones. Sin indicadores compuestos ni etiquetas interpretativas (regla 6):
 * los recuentos, el dinero y los denominadores que hacen falta para leerlos.
 * <p>
 * {@code naturalPersonGrants} viaja en cada grupo por la misma razón por la que {@code internal} viaja en cada
 * grupo de las quejas (ADR-015): es el dato que permite a quien lee decidir si quiere separarlos, sin que el
 * observatorio lo decida por él. Y {@code beneficiaries} es el denominador que convierte un total en una
 * concentración: 30 M€ repartidos entre 40 beneficiarios y entre 4.000 no son lo mismo.
 *
 * @param key clave del grupo; {@code null} es un grupo legítimo (concesiones sin beneficiario o sin convocatoria)
 * @param label etiqueta legible que publica el origen, {@code null} si no la hay o si nombrarla nombraría a una
 * persona física
 * @param grants concesiones del grupo
 * @param granted importe concedido, sumado
 * @param naturalPersonGrants cuántas de esas concesiones van a una persona física
 * @param beneficiaries beneficiarios distintos del grupo
 */
public record GrantBucket(String key, String label, long grants, BigDecimal granted, long naturalPersonGrants,
		long beneficiaries) {

	public GrantBucket {
		granted = granted == null ? BigDecimal.ZERO : granted;
	}

}
