package es.zaragoza.observatory.spending.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Un proceso de contratación pública del ayuntamiento (S3.1, ADR-017).
 * <p>
 * La fila existe <b>desde que el ocid aparece en el censo</b>, tenga release o no. Es la diferencia con las
 * demás fuentes del proyecto: aquí el listado no trae los registros, solo sus identificadores, y el 29,7 % del
 * universo responde 404 al pedir el detalle. Esos 2.379 procesos no se descartan ni se cuentan como error: son
 * expedientes recientes cuyo release aún no se ha publicado, y esconderlos sería publicar un histórico que se
 * acaba en 2022 sin decirlo.
 * <p>
 * Lo que no lleva es tan deliberado como lo que lleva:
 * <ul>
 * <li><b>sin territorio</b>, medido sobre la fuente entera: cero caminos de localización en 184 caminos
 * distintos. No se geocodifica el título para inventar un lugar de ejecución (ADR-003 §1, §5);</li>
 * <li><b>sin el identificador crudo de las partes</b>, que lleva el NIF dentro (ADR-017 §2, {@link PartyIdentity});</li>
 * <li><b>sin importe pagado</b>, que esta fuente no publica (ADR-017 §6).</li>
 * </ul>
 *
 * @param ocid identificador OCDS completo, {@code ocds-1xraxc-{expediente}-ContractingProcess}
 * @param fileNumber número de expediente extraído del ocid; identifica sin colisiones, pero la secuencia tiene
 * 149 huecos, así que el universo se enumera y no se genera contando
 * @param inDocumentedList si el proceso aparece en el listado <b>sin filtro</b>, el que documenta la API. 2.271
 * de 8.001 no aparecen (S3.1 §1)
 * @param releaseStatus en qué situación está su detalle
 * @param attempts intentos de lectura del detalle
 * @param lastAttemptAt fecha del último intento; con {@code attempts} fija la cadencia de reintento
 * @param publishedAt {@code publishedDate} del package, que coincide con {@code releases[].date} en los 5.606
 * @param tags {@code releases[].tag[]} unido por comas; en los 5.606 documentos trae siempre un solo valor
 * @param tender la licitación, nunca {@code null} ({@link Tender#NONE} mientras no haya release)
 * @param stage etapa <b>solo donde el documento la sostiene</b>; {@code null} en 1.560 procesos completos
 */
public record ContractingProcess(String ocid, Integer fileNumber, boolean inDocumentedList,
		ReleaseStatus releaseStatus, int attempts, Instant lastAttemptAt, Instant publishedAt, String releaseId,
		String tags, String initiationType, Tender tender, String procuringEntityName, String procuringEntityId,
		Stage stage, List<Award> awards, List<Contract> contracts, List<Cpv> cpvs, Instant firstSeenAt,
		Instant lastSeenAt) {

	/** {@code ocds-1xraxc-8148-ContractingProcess}: el número del medio es el expediente. */
	private static final Pattern FILE_NUMBER = Pattern.compile("^ocds-[^-]+-(\\d+)-");

	public ContractingProcess {
		if (ocid == null || ocid.isBlank()) {
			throw new IllegalArgumentException("ocid must not be blank");
		}
		if (releaseStatus == null) {
			throw new IllegalArgumentException("releaseStatus must not be null (" + ocid + ")");
		}
		if (attempts < 0) {
			throw new IllegalArgumentException("attempts must not be negative (" + ocid + ")");
		}
		tender = tender == null ? Tender.NONE : tender;
		awards = awards == null ? List.of() : List.copyOf(awards);
		contracts = contracts == null ? List.of() : List.copyOf(contracts);
		cpvs = cpvs == null ? List.of() : List.copyOf(cpvs);
	}

	/** Un proceso recién censado: se sabe que existe y no se ha pedido su detalle. */
	public static ContractingProcess census(String ocid, Instant seenAt) {
		return new ContractingProcess(ocid, fileNumberOf(ocid), false, ReleaseStatus.PENDING, 0, null, null, null,
				null, null, Tender.NONE, null, null, null, List.of(), List.of(), List.of(), seenAt, seenAt);
	}

	/** El número de expediente que lleva el ocid, o {@code null} si el ocid no tiene esa forma. */
	public static Integer fileNumberOf(String ocid) {
		Matcher matcher = FILE_NUMBER.matcher(ocid == null ? "" : ocid);
		if (!matcher.find()) {
			return null;
		}
		try {
			return Integer.valueOf(matcher.group(1));
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	/**
	 * Suma de los importes de las adjudicaciones <b>activas</b>. Las {@code unsuccessful} y las {@code pending}
	 * no se suman: son adjudicaciones que no adjudicaron. {@code null} si el proceso no tiene ninguna activa con
	 * importe, nunca un cero que parezca un contrato de cero euros.
	 */
	public BigDecimal awardedAmount() {
		BigDecimal total = null;
		for (Award award : awards) {
			if (!award.isActive() || award.value() == null || award.value().amount() == null) {
				continue;
			}
			total = total == null ? award.value().amount() : total.add(award.value().amount());
		}
		return total;
	}

	public boolean anyContractSigned() {
		return contracts.stream().anyMatch(Contract::isSigned);
	}

	/** Contratos que son una cáscara con identificador y nada más (S3.1 §4). */
	public long emptyContracts() {
		return contracts.stream().filter(Contract::isEmptyShell).count();
	}

	public boolean hasRelease() {
		return releaseStatus == ReleaseStatus.PUBLISHED;
	}

	public ContractingProcess seen(Instant firstSeenAt, Instant lastSeenAt) {
		return new ContractingProcess(ocid, fileNumber, inDocumentedList, releaseStatus, attempts, lastAttemptAt,
				publishedAt, releaseId, tags, initiationType, tender, procuringEntityName, procuringEntityId, stage,
				awards, contracts, cpvs, firstSeenAt, lastSeenAt);
	}

}
