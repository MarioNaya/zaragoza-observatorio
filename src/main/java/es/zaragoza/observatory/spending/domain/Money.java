package es.zaragoza.observatory.spending.domain;

import java.math.BigDecimal;

/**
 * Un importe con su moneda, tal como lo publica el documento. En los 5.606 releases la moneda es siempre EUR,
 * pero se guarda igual: una cifra sin moneda es una cifra que hay que interpretar.
 * <p>
 * <b>Nunca es dinero pagado.</b> OCDS no publica ejecución ({@code contracts[].implementation} aparece en 0
 * documentos) ni previsión ({@code planning}, 0). Lo que hay es licitado y adjudicado, y quien los suma obtiene
 * una cifra que no significa nada: el histórico son 4.359 M€ licitados frente a 1.819 M€ adjudicados.
 */
public record Money(BigDecimal amount, String currency) {

	public Money {
		currency = currency == null || currency.isBlank() ? null : currency.strip();
	}

	/** {@code null} si el documento no trae importe; nunca un cero inventado. */
	public static Money of(BigDecimal amount, String currency) {
		return amount == null ? null : new Money(amount, currency);
	}

}
