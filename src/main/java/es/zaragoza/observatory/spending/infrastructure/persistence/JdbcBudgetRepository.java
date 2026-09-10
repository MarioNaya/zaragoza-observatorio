package es.zaragoza.observatory.spending.infrastructure.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import es.zaragoza.observatory.spending.domain.BudgetAmounts;
import es.zaragoza.observatory.spending.domain.BudgetAxis;
import es.zaragoza.observatory.spending.domain.BudgetBucket;
import es.zaragoza.observatory.spending.domain.BudgetLine;
import es.zaragoza.observatory.spending.domain.BudgetLinePage;
import es.zaragoza.observatory.spending.domain.BudgetQuery;
import es.zaragoza.observatory.spending.domain.BudgetRepository;
import es.zaragoza.observatory.spending.domain.BudgetSnapshot;
import es.zaragoza.observatory.spending.domain.BudgetSnapshotRead;
import es.zaragoza.observatory.spending.domain.BudgetTotals;
import es.zaragoza.observatory.spending.domain.DueSnapshot;
import es.zaragoza.observatory.spending.domain.SnapshotStatus;

/**
 * Adaptador de persistencia del presupuesto, sobre SQL directo, por lo mismo que el de la contratación: alta por
 * lotes de miles de filas y agregaciones que en JPQL no existen.
 * <p>
 * Tres decisiones que no se ven en la firma de los métodos:
 * <ul>
 * <li><b>Las partidas de una instantánea se reemplazan enteras</b> cuando la lectura trae contenido: la fuente
 * las publica completas, así que lo que desaparece ha desaparecido. Y como la clave es
 * {@code (fecha, concepto)}, releer una instantánea es idempotente (regla 5).</li>
 * <li><b>Una lectura sin contenido no borra las partidas anteriores.</b> Un 404 sobre una instantánea ya cargada
 * es raro y probablemente temporal; vaciarla convertiría un tropiezo de la fuente en pérdida de datos.</li>
 * <li><b>Los totales se recalculan al cargar</b>, no en cada consulta: son la serie del producto y sumarlos
 * sobre 154.508 filas cada vez sería pagar el histórico entero por una gráfica.</li>
 * </ul>
 */
@Repository
class JdbcBudgetRepository implements BudgetRepository {

	private static final String SNAPSHOT_COLUMNS = """
			snapshot_date, read_status, attempts, last_attempt_at, next_attempt_at, lines, reported_count,
			credit_initial, credit_modification, credit_final, committed, obligations, payments,
			payments_pending, credit_remaining, first_seen_at, last_seen_at
			""";

	private static final String LINE_COLUMNS = """
			snapshot_date, concept, area_id, area, chapter_id, chapter, programme_id, programme, organ_id, organ,
			item_id, item, heading, heading_redacted, credit_initial, credit_modification, credit_final,
			committed, obligations, payments, payments_pending, credit_remaining
			""";

	private static final String UPSERT_CENSUS = """
			INSERT INTO spending_budget_snapshot (snapshot_date, read_status, next_attempt_at, first_seen_at,
			    last_seen_at)
			VALUES (?, 'PENDING', ?, ?, ?)
			ON CONFLICT (snapshot_date) DO UPDATE SET last_seen_at = EXCLUDED.last_seen_at
			""";

	/** Con contenido: estado, contabilidad del intento y los totales materializados de la instantánea. */
	private static final String UPDATE_LOADED = """
			UPDATE spending_budget_snapshot SET
			    read_status = ?, attempts = ?, last_attempt_at = ?, next_attempt_at = ?, lines = ?,
			    reported_count = ?, credit_initial = ?, credit_modification = ?, credit_final = ?,
			    committed = ?, obligations = ?, payments = ?, payments_pending = ?, credit_remaining = ?
			WHERE snapshot_date = ?
			""";

	/** Sin contenido: se actualiza la situación y la contabilidad, y las partidas conocidas se conservan. */
	private static final String UPDATE_WITHOUT_CONTENT = """
			UPDATE spending_budget_snapshot SET read_status = ?, attempts = ?, last_attempt_at = ?,
			    next_attempt_at = ?
			WHERE snapshot_date = ?
			""";

	private static final String INSERT_LINE = """
			INSERT INTO spending_budget_line (snapshot_date, concept, area_id, area, chapter_id, chapter,
			    programme_id, programme, organ_id, organ, item_id, item, heading, heading_redacted,
			    credit_initial, credit_modification, credit_final, committed, obligations, payments,
			    payments_pending, credit_remaining)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""";

