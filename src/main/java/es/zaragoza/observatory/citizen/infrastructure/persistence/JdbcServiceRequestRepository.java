package es.zaragoza.observatory.citizen.infrastructure.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import es.zaragoza.observatory.citizen.domain.AggregationAxis;
import es.zaragoza.observatory.citizen.domain.AggregationBucket;
import es.zaragoza.observatory.geo.Assignment;
import es.zaragoza.observatory.citizen.domain.AssignmentCounts;
import es.zaragoza.observatory.citizen.domain.DistrictAssignment;
import es.zaragoza.observatory.citizen.domain.InternalServices;
import es.zaragoza.observatory.citizen.domain.ServiceRequest;
import es.zaragoza.observatory.citizen.domain.ServiceRequestPage;
import es.zaragoza.observatory.citizen.domain.ServiceRequestQuery;
import es.zaragoza.observatory.citizen.domain.ServiceRequestRepository;
import es.zaragoza.observatory.citizen.domain.ServiceRequestStatus;
import es.zaragoza.observatory.citizen.domain.YearCoverage;
import es.zaragoza.observatory.geo.GeoPoint;
import es.zaragoza.observatory.shared.ZaragozaTime;

/**
 * Adaptador de persistencia de las quejas, sobre SQL directo.
 * <p>
 * Sin entidad JPA a propósito: aquí no hay ninguna lectura por identificador ni ninguna relación que mapear, y sí
 * hay un alta por lotes de 89.432 registros y agregaciones con {@code FILTER} y {@code percentile_cont} que en
 * JPQL no existen. Lo que {@code ddl-auto=validate} haría por una entidad lo hacen aquí los tests de integración,
 * que ejecutan estas mismas consultas contra PostGIS real: si una columna de V009 no casa con el SQL, fallan.
 * <p>
 * El alta es un {@code INSERT … ON CONFLICT} por lote que <b>conserva {@code first_seen_at}</b>: reingerir un
 * registro ya visto actualiza su estado y su fecha de cierre sin perder cuándo apareció por primera vez
 * (regla 5).
 */
@Repository
class JdbcServiceRequestRepository implements ServiceRequestRepository {

	private static final String COLUMNS = """
			source_id, status, service_code, service_name, requested_at, updated_at, lon, lat, district_id,
			assignment, district_declared, district_declared_id, first_seen_at, last_seen_at
			""";

	private static final String UPSERT = """
			INSERT INTO citizen_service_request (source_id, status, service_code, service_name, requested_at,
			    updated_at, lon, lat, district_id, assignment, district_declared, district_declared_id,
			    first_seen_at, last_seen_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT (source_id) DO UPDATE SET
			    status = EXCLUDED.status,
			    service_code = EXCLUDED.service_code,
			    service_name = EXCLUDED.service_name,
			    requested_at = EXCLUDED.requested_at,
			    updated_at = EXCLUDED.updated_at,
			    lon = EXCLUDED.lon,
			    lat = EXCLUDED.lat,
			    district_id = EXCLUDED.district_id,
			    assignment = EXCLUDED.assignment,
			    district_declared = EXCLUDED.district_declared,
			    district_declared_id = EXCLUDED.district_declared_id,
			    last_seen_at = EXCLUDED.last_seen_at
			""";

	private static final RowMapper<ServiceRequest> MAPPER = JdbcServiceRequestRepository::mapRow;

	private final JdbcTemplate jdbc;

	JdbcServiceRequestRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional
	public int upsertAll(List<ServiceRequest> requests, Instant seenAt) {
		if (requests.isEmpty()) {
			return 0;
		}
		Timestamp seen = Timestamp.from(seenAt);
		jdbc.batchUpdate(UPSERT, requests, requests.size(), (PreparedStatement ps, ServiceRequest request) -> {
			var district = request.district();
			ps.setLong(1, request.sourceId());
			ps.setString(2, request.status().name());
			ps.setString(3, request.serviceCode());
			ps.setString(4, request.serviceName());
			ps.setTimestamp(5, Timestamp.from(request.requestedAt()));
			setTimestamp(ps, 6, request.updatedAt());
			setDouble(ps, 7, request.point() == null ? null : request.point().lon());
			setDouble(ps, 8, request.point() == null ? null : request.point().lat());
			setInteger(ps, 9, district.districtId());
			ps.setString(10, district.status().name());
			ps.setString(11, district.declaredName());
			setInteger(ps, 12, district.declaredDistrictId());
			ps.setTimestamp(13, seen);
			ps.setTimestamp(14, seen);
		});
		return requests.size();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Instant> latestRequestedAt() {
		return instant("SELECT max(requested_at) FROM citizen_service_request");
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Instant> latestUpdatedAt() {
		return instant("SELECT max(updated_at) FROM citizen_service_request");
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Instant> earliestRequestedAt() {
		return instant("SELECT min(requested_at) FROM citizen_service_request");
	}

	@Override
	@Transactional(readOnly = true)
	public long count() {
		Long total = jdbc.queryForObject("SELECT count(*) FROM citizen_service_request", Long.class);
		return total == null ? 0 : total;
	}

	@Override
	@Transactional(readOnly = true)
	public long count(ServiceRequestQuery filters) {
		var where = new Where(filters);
		Long total = jdbc.queryForObject("SELECT count(*) FROM citizen_service_request" + where.clause(), Long.class,
				where.args());
		return total == null ? 0 : total;
	}

	@Override
	@Transactional(readOnly = true)
	public ServiceRequestPage search(ServiceRequestQuery query) {
		var where = new Where(query);
		Long total = jdbc.queryForObject("SELECT count(*) FROM citizen_service_request" + where.clause(), Long.class,
				where.args());
		String direction = query.ascending() ? "ASC" : "DESC";
		// Desempate estable por id: sin él, dos páginas de registros con la misma fecha pueden repetir filas.
		String sql = "SELECT " + COLUMNS + " FROM citizen_service_request" + where.clause() + " ORDER BY "
				+ query.sortField().column() + " " + direction + " NULLS LAST, source_id " + direction
				+ " LIMIT ? OFFSET ?";
		var args = new ArrayList<Object>(Arrays.asList(where.args()));
		args.add(query.size());
		args.add(query.page() * query.size());
		List<ServiceRequest> items = jdbc.query(sql, MAPPER, args.toArray());
		return new ServiceRequestPage(items, total == null ? 0 : total, query.page(), query.size());
	}

	@Override
	@Transactional(readOnly = true)
	public List<AggregationBucket> aggregate(AggregationAxis axis, ServiceRequestQuery filters) {
		// Los dos ejes territoriales agrupan solo lo que tiene junta; lo demás no se reparte ni se esconde: va en
		// `assignmentCounts` (regla 7).
		boolean territorial = axis == AggregationAxis.DISTRICT || axis == AggregationAxis.DISTRICT_YEAR;
		var where = new Where(filters, territorial ? "district_id IS NOT NULL" : null);
		// El año y el mes son los de la hora local de Zaragoza: agrupar en UTC movería de grupo las quejas de
		// medianoche y las de Nochevieja.
		String localTime = "requested_at AT TIME ZONE '" + ZaragozaTime.ZONE.getId() + "'";
		String key = switch (axis) {
			case DISTRICT, DISTRICT_YEAR -> "district_id::text";
			case CATEGORY -> "service_code";
			case MONTH -> "to_char(" + localTime + ", 'YYYY-MM')";
		};
		String year = axis == AggregationAxis.DISTRICT_YEAR
				? "EXTRACT(YEAR FROM " + localTime + ")::int"
				: "NULL::int";
		String label = axis == AggregationAxis.CATEGORY ? "max(service_name)" : "NULL::text";
		String sql = """
				SELECT %s AS bucket_key, %s AS bucket_year, %s AS bucket_label, count(*) AS total,
				       count(*) FILTER (WHERE status = 'CLOSED') AS closed,
				       count(*) FILTER (WHERE lon IS NOT NULL) AS with_point,
				       count(*) FILTER (WHERE service_code = ?) AS internal,
				       percentile_cont(0.5) WITHIN GROUP (
				           ORDER BY EXTRACT(EPOCH FROM (updated_at - requested_at)) / 3600.0)
				           FILTER (WHERE status = 'CLOSED' AND updated_at IS NOT NULL
				                     AND updated_at >= requested_at) AS median_hours
				FROM citizen_service_request%s
				GROUP BY 1, 2
				ORDER BY 1, 2
				""".formatted(key, year, label, where.clause());
		// El parámetro del FILTER va delante de los de la cláusula WHERE, en el orden en que aparece en el SQL.
		var args = new ArrayList<Object>();
		args.add(InternalServices.CODE);
		args.addAll(Arrays.asList(where.args()));
		return jdbc.query(sql, (rs, row) -> new AggregationBucket(rs.getString("bucket_key"),
				rs.getString("bucket_label"), rs.getLong("total"), rs.getLong("closed"), rs.getLong("with_point"),
				rs.getLong("internal"), nullableInt(rs, "bucket_year"), nullableDouble(rs, "median_hours")),
				args.toArray());
	}

	@Override
	@Transactional(readOnly = true)
	public List<YearCoverage> pointCoverageByYear(ServiceRequestQuery filters) {
		// Sin la condición territorial a propósito: aquí cuentan también las que no se pudieron situar, que son
		// justo las que la serie por junta no puede enseñar (ADR-015).
		var where = new Where(filters);
		String sql = """
				SELECT EXTRACT(YEAR FROM requested_at AT TIME ZONE '%s')::int AS year, count(*) AS total,
				       count(*) FILTER (WHERE lon IS NOT NULL) AS with_point,
				       count(*) FILTER (WHERE district_id IS NOT NULL) AS assigned
				FROM citizen_service_request%s
				GROUP BY 1
				ORDER BY 1
				""".formatted(ZaragozaTime.ZONE.getId(), where.clause());
		return jdbc.query(sql, (rs, row) -> new YearCoverage(rs.getInt("year"), rs.getLong("total"),
				rs.getLong("with_point"), rs.getLong("assigned")), where.args());
	}

	@Override
	@Transactional(readOnly = true)
	public AssignmentCounts assignmentCounts(ServiceRequestQuery filters) {
		var where = new Where(filters);
		var byAssignment = new EnumMap<Assignment, Long>(Assignment.class);
		for (Assignment assignment : Assignment.values()) {
			byAssignment.put(assignment, 0L);
		}
		jdbc.query("SELECT assignment, count(*) AS n FROM citizen_service_request" + where.clause()
				+ " GROUP BY assignment",
				(rs, row) -> Map.entry(Assignment.valueOf(rs.getString("assignment")), rs.getLong("n")),
				where.args()).forEach(entry -> byAssignment.put(entry.getKey(), entry.getValue()));

		String sql = """
				SELECT count(*) FILTER (WHERE district_id IS NOT NULL AND district_declared_id IS NOT NULL
				                          AND district_id = district_declared_id) AS agrees,
				       count(*) FILTER (WHERE district_id IS NOT NULL AND district_declared_id IS NOT NULL
				                          AND district_id <> district_declared_id) AS disagrees,
				       count(*) FILTER (WHERE district_id IS NULL AND district_declared_id IS NOT NULL) AS only_declared,
				       count(*) FILTER (WHERE district_declared IS NOT NULL AND district_declared_id IS NULL) AS unmatched
				FROM citizen_service_request%s
				""".formatted(where.clause());
		return jdbc.queryForObject(sql, (rs, row) -> new AssignmentCounts(byAssignment, rs.getLong("agrees"),
				rs.getLong("disagrees"), rs.getLong("only_declared"), rs.getLong("unmatched")), where.args());
	}

	@Override
	@Transactional(readOnly = true)
	public Map<ServiceRequestStatus, Long> statusCounts(ServiceRequestQuery filters) {
		var where = new Where(filters);
		var counts = new EnumMap<ServiceRequestStatus, Long>(ServiceRequestStatus.class);
		for (ServiceRequestStatus status : ServiceRequestStatus.values()) {
			counts.put(status, 0L);
		}
		jdbc.query("SELECT status, count(*) AS n FROM citizen_service_request" + where.clause() + " GROUP BY status",
				(rs, row) -> Map.entry(ServiceRequestStatus.valueOf(rs.getString("status")), rs.getLong("n")),
				where.args()).forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
		return counts;
	}

	// --- construcción del filtro ------------------------------------------------------------------------

	/** Cláusula {@code WHERE} y sus argumentos, construida solo con nombres de columna fijos y parámetros. */
	private static final class Where {

		private final StringBuilder clause = new StringBuilder();
		private final List<Object> args = new ArrayList<>();

		Where(ServiceRequestQuery query) {
			this(query, null);
		}

		/** @param extraCondition condición fija sin parámetros (p. ej. excluir los registros sin junta) */
		Where(ServiceRequestQuery query, String extraCondition) {
			add("district_id = ?", query.districtId());
			add("service_code = ?", query.serviceCode());
			add("status = ?", query.status() == null ? null : query.status().name());
			add("assignment = ?", query.assignment() == null ? null : query.assignment().name());
			switch (query.internal() == null ? InternalServices.Filter.INCLUDE : query.internal()) {
				case EXCLUDE -> add("service_code IS DISTINCT FROM ?", InternalServices.CODE);
				case ONLY -> add("service_code = ?", InternalServices.CODE);
				case INCLUDE -> {
				}
			}
			add("requested_at >= ?", query.from() == null ? null : Timestamp.from(query.from()));
			add("requested_at < ?", query.to() == null ? null : Timestamp.from(query.to()));
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

	private Optional<Instant> instant(String sql) {
		Timestamp value = jdbc.queryForObject(sql, Timestamp.class);
		return Optional.ofNullable(value).map(Timestamp::toInstant);
	}

	private static ServiceRequest mapRow(ResultSet rs, int rowNumber) throws SQLException {
		Double lon = nullableDouble(rs, "lon");
		Double lat = nullableDouble(rs, "lat");
		GeoPoint point = lon == null || lat == null ? null : new GeoPoint(lon, lat);
		var assignment = new DistrictAssignment(Assignment.valueOf(rs.getString("assignment")),
				nullableInt(rs, "district_id"), rs.getString("district_declared"),
				nullableInt(rs, "district_declared_id"));
		return new ServiceRequest(rs.getLong("source_id"), ServiceRequestStatus.valueOf(rs.getString("status")),
				rs.getString("service_code"), rs.getString("service_name"),
				rs.getTimestamp("requested_at").toInstant(), instantOrNull(rs, "updated_at"), point, assignment,
				rs.getTimestamp("first_seen_at").toInstant(), rs.getTimestamp("last_seen_at").toInstant());
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
