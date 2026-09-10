package es.zaragoza.observatory.spending.infrastructure.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import es.zaragoza.observatory.spending.domain.BudgetAmounts;
import es.zaragoza.observatory.spending.domain.BudgetAxis;
import es.zaragoza.observatory.spending.domain.BudgetBucket;
import es.zaragoza.observatory.spending.domain.BudgetLine;
import es.zaragoza.observatory.spending.domain.BudgetSnapshot;
import es.zaragoza.observatory.spending.domain.BudgetTotals;
import es.zaragoza.observatory.spending.domain.SnapshotStatus;

/** Cuerpos de respuesta del presupuesto de gastos (SPEC.md §4.7, S3.2). */
final class BudgetDtos {

	private BudgetDtos() {
	}

	/**
	 * Los cuatro importes que significan cosas distintas, más los cuatro de detalle. Van <b>los cuatro
	 * separados</b> y ninguno se llama «gasto» a secas: {@code creditFinal} es lo presupuestado,
	 * {@code committed} lo dispuesto, {@code obligations} el gasto ejecutado y {@code payments} lo pagado. En la
	 * última instantánea van de 1.094 M€ a 546 M€ (S3.2 §5, regla 33).
	 */
	record AmountsDto(BigDecimal creditInitial, BigDecimal creditModification, BigDecimal creditFinal,
			BigDecimal committed, BigDecimal obligations, BigDecimal payments, BigDecimal paymentsPending,
			BigDecimal creditRemaining) {

		static AmountsDto of(BudgetAmounts amounts) {
			return new AmountsDto(amounts.creditInitial(), amounts.creditModification(), amounts.creditFinal(),
					amounts.committed(), amounts.obligations(), amounts.payments(), amounts.paymentsPending(),
					amounts.creditRemaining());
		}
	}

	/**
	 * Una instantánea del censo.
	 *
	 * @param readStatus PENDING · LOADED · EMPTY · ABSENT. Durante la carga inicial la mayoría está en PENDING,
	 * y eso es parte de la respuesta: dice qué parte de la serie se está mirando
	 * @param complete si las partidas cargadas cuadran con el {@code totalCount} que declaró la fuente
	 * @param frozen si ya no se volverá a pedir. Una instantánea publicada no se reescribe (S3.2 §3)
	 * @param amounts los ocho importes de la foto, {@code null} mientras no esté cargada
	 */
	record SnapshotDto(LocalDate date, int year, String readStatus, Integer lines, Integer reportedCount,
			boolean complete, boolean frozen, Instant lastAttemptAt, AmountsDto amounts, Instant firstSeenAt,
			Instant lastSeenAt) {

		static SnapshotDto of(BudgetSnapshot snapshot) {
			boolean loaded = snapshot.status() == SnapshotStatus.LOADED;
			return new SnapshotDto(snapshot.date(), snapshot.year(), snapshot.status().name(), snapshot.lines(),
					snapshot.reportedCount(), snapshot.complete(), snapshot.nextAttemptAt() == null,
					snapshot.lastAttemptAt(), loaded ? AmountsDto.of(snapshot.totals()) : null,
					snapshot.firstSeenAt(), snapshot.lastSeenAt());
		}
	}

	/**
	 * Una partida en una instantánea.
	 *
	 * @param programmeId {@code null} en 2010-2014 y parcial en otros seis ejercicios: la clasificación por
	 * programa no existía (S3.2 §7)
	 * @param heading nombre de la partida; {@code null} si se redactó
	 * @param headingRedacted si el nombre se omitió por nombrar a una persona física (S3.2 §8)
	 */
	record LineDto(LocalDate snapshotDate, String concept, String areaId, String area, Integer chapterId,
			String chapter, String programmeId, String programme, String organId, String organ, String itemId,
			String item, String heading, boolean headingRedacted, AmountsDto amounts) {

		static LineDto of(BudgetLine line) {
			return new LineDto(line.snapshotDate(), line.concept(), line.areaId(), line.area(), line.chapterId(),
					line.chapter(), line.programmeId(), line.programme(), line.organId(), line.organ(),
					line.itemId(), line.item(), line.heading(), line.headingRedacted(),
					AmountsDto.of(line.amounts()));
		}
	}

	/**
	 * Una página de partidas. {@code snapshotDate} no es adorno: el listado describe <b>una</b> foto, y cuál es
	 * no puede quedar implícito.
	 */
	record LinePage(SpendingDtos.Source source, Instant ingestedAt, List<String> caveats, LocalDate snapshotDate,
			long total, int page, int size, List<LineDto> items) {
	}

	/**
	 * Un grupo de una agregación.
	 *
	 * @param snapshotDate instantánea de la que sale el grupo; en el eje por año, <b>cada grupo sale de una
	 * distinta</b>
	 */
	record BucketDto(String key, String label, LocalDate snapshotDate, long lines, AmountsDto amounts) {

		static BucketDto of(BudgetBucket bucket) {
			return new BucketDto(bucket.key(), bucket.label(), bucket.snapshotDate(), bucket.lines(),
					AmountsDto.of(bucket.amounts()));
		}
	}

	/**
	 * Una agregación.
	 *
	 * @param by eje pedido
	 * @param unit qué cuenta cada grupo: siempre partidas presupuestarias
	 * @param basis de dónde salen los grupos, que en el eje por año es «la última instantánea de cada año» y en
	 * los demás una sola foto
	 * @param lines partidas sumadas en todos los grupos
	 */
	record AggregationDto(String by, String unit, String basis, LocalDate snapshotDate, List<BucketDto> items,
			long lines) {

		static AggregationDto of(BudgetAxis axis, LocalDate snapshotDate, List<BucketDto> items) {
			String basis = axis == BudgetAxis.YEAR ? "closing-snapshot-per-year" : "single-snapshot";
			return new AggregationDto(axis.name().toLowerCase(Locale.ROOT), "lines", basis, snapshotDate, items,
					items.stream().mapToLong(BucketDto::lines).sum());
		}
	}

	/**
	 * El resumen del presupuesto.
	 *
	 * @param snapshots instantáneas censadas
	 * @param byReadStatus cuántas hay en cada situación de lectura
	 * @param pending las que faltan por leer; durante la carga inicial es la mayoría
	 * @param lines partidas cargadas
	 * @param latestLoaded la instantánea que contestan por defecto listado y agregaciones
	 * @param latestAmounts sus ocho importes
	 * @param redactedHeadings partidas cuyo nombre se omitió por nombrar a una persona física
	 * @param linesWithoutProgramme partidas sin programa presupuestario
	 */
	record SummaryDto(long snapshots, Map<String, Long> byReadStatus, long pending, long lines,
			LocalDate firstSnapshot, LocalDate lastSnapshot, LocalDate latestLoaded, AmountsDto latestAmounts,
			long redactedHeadings, long linesWithoutProgramme) {

		static SummaryDto of(BudgetTotals totals) {
			var byStatus = new LinkedHashMap<String, Long>();
			for (SnapshotStatus status : SnapshotStatus.values()) {
				byStatus.put(status.name(), totals.byStatus().getOrDefault(status, 0L));
			}
			return new SummaryDto(totals.snapshots(), byStatus, totals.pending(), totals.lines(),
					totals.firstSnapshot(), totals.lastSnapshot(), totals.latestLoaded(),
					AmountsDto.of(totals.latestTotals()), totals.redactedHeadings(),
					totals.linesWithoutProgramme());
		}
	}

}