	private final JdbcTemplate jdbc;

	JdbcBudgetRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	// --- escritura ---------------------------------------------------------------------------------------

	@Override
	@Transactional
	public int upsertCensus(List<LocalDate> dates, Instant seenAt) {
		if (dates.isEmpty()) {
			return 0;
		}
		Timestamp seen = Timestamp.from(seenAt);
		jdbc.batchUpdate(UPSERT_CENSUS, dates, dates.size(), (PreparedStatement ps, LocalDate date) -> {
			ps.setObject(1, date);
			// Una instantánea recién censada toca ya: nunca se ha pedido.
			ps.setTimestamp(2, seen);
			ps.setTimestamp(3, seen);
			ps.setTimestamp(4, seen);
		});
		return dates.size();
	}

	@Override
	@Transactional(readOnly = true)
	public List<DueSnapshot> due(Instant now, int limit) {
		// De la más reciente a la más antigua: durante la carga inicial, la primera que existe es justo la que
		// contestan por defecto el listado y las agregaciones.
		return jdbc.query("""
				SELECT snapshot_date, read_status, attempts FROM spending_budget_snapshot
				WHERE next_attempt_at IS NOT NULL AND next_attempt_at <= ?
				ORDER BY next_attempt_at, snapshot_date DESC
				LIMIT ?
				""", (ResultSet rs, int row) -> new DueSnapshot(rs.getObject("snapshot_date", LocalDate.class),
				SnapshotStatus.valueOf(rs.getString("read_status")), rs.getInt("attempts")), Timestamp.from(now),
				limit);
	}

	@Override
	@Transactional
	public void recordSnapshot(BudgetSnapshotRead read, int attempts, Instant attemptedAt, Instant nextAttemptAt) {
		LocalDate date = read.date();
		if (read.status() != SnapshotStatus.LOADED) {
			jdbc.update(UPDATE_WITHOUT_CONTENT, ps -> {
				ps.setString(1, read.status().name());
				ps.setInt(2, attempts);
				ps.setTimestamp(3, Timestamp.from(attemptedAt));
				setTimestamp(ps, 4, nextAttemptAt);
				ps.setObject(5, date);
			});
			return;
		}
		List<BudgetLine> lines = read.lines();
		BudgetAmounts totals = lines.stream().map(BudgetLine::amounts).reduce(BudgetAmounts.ZERO,
				BudgetAmounts::plus);
		jdbc.update(UPDATE_LOADED, ps -> {
			ps.setString(1, SnapshotStatus.LOADED.name());
			ps.setInt(2, attempts);
			ps.setTimestamp(3, Timestamp.from(attemptedAt));
			setTimestamp(ps, 4, nextAttemptAt);
			ps.setInt(5, lines.size());
			if (read.reportedCount() < 0) {
				ps.setNull(6, Types.INTEGER);
			}
			else {
				ps.setInt(6, read.reportedCount());
			}
			setAmounts(ps, 7, totals);
			ps.setObject(15, date);
		});
		jdbc.update("DELETE FROM spending_budget_line WHERE snapshot_date = ?", date);
		jdbc.batchUpdate(INSERT_LINE, lines, Math.max(lines.size(), 1), (PreparedStatement ps, BudgetLine line) -> {
			ps.setObject(1, line.snapshotDate());
			ps.setString(2, line.concept());
			ps.setString(3, line.areaId());
			ps.setString(4, line.area());
			setInteger(ps, 5, line.chapterId());
			ps.setString(6, line.chapter());
			ps.setString(7, line.programmeId());
			ps.setString(8, line.programme());
			ps.setString(9, line.organId());
			ps.setString(10, line.organ());
			ps.setString(11, line.itemId());
			ps.setString(12, line.item());
			// De una partida que nombra a una persona física no entra el texto; la restricción de la tabla lo
			// impone también desde abajo (S3.2 §8, regla 22).
			ps.setString(13, line.heading());
			ps.setBoolean(14, line.headingRedacted());
			setAmounts(ps, 15, line.amounts());
		});
	}

