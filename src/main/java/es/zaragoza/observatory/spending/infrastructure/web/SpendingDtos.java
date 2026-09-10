package es.zaragoza.observatory.spending.infrastructure.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import es.zaragoza.observatory.spending.domain.AggregationAxis;
import es.zaragoza.observatory.spending.domain.AggregationBucket;
import es.zaragoza.observatory.spending.domain.Award;
import es.zaragoza.observatory.spending.domain.Contract;
import es.zaragoza.observatory.spending.domain.ContractingProcess;
import es.zaragoza.observatory.spending.domain.Cpv;
import es.zaragoza.observatory.spending.domain.Money;
import es.zaragoza.observatory.spending.domain.PartyIdentity;
import es.zaragoza.observatory.spending.domain.ReleaseStatus;
import es.zaragoza.observatory.spending.domain.SpendingTotals;

/** Cuerpos de respuesta del módulo {@code spending} (SPEC.md §4.7). */
final class SpendingDtos {

	private SpendingDtos() {
	}

	record Source(String dataset, String url) {
	}

	record ApiPage<T>(Source source, Instant ingestedAt, List<String> caveats, long total, int page, int size,
			List<T> items) {
	}

	record ApiItem<T>(Source source, Instant ingestedAt, List<String> caveats, T item) {
	}

	/**
	 * Un proceso de contratación. Se llama proceso y no contrato a propósito (ADR-017 §9): 2.379 no tienen
	 * release, 1.560 tienen un contrato sin fecha de firma y 305 son licitaciones vivas.
	 *
	 * @param releaseStatus PENDING · PUBLISHED · EMPTY · ABSENT; un proceso ABSENT solo trae identificador
	 * @param inDocumentedList si aparece en el listado sin filtro que documenta la API
	 * @param stage {@code null} donde el documento no la sostiene, que es lo que hay que ver
	 * @param tenderAmount importe licitado; {@code null} sin release
	 * @param awardedAmount suma de las adjudicaciones activas; {@code null} si no hay ninguna
	 */
	record ProcessDto(String ocid, Integer fileNumber, boolean inDocumentedList, String releaseStatus,
			Instant publishedAt, String tags, String title, String description, String tenderStatus,
			String procurementMethod, String category, String awardCriteria, Integer numberOfTenderers,
			BigDecimal tenderAmount, BigDecimal tenderMinAmount, String currency, String procuringEntity,
			String stage, BigDecimal awardedAmount, List<CpvDto> cpv, List<AwardDto> awards,
			List<ContractDto> contracts, Instant firstSeenAt, Instant lastSeenAt) {

		static ProcessDto of(ContractingProcess process) {
			var tender = process.tender();
			return new ProcessDto(process.ocid(), process.fileNumber(), process.inDocumentedList(),
					process.releaseStatus().name(), process.publishedAt(), process.tags(), tender.title(),
					tender.description(), tender.status(), tender.procurementMethod(), tender.category(),
					tender.awardCriteria(), tender.numberOfTenderers(), amountOf(tender.value()),
					amountOf(tender.minValue()), currencyOf(tender.value()), process.procuringEntityName(),
					process.stage() == null ? null : process.stage().name(), process.awardedAmount(),
					process.cpvs().stream().map(CpvDto::of).toList(),
					process.awards().stream().map(AwardDto::of).toList(),
					process.contracts().stream().map(ContractDto::of).toList(), process.firstSeenAt(),
					process.lastSeenAt());
		}
	}

	record CpvDto(String code, String description, boolean main) {

		static CpvDto of(Cpv cpv) {
			return new CpvDto(cpv.code(), cpv.description(), cpv.main());
		}
	}

	/** Una adjudicación con sus adjudicatarias, ya sin el identificador que el origen publica con el NIF dentro. */
	record AwardDto(String id, String title, String description, String status, Instant awardedOn,
			BigDecimal amount, String currency, List<PartyDto> suppliers) {

		static AwardDto of(Award award) {
			return new AwardDto(award.awardId(), award.title(), award.description(), award.status(),
					award.awardedOn(), amountOf(award.value()), currencyOf(award.value()),
					award.parties().stream().map(PartyDto::of).toList());
		}
	}

