package es.zaragoza.observatory.spending.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import es.zaragoza.observatory.spending.domain.AggregationAxis;
import es.zaragoza.observatory.spending.domain.AggregationBucket;
import es.zaragoza.observatory.spending.domain.ContractingProcess;
import es.zaragoza.observatory.spending.domain.ContractingProcessRepository;
import es.zaragoza.observatory.spending.domain.DueRelease;
import es.zaragoza.observatory.spending.domain.ProcessPage;
import es.zaragoza.observatory.spending.domain.ProcessQuery;
import es.zaragoza.observatory.spending.domain.ReleaseContent;
import es.zaragoza.observatory.spending.domain.ReleaseStatus;
import es.zaragoza.observatory.spending.domain.SpendingTotals;
import es.zaragoza.observatory.spending.domain.Stage;

/**
 * Repositorio de mentira para probar la <b>escritura</b> de {@code ReadReleases} sin base de datos: qué se
 * guarda, con qué estado y cuándo toca el siguiente intento. La lectura se prueba contra PostgreSQL real en
 * {@code SpendingIntegrationTests}, que es donde vive el SQL.
 */
public class RecordingProcesses implements ContractingProcessRepository {

	public final List<DueRelease> pending = new ArrayList<>();

	public final Map<String, Recorded> recorded = new LinkedHashMap<>();

	public final Map<String, Instant> failed = new LinkedHashMap<>();

	/** El censo, para las pruebas de {@code CheckDocumentedListing}. */
	public final Set<String> census = new LinkedHashSet<>();

	/** Los ocids que quedaron marcados como presentes en el listado documentado. */
	public final Set<String> documented = new LinkedHashSet<>();

	/** Lo que se guardó de una lectura concluyente. */
	public record Recorded(ReleaseStatus status, ReleaseContent content, Stage stage, int attempts,
			Instant attemptedAt, Instant nextAttemptAt) {
	}

	@Override
	public List<DueRelease> due(Instant now, int limit) {
		return pending.stream().limit(limit).toList();
	}

	@Override
	public void recordRelease(String ocid, ReleaseStatus status, ReleaseContent content, Stage stage, int attempts,
			Instant attemptedAt, Instant nextAttemptAt) {
		recorded.put(ocid, new Recorded(status, content, stage, attempts, attemptedAt, nextAttemptAt));
	}

	@Override
	public void recordFailedAttempt(String ocid, Instant attemptedAt, Instant nextAttemptAt) {
		failed.put(ocid, nextAttemptAt);
	}

	// --- lo demás no se usa en estos tests ----------------------------------------------------------------

	@Override
	public int upsertCensus(List<String> ocids, Instant seenAt) {
		throw new UnsupportedOperationException();
	}

	@Override
	public int markDocumentedList(Set<String> listed) {
		documented.clear();
		listed.stream().filter(census::contains).forEach(documented::add);
		return documented.size();
	}

	@Override
	public Set<String> allOcids() {
		return census;
	}

	@Override
	public long count() {
		throw new UnsupportedOperationException();
	}

	@Override
	public long count(ProcessQuery filters) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Optional<ContractingProcess> byOcid(String ocid) {
		throw new UnsupportedOperationException();
	}

	@Override
	public ProcessPage search(ProcessQuery query) {
		throw new UnsupportedOperationException();
	}

	@Override
	public List<AggregationBucket> aggregate(AggregationAxis axis, ProcessQuery filters) {
		throw new UnsupportedOperationException();
	}

	@Override
	public SpendingTotals totals(ProcessQuery filters) {
		throw new UnsupportedOperationException();
	}

}
