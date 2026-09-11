package es.zaragoza.observatory.spending.infrastructure.persistence;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import es.zaragoza.observatory.spending.domain.Grant;
import es.zaragoza.observatory.spending.domain.GrantAxis;
import es.zaragoza.observatory.spending.domain.GrantBeneficiary;
import es.zaragoza.observatory.spending.domain.GrantBucket;
import es.zaragoza.observatory.spending.domain.GrantCall;
import es.zaragoza.observatory.spending.domain.GrantQuery;
import es.zaragoza.observatory.spending.domain.GrantRead;
import es.zaragoza.observatory.spending.domain.GrantRepository;
import es.zaragoza.observatory.spending.domain.GrantTotals;

/**
 * Adaptador de persistencia de las subvenciones, sobre SQL directo, por lo mismo que los otros dos del módulo:
 * alta por lotes de miles de filas y agregaciones que en JPQL no existen.
 * <p>
 * Tres decisiones que no se ven en la firma de los métodos:
 * <ul>
 * <li><b>El orden de ingesta no cambia lo que se guarda.</b> El upsert del directorio vuelve a calcular
 * {@code natural_person} como «lo que diga la clasificación <b>o</b> lo que ya supiéramos por el identificador
 * enmascarado», así que releer el directorio no le devuelve el nombre a nadie (ADR-018 §4).</li>
 * <li><b>El enlace se guarda aunque la concesión no exista todavía.</b> Tabla propia, sin clave ajena: los
 * cuatro recursos se ingieren por separado y esperar a que llegue el otro sería inventarse un orden.</li>
 * <li><b>Nada se borra.</b> Una concesión que dejara de publicarse conserva su fila y su {@code last_seen_at},
 * como las fichas dadas de baja de ADR-013.</li>
 * </ul>
 */
@Repository
class JdbcGrantRepository implements GrantRepository {

	private static final String GRANT_COLUMNS = """
			g.id, g.call_id, g.title, g.title_redacted, g.file_number, g.requested, g.granted, g.annual,
			g.annuities, g.requested_on, g.granted_on, g.agreed_on
			""";

	private static final String READ_COLUMNS = GRANT_COLUMNS + """
			, c.title AS call_title, c.line AS call_line, c.type AS call_type,
			l.beneficiary_id, b.name AS beneficiary_name, b.natural_person, b.classification
			""";

	private static final String READ_FROM = """
			FROM spending_grant g
			LEFT JOIN spending_grant_call c ON c.id = g.call_id
			LEFT JOIN spending_grant_link l ON l.grant_id = g.id
			LEFT JOIN spending_grant_beneficiary b ON b.id = l.beneficiary_id
			""";

	private static final String UPSERT_GRANT = """
			INSERT INTO spending_grant (id, call_id, title, title_redacted, file_number, requested, granted,
			    annual, annuities, requested_on, granted_on, agreed_on, first_seen_at, last_seen_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT (id) DO UPDATE SET
			    call_id = EXCLUDED.call_id, title = EXCLUDED.title, title_redacted = EXCLUDED.title_redacted,
			    file_number = EXCLUDED.file_number, requested = EXCLUDED.requested, granted = EXCLUDED.granted,
			    annual = EXCLUDED.annual, annuities = EXCLUDED.annuities,
			    requested_on = EXCLUDED.requested_on, granted_on = EXCLUDED.granted_on,
			    agreed_on = EXCLUDED.agreed_on, last_seen_at = EXCLUDED.last_seen_at
			""";

