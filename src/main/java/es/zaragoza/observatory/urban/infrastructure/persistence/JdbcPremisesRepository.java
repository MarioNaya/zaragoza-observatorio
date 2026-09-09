package es.zaragoza.observatory.urban.infrastructure.persistence;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import es.zaragoza.observatory.geo.Assignment;
import es.zaragoza.observatory.geo.GeoPoint;
import es.zaragoza.observatory.urban.domain.AggregationAxis;
import es.zaragoza.observatory.urban.domain.AggregationBucket;
import es.zaragoza.observatory.urban.domain.IaeActivity;
import es.zaragoza.observatory.urban.domain.Licence;
import es.zaragoza.observatory.urban.domain.LicensedPremises;
import es.zaragoza.observatory.urban.domain.PremisesPage;
import es.zaragoza.observatory.urban.domain.PremisesQuery;
import es.zaragoza.observatory.urban.domain.PremisesRepository;
import es.zaragoza.observatory.urban.domain.YearCoverage;

/**
 * Adaptador de persistencia de los locales con licencia, sobre SQL directo, por las mismas razones que en
 * {@code citizen}: alta por lotes de 42.342 registros y agregaciones con {@code FILTER} que en JPQL no existen.
 * Lo que {@code ddl-auto=validate} haría por una entidad JPA lo hacen aquí los tests de integración, que
 * ejecutan estas mismas consultas contra PostGIS real.
 * <p>
 * Las licencias de un local <b>se reemplazan enteras</b> en cada upsert: el origen las publica siempre completas
 * dentro del local, así que una que desaparezca ha desaparecido de verdad, y quedarse con la copia vieja sería
 * conservar un expediente que ya no existe.
 */
@Repository
class JdbcPremisesRepository implements PremisesRepository {

	private static final String PREMISES_COLUMNS = """
			p.source_id, p.iae_code, p.iae_title, p.iae_section, p.iae_group, p.status_code, p.portal_code,
			p.street_code, p.saturated_zone, p.created_at, p.updated_at, p.deregistered_at, p.lon, p.lat,
			p.district_id, p.assignment, p.first_seen_at, p.last_seen_at
			""";

	private static final String UPSERT_PREMISES = """
			INSERT INTO urban_premises (source_id, iae_code, iae_title, iae_section, iae_group, status_code,
			    portal_code, street_code, saturated_zone, created_at, updated_at, deregistered_at, lon, lat,
			    district_id, assignment, first_seen_at, last_seen_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT (source_id) DO UPDATE SET
			    iae_code = EXCLUDED.iae_code,
			    iae_title = EXCLUDED.iae_title,
			    iae_section = EXCLUDED.iae_section,
			    iae_group = EXCLUDED.iae_group,
			    status_code = EXCLUDED.status_code,
			    portal_code = EXCLUDED.portal_code,
			    street_code = EXCLUDED.street_code,
			    saturated_zone = EXCLUDED.saturated_zone,
			    created_at = EXCLUDED.created_at,
			    updated_at = EXCLUDED.updated_at,
			    deregistered_at = EXCLUDED.deregistered_at,
			    lon = EXCLUDED.lon,
			    lat = EXCLUDED.lat,
			    district_id = EXCLUDED.district_id,
			    assignment = EXCLUDED.assignment,
			    last_seen_at = EXCLUDED.last_seen_at
			""";

	private static final String INSERT_LICENCE = """
			INSERT INTO urban_premises_licence (premises_id, year, file_number, display_order, type_id, type_name,
			    resolved_on, resolution_code, created_at, updated_at, deregistered_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT (premises_id, year, file_number) DO UPDATE SET
			    display_order = EXCLUDED.display_order,
			    type_id = EXCLUDED.type_id,
			    type_name = EXCLUDED.type_name,
			    resolved_on = EXCLUDED.resolved_on,
			    resolution_code = EXCLUDED.resolution_code,
			    created_at = EXCLUDED.created_at,
			    updated_at = EXCLUDED.updated_at,
			    deregistered_at = EXCLUDED.deregistered_at
			""";