	/**
	 * Una adjudicataria. De una persona física no se publica ni NIF ni nombre; se publica que lo es, para que su
	 * adjudicación no parezca un hueco (ADR-017 §2).
	 */
	record PartyDto(String taxId, String name, boolean naturalPerson) {

		static PartyDto of(PartyIdentity party) {
			return new PartyDto(party.taxId(), party.name(), party.naturalPerson());
		}
	}

	/** Un contrato. {@code emptyShell} marca los 1.560 que traen identificador y nada más (S3.1 §4). */
	record ContractDto(String id, String awardId, String title, String description, String status,
			Instant signedOn, BigDecimal amount, String currency, Instant periodStart, Instant periodEnd,
			boolean emptyShell) {

		static ContractDto of(Contract contract) {
			return new ContractDto(contract.contractId(), contract.awardId(), contract.title(),
					contract.description(), contract.status(), contract.signedOn(), amountOf(contract.value()),
					currencyOf(contract.value()), contract.periodStart(), contract.periodEnd(),
					contract.isEmptyShell());
		}
	}

	/**
	 * Un grupo de una agregación. Los dos importes van por separado y ninguno se llama «importe» a secas.
	 *
	 * @param total registros del grupo en la unidad que declara la respuesta
	 * @param withRelease procesos del grupo cuyo detalle se ha podido leer
	 * @param withoutStage procesos del grupo con release y sin etapa derivable
	 */
	record BucketDto(String key, String label, Integer year, long total, long processes, long awards,
			long withRelease, long withoutStage, BigDecimal tenderedAmount, BigDecimal awardedAmount) {

		static BucketDto of(AggregationBucket bucket, AggregationAxis axis) {
			return new BucketDto(bucket.key(), bucket.label(), bucket.year(), bucket.total(axis),
					bucket.processes(), bucket.awards(), bucket.withRelease(), bucket.withoutStage(),
					bucket.tenderedAmount(), bucket.awardedAmount());
		}
	}

	/**
	 * El resultado de una agregación con lo que la regla 7 exige al lado.
	 *
	 * @param unit qué cuenta cada grupo: {@code processes} o {@code awards}
	 * @param overlapping si un registro puede caer en varios grupos, y por tanto la suma no es el total
	 * @param matched suma de los grupos
	 * @param withoutRelease procesos del filtro cuyo detalle aún no está publicado
	 */
	record AggregationDto(String by, String unit, boolean overlapping, List<BucketDto> buckets, long matched,
			long total, long withoutRelease) {
	}

	/**
	 * El universo con sus huecos delante. No dice qué porcentaje del origen se ha ingerido, porque una cifra
	 * copiada de un spike envejece sin que nadie lo note: lo que se ve aquí es lo que se ha guardado.
	 */
	record SummaryDto(long processes, Map<String, Long> byReleaseStatus, long notInDocumentedList,
			long withoutStage, long emptyContracts, long awards, long naturalPersonSuppliers,
			BigDecimal tenderedAmount, BigDecimal awardedAmount, Instant earliestPublishedAt,
			Instant latestPublishedAt) {

		static SummaryDto of(SpendingTotals totals) {
			var byStatus = new LinkedHashMap<String, Long>();
			for (ReleaseStatus status : ReleaseStatus.values()) {
				byStatus.put(status.name(), totals.byReleaseStatus().getOrDefault(status, 0L));
			}
			return new SummaryDto(totals.processes(), byStatus, totals.notInDocumentedList(),
					totals.withoutStage(), totals.emptyContracts(), totals.awards(), totals.naturalPersonParties(),
					totals.tenderedAmount(), totals.awardedAmount(), totals.earliestPublishedAt(),
					totals.latestPublishedAt());
		}
	}

	private static BigDecimal amountOf(Money money) {
		return money == null ? null : money.amount();
	}

	private static String currencyOf(Money money) {
		return money == null ? null : money.currency();
	}

}
