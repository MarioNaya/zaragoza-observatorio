package es.zaragoza.observatory.spending.domain;

import java.math.BigDecimal;

/**
 * Los ocho importes con los que el presupuesto describe una partida, en el orden del ciclo presupuestario
 * (S3.2 §5). No son ocho cifras sueltas: sobre la instantánea entera cuadran al céntimo tres identidades.
 *
 * <pre>
 * creditFinal      = creditInitial + creditModification
 * creditRemaining  = creditFinal   − obligations
 * paymentsPending  = obligations   − payments
 * </pre>
 *
 * Y las cuatro que significan algo distinto <b>no son intercambiables</b>: {@code creditFinal} es lo
 * presupuestado, {@code committed} lo dispuesto, {@code obligations} el <b>gasto ejecutado</b> (la obligación
 * reconocida) y {@code payments} lo efectivamente pagado. En la instantánea de agosto de 2026 van de 1.094 M€ a
 * 546 M€: quien confunda dos de ellas se equivoca por el doble.
 * <p>
 * Se publican las cuatro por separado y ninguna se llama «gasto» a secas (regla 6, regla 33). Este es además el
 * único sitio del producto donde hay dinero <b>pagado</b>: OCDS publica licitado y adjudicado y nada más
 * (ADR-017 §7).
 *
 * @param creditInitial crédito inicial aprobado
 * @param creditModification modificaciones de crédito del ejercicio
 * @param creditFinal crédito definitivo: lo presupuestado de verdad a esa fecha
 * @param committed gasto comprometido (dispuesto)
 * @param obligations obligación neta reconocida: <b>el gasto ejecutado</b>
 * @param payments pago neto: lo que ha salido de la caja
 * @param paymentsPending obligación pendiente de pago
 * @param creditRemaining remanente de crédito
 */
public record BudgetAmounts(BigDecimal creditInitial, BigDecimal creditModification, BigDecimal creditFinal,
		BigDecimal committed, BigDecimal obligations, BigDecimal payments, BigDecimal paymentsPending,
		BigDecimal creditRemaining) {

	public static final BudgetAmounts ZERO = new BudgetAmounts(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
			BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

	public BudgetAmounts {
		creditInitial = zeroIfNull(creditInitial);
		creditModification = zeroIfNull(creditModification);
		creditFinal = zeroIfNull(creditFinal);
		committed = zeroIfNull(committed);
		obligations = zeroIfNull(obligations);
		payments = zeroIfNull(payments);
		paymentsPending = zeroIfNull(paymentsPending);
		creditRemaining = zeroIfNull(creditRemaining);
	}

	/**
	 * Suma componente a componente. Aquí el cero <b>sí</b> es un valor legítimo, al revés que en un importe de
	 * contratación: una partida sin ejecutar tiene 0 € de obligación neta, y eso es un dato, no una ausencia.
	 */
	public BudgetAmounts plus(BudgetAmounts other) {
		if (other == null) {
			return this;
		}
		return new BudgetAmounts(creditInitial.add(other.creditInitial),
				creditModification.add(other.creditModification), creditFinal.add(other.creditFinal),
				committed.add(other.committed), obligations.add(other.obligations), payments.add(other.payments),
				paymentsPending.add(other.paymentsPending), creditRemaining.add(other.creditRemaining));
	}

	private static BigDecimal zeroIfNull(BigDecimal value) {
		return value == null ? BigDecimal.ZERO : value;
	}

}