	private final JdbcTemplate jdbc;

	JdbcPremisesRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional
	public int upsertAll(List<LicensedPremises> premises, Instant seenAt) {
		if (premises.isEmpty()) {
			return 0;
		}
		Timestamp seen = Timestamp.from(seenAt);
		jdbc.batchUpdate(UPSERT_PREMISES, premises, premises.size(),
				(PreparedStatement ps, LicensedPremises item) -> {
					IaeActivity activity = item.activity();
					ps.setInt(1, item.sourceId());
					ps.setString(2, activity.code());
					ps.setString(3, activity.title());
					setInteger(ps, 4, activity.section());
					setInteger(ps, 5, activity.group());
					ps.setInt(6, item.statusCode());
					ps.setString(7, item.portalCode());
					ps.setString(8, item.streetCode());
					ps.setString(9, item.saturatedZone());
					ps.setTimestamp(10, Timestamp.from(item.createdAt()));
					setTimestamp(ps, 11, item.updatedAt());
					setTimestamp(ps, 12, item.deregisteredAt());
					setDouble(ps, 13, item.point() == null ? null : item.point().lon());
					setDouble(ps, 14, item.point() == null ? null : item.point().lat());
					setInteger(ps, 15, item.districtId());
					ps.setString(16, item.assignment().name());
					ps.setTimestamp(17, seen);
					ps.setTimestamp(18, seen);
				});
		replaceLicences(premises);
		return premises.size();
	}