	private static final String UPSERT_CALL = """
			INSERT INTO spending_grant_call (id, title, fiscal_year, multi_year, valid_from, valid_to,
			    submission_from, submission_to, budget, advance_percentage, manager_id, manager, function_id,
			    function, purpose_id, purpose, type_id, type, line_id, line, scope_id, scope, area_id, area,
			    first_seen_at, last_seen_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT (id) DO UPDATE SET
			    title = EXCLUDED.title, fiscal_year = EXCLUDED.fiscal_year, multi_year = EXCLUDED.multi_year,
			    valid_from = EXCLUDED.valid_from, valid_to = EXCLUDED.valid_to,
			    submission_from = EXCLUDED.submission_from, submission_to = EXCLUDED.submission_to,
			    budget = EXCLUDED.budget, advance_percentage = EXCLUDED.advance_percentage,
			    manager_id = EXCLUDED.manager_id, manager = EXCLUDED.manager,
			    function_id = EXCLUDED.function_id, function = EXCLUDED.function,
			    purpose_id = EXCLUDED.purpose_id, purpose = EXCLUDED.purpose, type_id = EXCLUDED.type_id,
			    type = EXCLUDED.type, line_id = EXCLUDED.line_id, line = EXCLUDED.line,
			    scope_id = EXCLUDED.scope_id, scope = EXCLUDED.scope, area_id = EXCLUDED.area_id,
			    area = EXCLUDED.area, last_seen_at = EXCLUDED.last_seen_at
			""";

	/**
	 * El upsert que sostiene ADR-018 §4: {@code natural_person} vuelve a salir de la unión de las dos señales,
	 * y el nombre se descarta si cualquiera de ellas dice que es una persona. Por eso la fila recuerda
	 * {@code masked_identifier}: sin ella, releer el directorio después de los enlaces devolvería un nombre.
	 */
	private static final String UPSERT_BENEFICIARY = """
			INSERT INTO spending_grant_beneficiary (id, name, legal_nif, natural_person, masked_identifier,
			    classification, first_seen_at, last_seen_at)
			VALUES (?, ?, NULL, ?, false, ?, ?, ?)
			ON CONFLICT (id) DO UPDATE SET
			    natural_person = EXCLUDED.natural_person OR spending_grant_beneficiary.masked_identifier,
			    name = CASE
			        WHEN EXCLUDED.natural_person OR spending_grant_beneficiary.masked_identifier THEN NULL
			        ELSE EXCLUDED.name END,
			    legal_nif = CASE
			        WHEN EXCLUDED.natural_person OR spending_grant_beneficiary.masked_identifier THEN NULL
			        ELSE spending_grant_beneficiary.legal_nif END,
			    classification = EXCLUDED.classification,
			    last_seen_at = EXCLUDED.last_seen_at
			""";

	/** Un enlace puede llegar antes que su concesión: se guarda igual y se resuelve al leer. */
	private static final String UPSERT_LINK = """
			INSERT INTO spending_grant_link (grant_id, beneficiary_id, last_seen_at)
			VALUES (?, ?, ?)
			ON CONFLICT (grant_id) DO UPDATE SET
			    beneficiary_id = EXCLUDED.beneficiary_id, last_seen_at = EXCLUDED.last_seen_at
			""";

	/**
	 * La segunda señal, que además <b>retira</b> la identidad que el directorio hubiera dejado puesta. Es un
	 * upsert y no un update porque el enlace puede llegar antes que el directorio: si se quedara esperando, el
	 * orden de ingesta decidiría si un beneficiario sale nombrado, y eso en una regla de datos personales no
	 * vale (ADR-018 §4). La ficha entra sin clasificar y el directorio la completa después.
	 */
	private static final String MARK_NATURAL_PERSON = """
			INSERT INTO spending_grant_beneficiary (id, name, legal_nif, natural_person, masked_identifier,
			    classification, first_seen_at, last_seen_at)
			VALUES (?, NULL, NULL, true, true, NULL, ?, ?)
			ON CONFLICT (id) DO UPDATE SET
			    natural_person = true, masked_identifier = true, name = NULL, legal_nif = NULL,
			    last_seen_at = EXCLUDED.last_seen_at
			""";

	/** El NIF de persona jurídica, por la misma vía y con la misma cautela: nunca sobre una persona física. */
	private static final String SET_LEGAL_NIF = """
			INSERT INTO spending_grant_beneficiary (id, name, legal_nif, natural_person, masked_identifier,
			    classification, first_seen_at, last_seen_at)
			VALUES (?, NULL, ?, false, false, NULL, ?, ?)
			ON CONFLICT (id) DO UPDATE SET
			    legal_nif = CASE WHEN spending_grant_beneficiary.natural_person THEN NULL
			        ELSE EXCLUDED.legal_nif END,
			    last_seen_at = EXCLUDED.last_seen_at
			""";

