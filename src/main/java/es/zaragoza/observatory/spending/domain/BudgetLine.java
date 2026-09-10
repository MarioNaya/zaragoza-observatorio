package es.zaragoza.observatory.spending.domain;

import java.time.LocalDate;

/**
 * Una partida presupuestaria <b>en una instantánea</b> (S3.2 §6). No es una entidad con vida propia: la misma
 * partida aparece una vez por cada fecha publicada, con los importes que tenía ese día.
 * <p>
 * La clave es {@code (snapshotDate, concept)}. El {@code id} que publica el origen no vale: es
 * {@code fecha + "-" + concepto}, o sea la misma clave con la fecha pegada delante. Y {@code concept} lleva
 * dentro los dos dígitos del ejercicio ({@code 26GUR--1513-6190325}), así que <b>no cruza de año</b>: de los
 * 22.838 conceptos distintos del histórico, ninguno aparece en dos ejercicios. Para una serie plurianual el eje
 * es {@code (organId, programmeId, itemId)}, y publicarlo exige antes decidir qué se hace con los años sin
 * programa (S3.2 §6 y §7).
 *
 * @param snapshotDate fecha de la instantánea a la que pertenece la fila
 * @param concept código de la partida dentro del ejercicio
 * @param areaId código de área de gasto
 * @param area nombre del área
 * @param chapterId capítulo económico (1..9). El endpoint se llama «gasto corriente» y trae también el capítulo
 * 6, inversiones reales, y los financieros: es el presupuesto de gastos entero (S3.2 §5)
 * @param chapter nombre del capítulo
 * @param programmeId código de programa presupuestario. <b>{@code null} en 2010-2014</b> y parcial en otros seis
 * ejercicios: la clasificación por programa no existía, y eso no es un fallo de carga (S3.2 §7)
 * @param programme nombre del programa
 * @param organId código de órgano gestor
 * @param organ nombre del órgano
 * @param itemId código del epígrafe económico, ya recortado: el origen lo rellena con espacios en los años
 * antiguos
 * @param item nombre del epígrafe
 * @param heading nombre de la partida, que es lo que dice <b>en qué</b> se gasta. {@code null} si se redactó
 * @param headingRedacted si el nombre se sustituyó por nombrar a una persona física (S3.2 §8)
 * @param amounts los ocho importes
 */
public record BudgetLine(LocalDate snapshotDate, String concept, String areaId, String area, Integer chapterId,
		String chapter, String programmeId, String programme, String organId, String organ, String itemId,
		String item, String heading, boolean headingRedacted, BudgetAmounts amounts) {

	public BudgetLine {
		if (snapshotDate == null) {
			throw new IllegalArgumentException("snapshotDate must not be null");
		}
		if (concept == null || concept.isBlank()) {
			throw new IllegalArgumentException("concept must not be blank");
		}
		concept = concept.strip();
		amounts = amounts == null ? BudgetAmounts.ZERO : amounts;
		if (headingRedacted && heading != null) {
			throw new IllegalArgumentException("a redacted heading must not carry the original text");
		}
	}

}