	/**
	 * Borra las licencias de los locales de la página y vuelve a insertarlas. El borrado es por los ids de la
	 * página, no por toda la tabla: una ingesta incremental solo toca lo que ha traído.
	 */
	private void replaceLicences(List<LicensedPremises> premises) {
		List<Object[]> ids = premises.stream().map(item -> new Object[] { item.sourceId() }).toList();
		jdbc.batchUpdate("DELETE FROM urban_premises_licence WHERE premises_id = ?", ids);

		record Row(int premisesId, Licence licence) {
		}
		List<Row> rows = new ArrayList<>();
		for (LicensedPremises item : premises) {
			for (Licence licence : item.licences()) {
				rows.add(new Row(item.sourceId(), licence));
			}
		}
		if (rows.isEmpty()) {
			return;
		}
		jdbc.batchUpdate(INSERT_LICENCE, rows, rows.size(), (PreparedStatement ps, Row row) -> {
			Licence licence = row.licence();
			ps.setInt(1, row.premisesId());
			ps.setInt(2, licence.year());
			ps.setLong(3, licence.fileNumber());
			setInteger(ps, 4, licence.displayOrder());
			ps.setInt(5, licence.typeId());
			ps.setString(6, licence.typeName());
			if (licence.resolvedOn() == null) {
				ps.setNull(7, Types.DATE);
			}
			else {
				ps.setDate(7, Date.valueOf(licence.resolvedOn()));
			}
			setInteger(ps, 8, licence.resolutionCode());
			setTimestamp(ps, 9, licence.createdAt());
			setTimestamp(ps, 10, licence.updatedAt());
			setTimestamp(ps, 11, licence.deregisteredAt());
		});
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Instant> latestUpdatedAt() {
		return instant("SELECT max(updated_at) FROM urban_premises");
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Instant> earliestCreatedAt() {
		return instant("SELECT min(created_at) FROM urban_premises");
	}

	@Override
	@Transactional(readOnly = true)
	public long count() {
		return count("SELECT count(*) FROM urban_premises");
	}

	@Override
	@Transactional(readOnly = true)
	public long countLicences() {
		return count("SELECT count(*) FROM urban_premises_licence");
	}

	@Override
	@Transactional(readOnly = true)
	public long count(PremisesQuery filters) {
		var where = new Where(filters);
		Long total = jdbc.queryForObject("SELECT count(*) FROM urban_premises p" + where.clause(), Long.class,
				where.args());
		return total == null ? 0 : total;
	}

	@Override
	@Transactional(readOnly = true)
	public PremisesPage search(PremisesQuery query) {
		var where = new Where(query);
		Long total = jdbc.queryForObject("SELECT count(*) FROM urban_premises p" + where.clause(), Long.class,
				where.args());
		String direction = query.ascending() ? "ASC" : "DESC";
		// Desempate estable por id: sin él, dos páginas de locales con la misma fecha pueden repetir filas.
		String sql = "SELECT " + PREMISES_COLUMNS + " FROM urban_premises p" + where.clause() + " ORDER BY "
				+ query.sortField().column() + " " + direction + " NULLS LAST, p.source_id " + direction
				+ " LIMIT ? OFFSET ?";
		var args = new ArrayList<Object>(Arrays.asList(where.args()));
		args.add(query.size());
		args.add(query.page() * query.size());
		List<LicensedPremises> items = jdbc.query(sql, JdbcPremisesRepository::mapPremises, args.toArray());
		return new PremisesPage(withLicences(items), total == null ? 0 : total, query.page(), query.size());
	}

	/** Carga las licencias de la página con una sola consulta, no una por local. */
	private List<LicensedPremises> withLicences(List<LicensedPremises> items) {
		if (items.isEmpty()) {
			return items;
		}
		String placeholders = String.join(",", items.stream().map(item -> "?").toList());
		Object[] ids = items.stream().map(LicensedPremises::sourceId).toArray();
		Map<Integer, List<Licence>> byPremises = new LinkedHashMap<>();
		jdbc.query("""
				SELECT premises_id, year, file_number, display_order, type_id, type_name, resolved_on,
				       resolution_code, created_at, updated_at, deregistered_at
				FROM urban_premises_licence
				WHERE premises_id IN (%s)
				ORDER BY premises_id, year, file_number
				""".formatted(placeholders), rs -> {
			byPremises.computeIfAbsent(rs.getInt("premises_id"), key -> new ArrayList<>()).add(mapLicence(rs));
		}, ids);
		return items.stream()
				.map(item -> new LicensedPremises(item.sourceId(), item.activity(), item.statusCode(),
						item.portalCode(), item.streetCode(), item.saturatedZone(), item.createdAt(),
						item.updatedAt(), item.deregisteredAt(), item.point(), item.districtId(), item.assignment(),
						byPremises.getOrDefault(item.sourceId(), List.of()), item.firstSeenAt(), item.lastSeenAt()))
				.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public List<AggregationBucket> aggregate(AggregationAxis axis, PremisesQuery filters) {
		// Los ejes territoriales agrupan solo lo que tiene junta; lo demás no se reparte ni se esconde: va en
		// `assignmentCounts` (regla 7).
		var where = new Where(filters, axis.isTerritorial() ? "p.district_id IS NOT NULL" : null);
		boolean byLicence = axis.unit() == AggregationAxis.Unit.LICENCES;
		// Siempre se une con las licencias, y el tipo de unión es lo que fija la unidad: con INNER, un local sin
		// licencias no aporta nada al grupo de licencias; con LEFT, un local sin licencias sigue contando como
		// local (son tres en el registro entero, pero contarlos o no es la diferencia entre las dos unidades).
		String join = byLicence ? "JOIN" : "LEFT JOIN";
		String key = switch (axis) {
			case DISTRICT, DISTRICT_LICENCE_YEAR -> "p.district_id::text";
			case ACTIVITY -> "coalesce(p.iae_section::text, '?') || '.' || coalesce(p.iae_group::text, '?')";
			case STATUS -> "p.status_code::text";
			case LICENCE_YEAR -> "l.year::text";
			case LICENCE_TYPE -> "l.type_id::text";
		};
		String year = switch (axis) {
			case DISTRICT_LICENCE_YEAR, LICENCE_YEAR -> "l.year";
			default -> "NULL::int";
		};
		String label = switch (axis) {
			case ACTIVITY -> "max(p.iae_title)";
			case LICENCE_TYPE -> "max(l.type_name)";
			default -> "NULL::text";
		};
		// `total` cuenta la unidad del eje; `premises` y `licences` van los dos en cada grupo para que se vea la
		// diferencia sin tener que pedir otra agregación (ADR-016 §7).
		String total = byLicence ? "count(*)" : "count(DISTINCT p.source_id)";
		String deregistered = byLicence ? "count(*) FILTER (WHERE l.deregistered_at IS NOT NULL)"
				: "count(DISTINCT p.source_id) FILTER (WHERE p.deregistered_at IS NOT NULL)";
		String sql = """
				SELECT %s AS bucket_key, %s AS bucket_year, %s AS bucket_label, %s AS total,
				       count(DISTINCT p.source_id) AS premises,
				       count(l.premises_id) AS licences,
				       count(DISTINCT p.source_id) FILTER (WHERE p.lon IS NOT NULL) AS with_point,
				       %s AS deregistered
				FROM urban_premises p %s urban_premises_licence l ON l.premises_id = p.source_id%s
				GROUP BY 1, 2
				ORDER BY 1, 2
				""".formatted(key, year, label, total, deregistered, join, where.clause());
		return jdbc.query(sql,
				(rs, row) -> new AggregationBucket(rs.getString("bucket_key"), rs.getString("bucket_label"),
						rs.getLong("total"), rs.getLong("premises"), rs.getLong("licences"),
						rs.getLong("with_point"), rs.getLong("deregistered"), nullableInt(rs, "bucket_year")),
				where.args());
	}

	@Override
	@Transactional(readOnly = true)
	public List<YearCoverage> pointCoverageByLicenceYear(PremisesQuery filters) {
		// Sin la condición territorial a propósito: aquí cuentan también los locales que no se pudieron situar,
		// que son justo los que la serie por junta no puede enseñar (ADR-015).
		var where = new Where(filters);
		String sql = """
				SELECT l.year AS year, count(DISTINCT p.source_id) AS total,
				       count(DISTINCT p.source_id) FILTER (WHERE p.lon IS NOT NULL) AS with_point,
				       count(DISTINCT p.source_id) FILTER (WHERE p.district_id IS NOT NULL) AS assigned
				FROM urban_premises_licence l JOIN urban_premises p ON p.source_id = l.premises_id%s
				GROUP BY 1
				ORDER BY 1
				""".formatted(where.clause());
		return jdbc.query(sql, (rs, row) -> new YearCoverage(rs.getInt("year"), rs.getLong("total"),
				rs.getLong("with_point"), rs.getLong("assigned")), where.args());
	}

	@Override
	@Transactional(readOnly = true)
	public Map<Assignment, Long> assignmentCounts(PremisesQuery filters) {
		var where = new Where(filters);
		var counts = new EnumMap<Assignment, Long>(Assignment.class);
		for (Assignment assignment : Assignment.values()) {
			counts.put(assignment, 0L);
		}
		jdbc.query("SELECT p.assignment, count(*) AS n FROM urban_premises p" + where.clause()
				+ " GROUP BY p.assignment",
				(rs, row) -> Map.entry(Assignment.valueOf(rs.getString("assignment")), rs.getLong("n")),
				where.args()).forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
		return counts;
	}

	@Override
	@Transactional(readOnly = true)
	public Map<Integer, Long> statusCounts(PremisesQuery filters) {
		var where = new Where(filters);
		Map<Integer, Long> counts = new TreeMap<>();
		jdbc.query("SELECT p.status_code, count(*) AS n FROM urban_premises p" + where.clause()
				+ " GROUP BY p.status_code ORDER BY p.status_code",
				(rs, row) -> Map.entry(rs.getInt("status_code"), rs.getLong("n")), where.args())
				.forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
		return counts;
	}

	// --- construcción del filtro ------------------------------------------------------------------------

	/** Cláusula {@code WHERE} y sus argumentos, construida solo con nombres de columna fijos y parámetros. */
	private static final class Where {

		private final StringBuilder clause = new StringBuilder();
		private final List<Object> args = new ArrayList<>();

		Where(PremisesQuery query) {
			this(query, null);
		}

		/** @param extraCondition condición fija sin parámetros (p. ej. excluir los locales sin junta) */
		Where(PremisesQuery query, String extraCondition) {
			add("p.district_id = ?", query.districtId());
			add("p.iae_code = ?", query.iaeCode());
			add("p.iae_section = ?", query.iaeSection());
			add("p.iae_group = ?", query.iaeGroup());
			add("p.status_code = ?", query.statusCode());
			add("p.saturated_zone = ?", query.saturatedZone());
			add("p.assignment = ?", query.assignment() == null ? null : query.assignment().name());
			add("EXISTS (SELECT 1 FROM urban_premises_licence y WHERE y.premises_id = p.source_id AND y.year = ?)",
					query.licenceYear());
			add("p.created_at >= ?", query.from() == null ? null : Timestamp.from(query.from()));
			add("p.created_at < ?", query.to() == null ? null : Timestamp.from(query.to()));
			if (extraCondition != null) {
				clause.append(clause.isEmpty() ? " WHERE " : " AND ").append(extraCondition);
			}
		}

		private void add(String condition, Object value) {
			if (value == null) {
				return;
			}
			clause.append(clause.isEmpty() ? " WHERE " : " AND ").append(condition);
			args.add(value);
		}

		String clause() {
			return clause.toString();
		}

		Object[] args() {
			return args.toArray();
		}
	}

	// --- utilidades JDBC --------------------------------------------------------------------------------

	private long count(String sql) {
		Long total = jdbc.queryForObject(sql, Long.class);
		return total == null ? 0 : total;
	}

	private Optional<Instant> instant(String sql) {
		Timestamp value = jdbc.queryForObject(sql, Timestamp.class);
		return Optional.ofNullable(value).map(Timestamp::toInstant);
	}

	private static LicensedPremises mapPremises(ResultSet rs, int rowNumber) throws SQLException {
		Double lon = nullableDouble(rs, "lon");
		Double lat = nullableDouble(rs, "lat");
		GeoPoint point = lon == null || lat == null ? null : new GeoPoint(lon, lat);
		var activity = new IaeActivity(rs.getString("iae_code"), rs.getString("iae_title"),
				nullableInt(rs, "iae_section"), nullableInt(rs, "iae_group"));
		return new LicensedPremises(rs.getInt("source_id"), activity, rs.getInt("status_code"),
				rs.getString("portal_code"), rs.getString("street_code"), rs.getString("saturated_zone"),
				rs.getTimestamp("created_at").toInstant(), instantOrNull(rs, "updated_at"),
				instantOrNull(rs, "deregistered_at"), point, nullableInt(rs, "district_id"),
				Assignment.valueOf(rs.getString("assignment")), List.of(),
				rs.getTimestamp("first_seen_at").toInstant(), rs.getTimestamp("last_seen_at").toInstant());
	}

	private static Licence mapLicence(ResultSet rs) throws SQLException {
		Date resolved = rs.getDate("resolved_on");
		return new Licence(rs.getInt("year"), rs.getLong("file_number"), nullableInt(rs, "display_order"),
				rs.getInt("type_id"), rs.getString("type_name"), resolved == null ? null : resolved.toLocalDate(),
				nullableInt(rs, "resolution_code"), instantOrNull(rs, "created_at"),
				instantOrNull(rs, "updated_at"), instantOrNull(rs, "deregistered_at"));
	}

	private static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
		double value = rs.getDouble(column);
		return rs.wasNull() ? null : value;
	}

	private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
		int value = rs.getInt(column);
		return rs.wasNull() ? null : value;
	}

	private static void setTimestamp(PreparedStatement ps, int index, Instant value) throws SQLException {
		if (value == null) {
			ps.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
		}
		else {
			ps.setTimestamp(index, Timestamp.from(value));
		}
	}

	private static void setDouble(PreparedStatement ps, int index, Double value) throws SQLException {
		if (value == null) {
			ps.setNull(index, Types.DOUBLE);
		}
		else {
			ps.setDouble(index, value);
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

}