	private final JdbcTemplate jdbc;
	private final Clock clock;

	JdbcGrantRepository(JdbcTemplate jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	// --- escritura ---------------------------------------------------------------------------------------

	@Override
	@Transactional
	public int upsertCalls(List<GrantCall> calls) {
		Timestamp now = Timestamp.from(clock.instant());
		jdbc.batchUpdate(UPSERT_CALL, calls, calls.size(), (PreparedStatement ps, GrantCall call) -> {
			int i = 0;
			ps.setInt(++i, call.id());
			ps.setString(++i, call.title());
			ps.setString(++i, call.fiscalYear());
			setBoolean(ps, ++i, call.multiYear());
			setDate(ps, ++i, call.validFrom());
			setDate(ps, ++i, call.validTo());
			setDate(ps, ++i, call.submissionFrom());
			setDate(ps, ++i, call.submissionTo());
			setDecimal(ps, ++i, call.budget());
			setInteger(ps, ++i, call.advancePercentage());
			ps.setString(++i, call.managerId());
			ps.setString(++i, call.manager());
			ps.setString(++i, call.functionId());
			ps.setString(++i, call.function());
			ps.setString(++i, call.purposeId());
			ps.setString(++i, call.purpose());
			ps.setString(++i, call.typeId());
			ps.setString(++i, call.type());
			ps.setString(++i, call.lineId());
			ps.setString(++i, call.line());
			ps.setString(++i, call.scopeId());
			ps.setString(++i, call.scope());
			ps.setString(++i, call.areaId());
			ps.setString(++i, call.area());
			ps.setTimestamp(++i, now);
			ps.setTimestamp(++i, now);
		});
		return calls.size();
	}

	@Override
	@Transactional
	public int upsertGrants(List<Grant> grants) {
		Timestamp now = Timestamp.from(clock.instant());
		jdbc.batchUpdate(UPSERT_GRANT, grants, grants.size(), (PreparedStatement ps, Grant grant) -> {
			int i = 0;
			ps.setLong(++i, grant.id());
			setInteger(ps, ++i, grant.callId());
			ps.setString(++i, grant.title());
			ps.setBoolean(++i, grant.titleRedacted());
			ps.setString(++i, grant.fileNumber());
			setDecimal(ps, ++i, grant.requested());
			setDecimal(ps, ++i, grant.granted());
			setDecimal(ps, ++i, grant.annual());
			setInteger(ps, ++i, grant.annuities());
			setDate(ps, ++i, grant.requestedOn());
			setDate(ps, ++i, grant.grantedOn());
			setDate(ps, ++i, grant.agreedOn());
			ps.setTimestamp(++i, now);
			ps.setTimestamp(++i, now);
		});
		return grants.size();
	}

	@Override
	@Transactional
	public int upsertBeneficiaries(List<GrantBeneficiary> beneficiaries) {
		Timestamp now = Timestamp.from(clock.instant());
		jdbc.batchUpdate(UPSERT_BENEFICIARY, beneficiaries, beneficiaries.size(),
				(PreparedStatement ps, GrantBeneficiary beneficiary) -> {
					int i = 0;
					ps.setString(++i, beneficiary.id());
					ps.setString(++i, beneficiary.name());
					ps.setBoolean(++i, beneficiary.naturalPerson());
					ps.setString(++i, beneficiary.classification());
					ps.setTimestamp(++i, now);
					ps.setTimestamp(++i, now);
				});
		return beneficiaries.size();
	}

	@Override
	@Transactional
	public int upsertLinks(Map<Long, String> links) {
		Timestamp now = Timestamp.from(clock.instant());
		var entries = List.copyOf(links.entrySet());
		jdbc.batchUpdate(UPSERT_LINK, entries, entries.size(), (PreparedStatement ps, Map.Entry<Long, String> e) -> {
			ps.setLong(1, e.getKey());
			ps.setString(2, e.getValue());
			ps.setTimestamp(3, now);
		});
		return entries.size();
	}

	@Override
	@Transactional
	public int markNaturalPersons(Set<String> beneficiaryIds) {
		Timestamp now = Timestamp.from(clock.instant());
		var ids = List.copyOf(beneficiaryIds);
		int[][] updated = jdbc.batchUpdate(MARK_NATURAL_PERSON, ids, ids.size(), (PreparedStatement ps, String id) -> {
			ps.setString(1, id);
			ps.setTimestamp(2, now);
			ps.setTimestamp(3, now);
		});
		return changed(updated);
	}

	@Override
	@Transactional
	public int setLegalNif(Map<String, String> byBeneficiary) {
		Timestamp now = Timestamp.from(clock.instant());
		var entries = List.copyOf(byBeneficiary.entrySet());
		int[][] updated = jdbc.batchUpdate(SET_LEGAL_NIF, entries, entries.size(),
				(PreparedStatement ps, Map.Entry<String, String> e) -> {
					ps.setString(1, e.getKey());
					ps.setString(2, e.getValue());
					ps.setTimestamp(3, now);
					ps.setTimestamp(4, now);
				});
		return changed(updated);
	}

	/** Filas realmente cambiadas en un lote; el driver puede devolver SUCCESS_NO_INFO (-2) por fila. */
	private static int changed(int[][] batches) {
		return Arrays.stream(batches).flatMapToInt(Arrays::stream).map(n -> Math.max(n, 0)).sum();
	}

	// --- lectura -----------------------------------------------------------------------------------------

	@Override
	@Transactional(readOnly = true)
	public Optional<Long> highestGrantId() {
		return Optional.ofNullable(jdbc.queryForObject("SELECT max(id) FROM spending_grant", Long.class));
	}

	@Override
	@Transactional(readOnly = true)
	public GrantRead.Page search(GrantQuery query) {
		var where = new Where(query);
		Long total = jdbc.queryForObject("SELECT count(*) " + READ_FROM + where.clause(), Long.class, where.args());
		String direction = query.ascending() ? "ASC" : "DESC";
		// Desempate estable por identificador: sin él, dos concesiones con el mismo importe o la misma fecha
		// pueden cambiar de página entre peticiones.
		String sql = "SELECT " + READ_COLUMNS + READ_FROM + where.clause() + " ORDER BY " + column(query.sort())
				+ " " + direction + " NULLS LAST, g.id " + direction + " LIMIT ? OFFSET ?";
		var args = new ArrayList<>(Arrays.asList(where.args()));
		args.add(query.size());
		args.add(query.page() * query.size());
		List<GrantRead> items = jdbc.query(sql, JdbcGrantRepository::mapRead, args.toArray());
		return new GrantRead.Page(items, total == null ? 0 : total, query.page(), query.size());
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<GrantRead> grant(long id) {
		List<GrantRead> found = jdbc.query("SELECT " + READ_COLUMNS + READ_FROM + " WHERE g.id = ?",
				JdbcGrantRepository::mapRead, id);
		return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
	}

	@Override
	@Transactional(readOnly = true)
	public List<GrantCall> calls() {
		return jdbc.query("SELECT * FROM spending_grant_call ORDER BY fiscal_year DESC NULLS LAST, id DESC",
				JdbcGrantRepository::mapCall);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<GrantCall> call(int id) {
		List<GrantCall> found = jdbc.query("SELECT * FROM spending_grant_call WHERE id = ?",
				JdbcGrantRepository::mapCall, id);
		return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
	}

	@Override
	@Transactional(readOnly = true)
	public List<GrantBucket> aggregate(GrantAxis axis, GrantQuery filters) {
		var where = new Where(filters);
		String key = keyColumn(axis);
		String label = labelColumn(axis);
		String sql = """
				SELECT %s AS bucket_key, max(%s) AS bucket_label, count(*) AS grants,
				    sum(g.granted) AS granted,
				    count(*) FILTER (WHERE b.natural_person) AS natural_person_grants,
				    count(DISTINCT l.beneficiary_id) AS beneficiaries
				%s%s
				GROUP BY %s
				ORDER BY sum(g.granted) DESC NULLS LAST, bucket_key
				""".formatted(key, label, READ_FROM, where.clause(), key);
		return jdbc.query(sql, (ResultSet rs, int row) -> new GrantBucket(rs.getString("bucket_key"),
				rs.getString("bucket_label"), rs.getLong("grants"), rs.getBigDecimal("granted"),
				rs.getLong("natural_person_grants"), rs.getLong("beneficiaries")), where.args());
	}

	@Override
	@Transactional(readOnly = true)
	public GrantTotals totals() {
		Long grants = count("SELECT count(*) FROM spending_grant");
		BigDecimal granted = jdbc.queryForObject("SELECT coalesce(sum(granted), 0) FROM spending_grant",
				BigDecimal.class);
		Long calls = count("SELECT count(*) FROM spending_grant_call");
		BigDecimal callBudget = jdbc.queryForObject("SELECT coalesce(sum(budget), 0) FROM spending_grant_call",
				BigDecimal.class);
		Long beneficiaries = count("SELECT count(*) FROM spending_grant_beneficiary");
		Long naturalPersons = count("SELECT count(*) FROM spending_grant_beneficiary WHERE natural_person");
		Long naturalPersonGrants = count("""
				SELECT count(*) FROM spending_grant g
				JOIN spending_grant_link l ON l.grant_id = g.id
				JOIN spending_grant_beneficiary b ON b.id = l.beneficiary_id
				WHERE b.natural_person
				""");
		var byClassification = new LinkedHashMap<String, Long>();
		jdbc.query("""
				SELECT coalesce(b.classification, '(sin beneficiario)') AS classification, count(*) AS n
				FROM spending_grant g
				LEFT JOIN spending_grant_link l ON l.grant_id = g.id
				LEFT JOIN spending_grant_beneficiary b ON b.id = l.beneficiary_id
				GROUP BY 1 ORDER BY n DESC
				""", (RowCallbackHandler) rs -> byClassification.put(rs.getString("classification"),
						rs.getLong("n")));
		Integer firstYear = jdbc.queryForObject(
				"SELECT extract(year from min(granted_on))::int FROM spending_grant WHERE granted_on >= DATE '1900-01-01'",
				Integer.class);
		Integer lastYear = jdbc.queryForObject(
				"SELECT extract(year from max(granted_on))::int FROM spending_grant", Integer.class);
		Long withoutBeneficiary = count("""
				SELECT count(*) FROM spending_grant g
				LEFT JOIN spending_grant_link l ON l.grant_id = g.id
				WHERE l.beneficiary_id IS NULL
				""");
		Long redacted = count("SELECT count(*) FROM spending_grant WHERE title_redacted");
		Long impossible = count(
				"SELECT count(*) FROM spending_grant WHERE granted_on IS NOT NULL AND granted_on < DATE '1900-01-01'");
		return new GrantTotals(grants, granted, calls, callBudget, beneficiaries, naturalPersons,
				naturalPersonGrants, byClassification, firstYear, lastYear, withoutBeneficiary, redacted,
				impossible);
	}

	// --- SQL -------------------------------------------------------------------------------------------

	private long count(String sql) {
		Long value = jdbc.queryForObject(sql, Long.class);
		return value == null ? 0 : value;
	}

	private static String keyColumn(GrantAxis axis) {
		return switch (axis) {
			case YEAR -> "extract(year from g.granted_on)::text";
			case CALL -> "g.call_id::text";
			case LINE -> "c.line_id";
			case TYPE -> "c.type_id";
			case MANAGER -> "c.manager_id";
			case CLASSIFICATION -> "b.classification";
			case BENEFICIARY -> "l.beneficiary_id";
		};
	}

	/**
	 * La etiqueta del grupo. En el eje de beneficiario es {@code b.name}, que es <b>nulo en toda persona física</b>
	 * por construcción: el grupo se cuenta, no se nombra (ADR-018 §4).
	 */
	private static String labelColumn(GrantAxis axis) {
		return switch (axis) {
			case YEAR -> "NULL::text";
			case CALL -> "c.title";
			case LINE -> "c.line";
			case TYPE -> "c.type";
			case MANAGER -> "c.manager";
			case CLASSIFICATION -> "b.classification";
			case BENEFICIARY -> "b.name";
		};
	}

	private static String column(GrantQuery.SortField sort) {
		return switch (sort) {
			case ID -> "g.id";
			case GRANTED -> "g.granted";
			case REQUESTED -> "g.requested";
			case GRANTED_ON -> "g.granted_on";
			case REQUESTED_ON -> "g.requested_on";
		};
	}

	private static GrantRead mapRead(ResultSet rs, int row) throws SQLException {
		Boolean naturalPerson = rs.getObject("natural_person") == null ? null : rs.getBoolean("natural_person");
		return new GrantRead(mapGrant(rs), rs.getString("call_title"), rs.getString("call_line"),
				rs.getString("call_type"), rs.getString("beneficiary_id"), rs.getString("beneficiary_name"),
				naturalPerson, rs.getString("classification"));
	}

	private static Grant mapGrant(ResultSet rs, int row) throws SQLException {
		return mapGrant(rs);
	}

	private static Grant mapGrant(ResultSet rs) throws SQLException {
		Integer callId = rs.getObject("call_id") == null ? null : rs.getInt("call_id");
		Integer annuities = rs.getObject("annuities") == null ? null : rs.getInt("annuities");
		return new Grant(rs.getLong("id"), callId, rs.getString("title"), rs.getBoolean("title_redacted"),
				rs.getString("file_number"), rs.getBigDecimal("requested"), rs.getBigDecimal("granted"),
				rs.getBigDecimal("annual"), annuities, rs.getObject("requested_on", LocalDate.class),
				rs.getObject("granted_on", LocalDate.class), rs.getObject("agreed_on", LocalDate.class));
	}

	private static GrantCall mapCall(ResultSet rs, int row) throws SQLException {
		Boolean multiYear = rs.getObject("multi_year") == null ? null : rs.getBoolean("multi_year");
		Integer advance = rs.getObject("advance_percentage") == null ? null : rs.getInt("advance_percentage");
		return new GrantCall(rs.getInt("id"), rs.getString("title"), rs.getString("fiscal_year"), multiYear,
				rs.getObject("valid_from", LocalDate.class), rs.getObject("valid_to", LocalDate.class),
				rs.getObject("submission_from", LocalDate.class), rs.getObject("submission_to", LocalDate.class),
				rs.getBigDecimal("budget"), advance, rs.getString("manager_id"), rs.getString("manager"),
				rs.getString("function_id"), rs.getString("function"), rs.getString("purpose_id"),
				rs.getString("purpose"), rs.getString("type_id"), rs.getString("type"), rs.getString("line_id"),
				rs.getString("line"), rs.getString("scope_id"), rs.getString("scope"), rs.getString("area_id"),
				rs.getString("area"));
	}

	private static void setDecimal(PreparedStatement ps, int index, BigDecimal value) throws SQLException {
		if (value == null) {
			ps.setNull(index, Types.NUMERIC);
		}
		else {
			ps.setBigDecimal(index, value);
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

	private static void setBoolean(PreparedStatement ps, int index, Boolean value) throws SQLException {
		if (value == null) {
			ps.setNull(index, Types.BOOLEAN);
		}
		else {
			ps.setBoolean(index, value);
		}
	}

	private static void setDate(PreparedStatement ps, int index, LocalDate value) throws SQLException {
		if (value == null) {
			ps.setNull(index, Types.DATE);
		}
		else {
			ps.setObject(index, value);
		}
	}

	/** Filtros. Los nombres de columna nunca vienen de fuera: solo los valores van como argumento. */
	private static final class Where {

		private final List<String> clauses = new ArrayList<>();
		private final List<Object> args = new ArrayList<>();

		Where(GrantQuery query) {
			if (query.year() != null) {
				clauses.add("extract(year from g.granted_on) = ?");
				args.add(query.year());
			}
			if (query.callId() != null) {
				clauses.add("g.call_id = ?");
				args.add(query.callId());
			}
			if (query.naturalPerson() != null) {
				clauses.add("b.natural_person = ?");
				args.add(query.naturalPerson());
			}
			add("l.beneficiary_id = ?", query.beneficiaryId());
			add("b.classification = ?", query.classification());
			if (query.titleContains() != null && !query.titleContains().isBlank()) {
				clauses.add("g.title ILIKE '%' || ? || '%'");
				args.add(query.titleContains().strip());
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

		Object[] args() {
			return args.toArray();
		}
	}

}
