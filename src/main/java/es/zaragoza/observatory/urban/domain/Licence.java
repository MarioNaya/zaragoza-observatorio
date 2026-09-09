package es.zaragoza.observatory.urban.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Una licencia concedida sobre un local (S2.4 §7). Un local tiene 1,64 de media, hasta 12, y tres no tienen
 * ninguna.
 * <p>
 * La identidad dentro del local es <b>{@code (year, fileNumber)}</b>, la única combinación que no colisiona ni
 * una vez en las 69.631 licencias del registro. {@code displayOrder} es el {@code orden} del origen y
 * <b>no</b> sirve como clave: colisiona 950 veces en 788 locales.
 * <p>
 * Lo que no lleva es el {@code comments} del origen, que es donde estaban los 15 DNI (ADR-016 §3).
 *
 * @param year año del expediente ({@code id.anyo}). El rango observado va de 1913 a <b>2033</b>: hay una fecha
 * imposible y se guarda tal cual, porque corregirla sería inventarse el dato
 * @param fileNumber número de expediente ({@code id.expediente})
 * @param displayOrder orden con el que el origen las presenta; puede repetirse dentro de un local
 * @param typeId tipo de licencia ({@code tipo.id}), 47 valores
 * @param typeName nombre del tipo tal como lo publica el origen, con sus mayúsculas y sus solapes
 * @param resolvedOn fecha de resolución; el origen la da siempre a las 00:00:00, así que es una fecha y no un
 * instante. 341 de 69.631 no la traen
 * @param resolutionCode {@code idResolucion}, código sin taxonomía publicada
 * @param createdAt alta del registro en la plataforma
 * @param updatedAt última modificación del registro
 * @param deregisteredAt {@code fechaBaja}, que traen 215 licencias
 */
public record Licence(int year, long fileNumber, Integer displayOrder, int typeId, String typeName,
		LocalDate resolvedOn, Integer resolutionCode, Instant createdAt, Instant updatedAt,
		Instant deregisteredAt) {

	public Licence {
		if (fileNumber < 0) {
			throw new IllegalArgumentException("fileNumber must not be negative, got " + fileNumber);
		}
		typeName = typeName == null || typeName.isBlank() ? null : typeName.strip();
	}

	/** Clave de la licencia dentro de su local. */
	public String key() {
		return year + "/" + fileNumber;
	}

}
