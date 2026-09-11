package es.zaragoza.observatory.spending.infrastructure.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import es.zaragoza.observatory.spending.domain.Grant;
import es.zaragoza.observatory.spending.domain.GrantAxis;
import es.zaragoza.observatory.spending.domain.GrantBucket;
import es.zaragoza.observatory.spending.domain.GrantCall;
import es.zaragoza.observatory.spending.domain.GrantRead;
import es.zaragoza.observatory.spending.domain.GrantTotals;
import es.zaragoza.observatory.spending.infrastructure.web.SpendingDtos.Source;

/** Cuerpos de respuesta de las subvenciones (SPEC.md §4.7, S3.3, ADR-018). */
final class GrantDtos {

	private GrantDtos() {
	}

	/**
	 * Una concesión con lo que le prestan su convocatoria y su beneficiario.
	 *
	 * @param title para qué era la ayuda; el documento de identidad que el origen mete dentro va sustituido
	 * @param titleRedacted si hubo que sustituirlo (2.759 de 46.925)
	 * @param granted importe concedido. <b>No es dinero pagado</b>: es lo que se acordó conceder
	 * @param beneficiaryId identificador opaco del beneficiario; {@code null} en las concesiones de 2013 y 2014,
	 * que la versión nueva de la API no publica
	 * @param beneficiary razón social; <b>{@code null} siempre que sea una persona física</b> (ADR-018 §4)
	 * @param naturalPerson si el beneficiario es una persona física; {@code null} si no hay enlace
	 */
	record GrantDto(long id, Integer callId, String call, String line, String type, String title,
			boolean titleRedacted, String fileNumber, BigDecimal requested, BigDecimal granted, BigDecimal annual,
			Integer annuities, LocalDate requestedOn, LocalDate grantedOn, LocalDate agreedOn, String beneficiaryId,
			String beneficiary, Boolean naturalPerson, String classification) {

		static GrantDto of(GrantRead read) {
			Grant grant = read.grant();
			return new GrantDto(grant.id(), grant.callId(), read.call(), read.line(), read.type(), grant.title(),
					grant.titleRedacted(), grant.fileNumber(), grant.requested(), grant.granted(), grant.annual(),
					grant.annuities(), grant.requestedOn(), grant.grantedOn(), grant.agreedOn(),
					read.beneficiaryId(), read.beneficiary(), read.naturalPerson(), read.classification());
		}
	}

	/**
	 * Una convocatoria.
	 *
	 * @param budget lo que se puso a disposición, <b>no</b> lo repartido
	 * @param manager el cargo gestor, que es un rol y no una persona
	 */
	record CallDto(int id, String title, String fiscalYear, Boolean multiYear, LocalDate validFrom,
			LocalDate validTo, LocalDate submissionFrom, LocalDate submissionTo, BigDecimal budget,
			Integer advancePercentage, String managerId, String manager, String functionId, String function,
			String purposeId, String purpose, String typeId, String type, String lineId, String line,
			String scopeId, String scope, String areaId, String area) {

		static CallDto of(GrantCall call) {
			return new CallDto(call.id(), call.title(), call.fiscalYear(), call.multiYear(), call.validFrom(),
					call.validTo(), call.submissionFrom(), call.submissionTo(), call.budget(),
					call.advancePercentage(), call.managerId(), call.manager(), call.functionId(), call.function(),
					call.purposeId(), call.purpose(), call.typeId(), call.type(), call.lineId(), call.line(),
					call.scopeId(), call.scope(), call.areaId(), call.area());
		}
	}

	/**
	 * Un grupo de una agregación.
	 *
	 * @param label etiqueta del origen; <b>nula en el eje de beneficiario siempre que sea una persona
	 * física</b>: el grupo se cuenta, no se nombra
	 * @param naturalPersonGrants cuántas concesiones del grupo van a una persona física
	 * @param beneficiaries beneficiarios distintos del grupo: el denominador que convierte un total en una
	 * concentración
	 */
	record BucketDto(String key, String label, long grants, BigDecimal granted, long naturalPersonGrants,
			long beneficiaries) {

		static BucketDto of(GrantBucket bucket) {
			return new BucketDto(bucket.key(), bucket.label(), bucket.grants(), bucket.granted(),
					bucket.naturalPersonGrants(), bucket.beneficiaries());
		}
	}

	/** Una agregación con su eje y su unidad declarados (regla 8, ADR-016 §7). */
	record AggregationDto(String by, String unit, List<BucketDto> buckets) {

		static AggregationDto of(GrantAxis axis, List<BucketDto> buckets) {
			return new AggregationDto(axis.name().toLowerCase(Locale.ROOT), "concesiones", buckets);
		}
	}

	/**
	 * El resumen del universo con sus huecos.
	 *
	 * @param callBudget presupuesto declarado de las convocatorias; no es lo repartido
	 * @param withoutBeneficiary concesiones sin enlace de beneficiario (2013 y 2014)
	 * @param redactedTitles concesiones cuyo título llevaba un documento de identidad
	 * @param impossibleDates concesiones con fecha de concesión imposible
	 */
	record SummaryDto(long grants, BigDecimal granted, long calls, BigDecimal callBudget, long beneficiaries,
			long naturalPersonBeneficiaries, long naturalPersonGrants, Map<String, Long> byClassification,
			Integer firstYear, Integer lastYear, long withoutBeneficiary, long redactedTitles,
			long impossibleDates) {

		static SummaryDto of(GrantTotals totals) {
			return new SummaryDto(totals.grants(), totals.granted(), totals.calls(), totals.callBudget(),
					totals.beneficiaries(), totals.naturalPersonBeneficiaries(), totals.naturalPersonGrants(),
					totals.byClassification(), totals.firstYear(), totals.lastYear(), totals.withoutBeneficiary(),
					totals.redactedTitles(), totals.impossibleDates());
		}
	}

	/** Página de concesiones con su unidad declarada. */
	record GrantPage(Source source, Instant ingestedAt, List<String> caveats, String unit, long total, int page,
			int size, List<GrantDto> items) {
	}

}