	@Override
	@Transactional
	public void recordFailedAttempt(LocalDate date, Instant attemptedAt, Instant nextAttemptAt) {
		jdbc.update("""
				UPDATE spending_budget_snapshot SET last_attempt_at = ?, next_attempt_at = ?
				WHERE snapshot_date = ?
				""", ps -> {
			ps.setTimestamp(1, Timestamp.from(attemptedAt));
			setTimestamp(ps, 2, nextAttemptAt);
			ps.setObject(3, date);
		});
	}

	// --- lectura -----------------------------------------------------------------------------------------

	@Override
	@Transactional(readOnly = true)
	public Optional<LocalDate> latestCensused() {
		return Optional.ofNullable(jdbc.queryForObject("SELECT max(snapshot_date) FROM spending_budget_snapshot",
				LocalDate.class));
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<LocalDate> latestLoaded() {
		return Optional.ofNullable(jdbc.queryForObject(
				"SELECT max(snapshot_date) FROM spending_budget_snapshot WHERE read_status = 'LOADED'",
				LocalDate.class));
	}

	@Override
	@Transactional(readOnly = true)
	public List<BudgetSnapshot> snapshots() {
		return jdbc.query("SELECT " + SNAPSHOT_COLUMNS
				+ " FROM spending_budget_snapshot ORDER BY snapshot_date DESC", JdbcBudgetRepository::mapSnapshot);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<BudgetSnapshot> snapshot(LocalDate date) {
		List<BudgetSnapshot> found = jdbc.query("SELECT " + SNAPSHOT_COLUMNS
				+ " FROM spending_budget_snapshot WHERE snapshot_date = ?", JdbcBudgetRepository::mapSnapshot, date);
		return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
	}

	@Override
	@Transactional(readOnly = true)
	public BudgetLinePage searchLines(BudgetQuery query) {
		LocalDate date = query.snapshotDate() != null ? query.snapshotDate() : latestLoaded().orElse(null);
		if (date == null) {
			return new BudgetLinePage(null, List.of(), 0, query.page(), query.size());
		}
		var where = new Where(query.on(date));
		Long total = jdbc.queryForObject("SELECT count(*) FROM spending_budget_line" + where.clause(), Long.class,
				where.args());
		String direction = query.ascending() ? "ASC" : "DESC";
		// Desempate estable por concepto: sin él, dos partidas con el mismo importe pueden cambiar de página
		// entre peticiones, que es justo el defecto que tiene la fuente (S3.2 §2).
		String sql = "SELECT " + LINE_COLUMNS + " FROM spending_budget_line" + where.clause() + " ORDER BY "
				+ column(query.sort()) + " " + direction + " NULLS LAST, concept ASC LIMIT ? OFFSET ?";
		var args = new ArrayList<>(Arrays.asList(where.args()));
		args.add(query.size());
		args.add(query.page() * query.size());
		List<BudgetLine> items = jdbc.query(sql, JdbcBudgetRepository::mapLine, args.toArray());
		return new BudgetLinePage(date, items, total == null ? 0 : total, query.page(), query.size());
	}

	@Override
	@Transactional(readOnly = true)
	public List<BudgetBucket> aggregate(BudgetAxis axis, BudgetQuery filters) {
		if (axis == BudgetAxis.YEAR) {
			return aggregateByYear(filters);
		}
		LocalDate date = filters.snapshotDate() != null ? filters.snapshotDate() : latestLoaded().orElse(null);
		if (date == null) {
			return List.of();
		}
		var where = new Where(filters.on(date));
		String keyColumn = keyColumn(axis);
		String labelColumn = labelColumn(axis);
		String sql = """
				SELECT %s AS bucket_key, max(%s) AS bucket_label, count(*) AS lines, %s
				FROM spending_budget_line%s
				GROUP BY %s
				ORDER BY sum(obligations) DESC NULLS LAST, bucket_key
				""".formatted(keyColumn, labelColumn, SUM_COLUMNS, where.clause(), keyColumn);
		return jdbc.query(sql, (ResultSet rs, int row) -> new BudgetBucket(rs.getString("bucket_key"),
				rs.getString("bucket_label"), date, rs.getLong("lines"), amounts(rs)), where.args());
	}

	/**
	 * La serie: un grupo por ejercicio con <b>la última instantánea cargada de cada año</b>. Sumar las doce fotos
	 * de un año contaría el mismo euro doce veces, porque cada foto es acumulada desde enero (S3.2 §9).
	 */
	private List<BudgetBucket> aggregateByYear(BudgetQuery filters) {
		var where = new Where(filters.on(null));
		String sql = """
				WITH closing AS (
				    SELECT DISTINCT ON (extract(year from snapshot_date)) snapshot_date
				    FROM spending_budget_snapshot
				    WHERE read_status = 'LOADED'
				    ORDER BY extract(year from snapshot_date), snapshot_date DESC
				)
				SELECT l.snapshot_date, count(*) AS lines, %s
				FROM spending_budget_line l JOIN closing c ON c.snapshot_date = l.snapshot_date%s
				GROUP BY l.snapshot_date
				ORDER BY l.snapshot_date
				""".formatted(SUM_COLUMNS, where.clause(" AND "));
		return jdbc.query(sql, (ResultSet rs, int row) -> {
			LocalDate date = rs.getObject("snapshot_date", LocalDate.class);
			return new BudgetBucket(String.valueOf(date.getYear()), null, date, rs.getLong("lines"), amounts(rs));
		}, where.args());
	}

	@Override
	@Transactional(readOnly = true)
	public BudgetTotals totals() {
		Map<SnapshotStatus, Long> byStatus = new EnumMap<>(SnapshotStatus.class);
		jdbc.query("SELECT read_status, count(*) AS n FROM spending_budget_snapshot GROUP BY read_status",
				(RowCallbackHandler) rs -> byStatus.put(SnapshotStatus.valueOf(rs.getString("read_status")),
						rs.getLong("n")));
		long snapshots = byStatus.values().stream().mapToLong(Long::longValue).sum();
		LocalDate first = jdbc.queryForObject("SELECT min(snapshot_date) FROM spending_budget_snapshot",
				LocalDate.class);
		LocalDate last = jdbc.queryForObject("SELECT max(snapshot_date) FROM spending_budget_snapshot",
				LocalDate.class);
		LocalDate latestLoaded = latestLoaded().orElse(null);
		BudgetAmounts latestTotals = latestLoaded == null ? BudgetAmounts.ZERO
				: snapshot(latestLoaded).map(BudgetSnapshot::totals).orElse(BudgetAmounts.ZERO);
		Long lines = jdbc.queryForObject("SELECT count(*) FROM spending_budget_line", Long.class);
		Long redacted = jdbc.queryForObject(
				"SELECT count(*) FROM spending_budget_line WHERE heading_redacted", Long.class);
		Long withoutProgramme = jdbc.queryForObject(
				"SELECT count(*) FROM spending_budget_line WHERE programme_id IS NULL", Long.class);
		return new BudgetTotals(snapshots, byStatus, lines == null ? 0 : lines, first, last, latestLoaded,
				latestTotals, redacted == null ? 0 : redacted, withoutProgramme == null ? 0 : withoutProgramme);
	}

	// --- SQL -------------------------------------------------------------------------------------------

	private static final String SUM_COLUMNS = """
			sum(credit_initial) AS credit_initial, sum(credit_modification) AS credit_modification,
			sum(credit_final) AS credit_final, sum(committed) AS committed, sum(obligations) AS obligations,
			sum(payments) AS payments, sum(payments_pending) AS payments_pending,
			sum(credit_remaining) AS credit_remaining
			""";

	private static String keyColumn(BudgetAxis axis) {
		return switch (axis) {
			case CHAPTER -> "chapter_id::text";
			case AREA -> "area_id";
			case PROGRAMME -> "programme_id";
			case ORGAN -> "organ_id";
			case YEAR -> throw new IllegalArgumentException("YEAR se agrega aparte");
		};
	}

	private static String labelColumn(BudgetAxis axis) {
		return switch (axis) {
			case CHAPTER -> "chapter";
			case AREA -> "area";
			case PROGRAMME -> "programme";
			case ORGAN -> "organ";
			case YEAR -> throw new IllegalArgumentException("YEAR se agrega aparte");
		};
	}

	private static String column(BudgetQuery.SortField sort) {
		return switch (sort) {
			case CONCEPT -> "concept";
			case CREDIT_FINAL -> "credit_final";
			case COMMITTED -> "committed";
			case OBLIGATIONS -> "obligations";
			case PAYMENTS -> "payments";
		};
	}

	/** Filtros del presupuesto. Los nombres de columna nunca vienen de fuera: solo los valores van como argumento. */
	private static final class Where {

		private final List<String> clauses = new ArrayList<>();
		private final List<Object> args = new ArrayList<>();

		Where(BudgetQuery query) {
			if (query.snapshotDate() != null) {
				clauses.add("snapshot_date = ?");
				args.add(query.snapshotDate());
			}
			if (query.chapterId() != null) {
				clauses.add("chapter_id = ?");
				args.add(query.chapterId());
			}
			add("area_id = ?", query.areaId());
			add("programme_id = ?", query.programmeId());
			add("organ_id = ?", query.organId());
			if (query.headingContains() != null && !query.headingContains().isBlank()) {
				clauses.add("heading ILIKE '%' || ? || '%'");
				args.add(query.headingContains().strip());
			}
		}

		private void add(String clause, String value) {
			if (value != null && !value.isBlank()) {
				clauses.add(clause);
				args.add(value.strip());
			}
		}

		String clause() {
			return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
		}

		/** La misma condición para pegarla a un {@code JOIN} que ya trae su propio {@code WHERE}. */
		String clause(String prefix) {
			return clauses.isEmpty() ? "" : prefix + String.join(" AND ", clauses);
		}

		Object[] args() {
			return args.toArray();
		}

	}

	// --- mapeo -----------------------------------------------------------------------------------------

	private static BudgetSnapshot mapSnapshot(ResultSet rs, int row) throws SQLException {
		return new BudgetSnapshot(rs.getObject("snapshot_date", LocalDate.class),
				SnapshotStatus.valueOf(rs.getString("read_status")), rs.getInt("attempts"),
				instantOrNull(rs, "last_attempt_at"), instantOrNull(rs, "next_attempt_at"),
				integerOrNull(rs, "lines"), integerOrNull(rs, "reported_count"), amounts(rs),
				instantOrNull(rs, "first_seen_at"), instantOrNull(rs, "last_seen_at"));
	}

	private static BudgetLine mapLine(ResultSet rs, int row) throws SQLException {
		return new BudgetLine(rs.getObject("snapshot_date", LocalDate.class), rs.getString("concept"),
				rs.getString("area_id"), rs.getString("area"), integerOrNull(rs, "chapter_id"),
				rs.getString("chapter"), rs.getString("programme_id"), rs.getString("programme"),
				rs.getString("organ_id"), rs.getString("organ"), rs.getString("item_id"), rs.getString("item"),
				rs.getString("heading"), rs.getBoolean("heading_redacted"), amounts(rs));
	}

	private static BudgetAmounts amounts(ResultSet rs) throws SQLException {
		return new BudgetAmounts(rs.getBigDecimal("credit_initial"), rs.getBigDecimal("credit_modification"),
				rs.getBigDecimal("credit_final"), rs.getBigDecimal("committed"), rs.getBigDecimal("obligations"),
				rs.getBigDecimal("payments"), rs.getBigDecimal("payments_pending"),
				rs.getBigDecimal("credit_remaining"));
	}

	private static void setAmounts(PreparedStatement ps, int firstIndex, BudgetAmounts amounts)
			throws SQLException {
		ps.setBigDecimal(firstIndex, amounts.creditInitial());
		ps.setBigDecimal(firstIndex + 1, amounts.creditModification());
		ps.setBigDecimal(firstIndex + 2, amounts.creditFinal());
		ps.setBigDecimal(firstIndex + 3, amounts.committed());
		ps.setBigDecimal(firstIndex + 4, amounts.obligations());
		ps.setBigDecimal(firstIndex + 5, amounts.payments());
		ps.setBigDecimal(firstIndex + 6, amounts.paymentsPending());
		ps.setBigDecimal(firstIndex + 7, amounts.creditRemaining());
	}

	/** {@code null} en una columna {@code timestamptz} necesita tipo explícito; sin él, PostgreSQL se queja. */
	private static void setTimestamp(PreparedStatement ps, int index, Instant value) throws SQLException {
		if (value == null) {
			ps.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
		}
		else {
			ps.setTimestamp(index, Timestamp.from(value));
		}
	}

	private static void setInteger(PreparedStatement ps, int index, Integer value) throws SQLException {
		if (value == null) {
			ps.setNull(index, Types.INTEGER);
		}
		else {
			ps.setInt(index, value);
		}
	}

	private static Integer integerOrNull(ResultSet rs, String column) throws SQLException {
		int value = rs.getInt(column);
		return rs.wasNull() ? null : value;
	}

	private static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

}
