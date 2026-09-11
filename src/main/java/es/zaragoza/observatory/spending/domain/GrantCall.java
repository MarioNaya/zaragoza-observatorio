package es.zaragoza.observatory.spending.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Una convocatoria de subvenciones: la unidad de la que cuelgan las concesiones (S3.3 §8). Son 1.589, de 2014 a
 * 2026, con un presupuesto declarado de 357,6 M€.
 * <p>
 * <b>Su presupuesto no es lo repartido</b>: es lo que se puso a disposición. Lo repartido es la suma de los
 * importes concedidos de sus concesiones, y las dos cifras se publican por separado.
 * <p>
 * Los códigos y etiquetas de gestor, función, objeto, tipo, línea y ámbito son los que publica el origen y no se
 * traducen ni se agrupan (regla 6). El gestor es un <b>cargo</b> —«Concejal Presidente de la Junta Municipal de
 * Distrito de Torrero»—, no una persona: los 68 que publica el origen son roles.
 *
 * @param id identificador de la convocatoria en el origen
 * @param title nombre de la convocatoria
 * @param fiscalYear ejercicio ({@code ejercicioClave})
 * @param multiYear si es plurianual
 * @param validFrom inicio de vigencia
 * @param validTo fin de vigencia
 * @param submissionFrom inicio del plazo de presentación
 * @param submissionTo fin del plazo de presentación
 * @param budget presupuesto declarado de la convocatoria
 * @param advancePercentage porcentaje anticipado
 * @param managerId código del gestor
 * @param manager cargo gestor
 * @param functionId código de función
 * @param function función
 * @param purposeId código de objeto de la subvención
 * @param purpose objeto
 * @param typeId código de tipo de procedimiento
 * @param type tipo: concurrencia competitiva, directa o nominativa
 * @param lineId código de línea de financiación
 * @param line línea de financiación, que solo llega si la proyección la pide con ruta con punto (S3.3 §3)
 * @param scopeId código de ámbito de la línea
 * @param scope nombre del ámbito de la línea
 * @param areaId código del área del ámbito
 * @param area área del ámbito
 */
public record GrantCall(int id, String title, String fiscalYear, Boolean multiYear, LocalDate validFrom,
		LocalDate validTo, LocalDate submissionFrom, LocalDate submissionTo, BigDecimal budget,
		Integer advancePercentage, String managerId, String manager, String functionId, String function,
		String purposeId, String purpose, String typeId, String type, String lineId, String line, String scopeId,
		String scope, String areaId, String area) {

	public GrantCall {
		if (id <= 0) {
			throw new IllegalArgumentException("id must be positive");
		}
		if (GrantTitle.carriesIdentity(title)) {
			throw new IllegalArgumentException("a call title must not carry an identity document (ADR-018 §5)");
		}
	}

}
