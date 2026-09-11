package es.zaragoza.observatory.urban.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToLongFunction;

import es.zaragoza.observatory.geo.Assignment;
import es.zaragoza.observatory.ingestion.Ingestion;
import es.zaragoza.observatory.ingestion.IngestionRunSummary;
import es.zaragoza.observatory.shared.DateWindow;
import es.zaragoza.observatory.shared.TerritorialTally;
import es.zaragoza.observatory.urban.Urban;
import es.zaragoza.observatory.urban.UrbanSources;
import es.zaragoza.observatory.urban.domain.AggregationAxis;
import es.zaragoza.observatory.urban.domain.AggregationBucket;
import es.zaragoza.observatory.urban.domain.PremisesQuery;
import es.zaragoza.observatory.urban.domain.PremisesRepository;

/**
 * Implementación de la superficie pública de {@code urban} (ADR-019 §9). Las dos medidas salen del mismo eje
 * territorial pero <b>cada una con su unidad y con la cobertura medida en esa unidad</b>: la de locales en
 * locales y la de licencias en licencias (ADR-016 §7).
 */
public class UrbanService implements Urban {

	private final PremisesRepository premises;
	private final Ingestion ingestion;

	public UrbanService(PremisesRepository premises, Ingestion ingestion) {
		this.premises = Objects.requireNonNull(premises);
		this.ingestion = Objects.requireNonNull(ingestion);
	}

	@Override
	public TerritorialTally premisesByDistrict(DateWindow window) {
		return tally(window, AggregationBucket::premises, premises::assignmentCounts);
	}

	@Override
	public TerritorialTally licencesByDistrict(DateWindow window) {
		return tally(window, AggregationBucket::licences, premises::licenceAssignmentCounts);
	}

	@Override
	public Instant ingestedAt() {
		return ingestion.lastSuccessful(UrbanSources.PREMISES).map(IngestionRunSummary::finishedAt).orElse(null);
	}

	/**
	 * El eje {@code DISTRICT} trae las dos cifras en cada grupo, así que la unidad la elige {@code value}; la
	 * cobertura la elige {@code coverage}, y las dos tienen que ir en la misma unidad o la columna mentiría.
	 */
	private TerritorialTally tally(DateWindow window, ToLongFunction<AggregationBucket> value,
			java.util.function.Function<PremisesQuery, Map<Assignment, Long>> coverage) {
		DateWindow bounds = window == null ? DateWindow.open() : window;
		PremisesQuery filters = new PremisesQuery(null, null, null, null, null, null, null, null, bounds.from(),
				bounds.to(), PremisesQuery.SortField.CREATED_AT, false, 0, PremisesQuery.MAX_SIZE).filtersOnly();

		Map<Integer, Long> byDistrict = new LinkedHashMap<>();
		for (AggregationBucket bucket : premises.aggregate(AggregationAxis.DISTRICT, filters)) {
			Integer districtId = districtId(bucket.key());
			if (districtId != null) {
				byDistrict.merge(districtId, value.applyAsLong(bucket), Long::sum);
			}
		}

		Map<Assignment, Long> counts = coverage.apply(filters);
		long total = counts.values().stream().mapToLong(Long::longValue).sum();
		long withoutPoint = counts.getOrDefault(Assignment.NO_POINT, 0L);
		long assigned = counts.getOrDefault(Assignment.RESOLVED, 0L) + counts.getOrDefault(Assignment.AMBIGUOUS, 0L);
		return new TerritorialTally(byDistrict, total, total - withoutPoint, assigned);
	}

	/** La clave del eje territorial es el id de junta; cualquier otra cosa no es una junta y no se cuenta. */
	private static Integer districtId(String key) {
		try {
			return Integer.valueOf(key);
		}
		catch (NumberFormatException | NullPointerException ex) {
			return null;
		}
	}

}
