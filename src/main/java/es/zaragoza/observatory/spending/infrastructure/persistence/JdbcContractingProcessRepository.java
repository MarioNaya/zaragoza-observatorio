package es.zaragoza.observatory.spending.infrastructure.persistence;

import java.math.BigDecimal;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import es.zaragoza.observatory.spending.domain.AggregationAxis;
import es.zaragoza.observatory.spending.domain.AggregationBucket;
import es.zaragoza.observatory.spending.domain.Award;
import es.zaragoza.observatory.spending.domain.Contract;
import es.zaragoza.observatory.spending.domain.ContractingProcess;
import es.zaragoza.observatory.spending.domain.ContractingProcessRepository;
import es.zaragoza.observatory.spending.domain.Cpv;
import es.zaragoza.observatory.spending.domain.DueRelease;
import es.zaragoza.observatory.spending.domain.Money;
import es.zaragoza.observatory.spending.domain.PartyIdentity;
import es.zaragoza.observatory.spending.domain.ProcessPage;
import es.zaragoza.observatory.spending.domain.ProcessQuery;
import es.zaragoza.observatory.spending.domain.ReleaseContent;
import es.zaragoza.observatory.spending.domain.ReleaseStatus;
import es.zaragoza.observatory.spending.domain.SpendingTotals;
import es.zaragoza.observatory.spending.domain.Stage;
import es.zaragoza.observatory.spending.domain.Tender;

/**
 * Adaptador de persistencia de la contratación, sobre SQL directo, por las mismas razones que en {@code citizen}
 * y {@code urban}: alta por lotes de 8.001 registros y agregaciones con {@code FILTER} que en JPQL no existen.
 * Lo que {@code ddl-auto=validate} haría por una entidad JPA lo hacen aquí los tests de integración, que
 * ejecutan estas mismas consultas contra PostgreSQL real.
 * <p>
 * Dos decisiones que no se ven en la firma de los métodos:
 * <ul>
 * <li><b>Una lectura que no devuelve release no borra el contenido anterior.</b> Un 404 sobre un proceso que ya
 * tenía release es raro y probablemente temporal; vaciar sus columnas convertiría un tropiezo de la fuente en
 * una pérdida de datos. Se actualiza el estado y la contabilidad del intento, y el contenido se queda.</li>
 * <li><b>Las adjudicaciones, los contratos y los CPV se reemplazan enteros</b> cuando sí hay release, no se
 * acumulan: el documento los publica completos, así que lo que desaparece ha desaparecido.</li>
 * </ul>
 */
@Repository
class JdbcContractingProcessRepository implements ContractingProcessRepository {


	private static final String PROCESS_COLUMNS = """
			p.ocid, p.file_number, p.in_documented_list, p.release_status, p.attempts, p.last_attempt_at,
			p.published_at, p.release_id, p.tags, p.initiation_type, p.tender_title, p.tender_description,
			p.tender_status, p.procurement_method, p.procurement_category, p.award_criteria,
			p.number_of_tenderers, p.tender_amount, p.tender_min_amount, p.tender_currency,
			p.procuring_entity_name, p.procuring_entity_id, p.stage, p.first_seen_at, p.last_seen_at
			""";

	private static final String UPSERT_CENSUS = """
			INSERT INTO spending_process (ocid, file_number, release_status, next_attempt_at, first_seen_at,
			    last_seen_at)
			VALUES (?, ?, 'PENDING', ?, ?, ?)
			ON CONFLICT (ocid) DO UPDATE SET last_seen_at = EXCLUDED.last_seen_at
			""";

	private static final String UPDATE_WITH_RELEASE = """
			UPDATE spending_process SET
			    release_status = ?, attempts = ?, last_attempt_at = ?, next_attempt_at = ?,
			    published_at = ?, release_id = ?, tags = ?, initiation_type = ?,
			    tender_title = ?, tender_description = ?, tender_status = ?, procurement_method = ?,
			    procurement_category = ?, award_criteria = ?, number_of_tenderers = ?,
			    tender_amount = ?, tender_min_amount = ?, tender_currency = ?,
			    procuring_entity_name = ?, procuring_entity_id = ?, stage = ?, awarded_amount = ?
			WHERE ocid = ?
			""";

	/** Sin release: se actualiza la situación y la contabilidad, y el contenido conocido se conserva. */
	private static final String UPDATE_WITHOUT_RELEASE = """
			UPDATE spending_process SET release_status = ?, attempts = ?, last_attempt_at = ?, next_attempt_at = ?
			WHERE ocid = ?
			""";

	private final JdbcTemplate jdbc;

	JdbcContractingProcessRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	// --- escritura ---------------------------------------------------------------------------------------

	@Override
	@Transactional
	public int upsertCensus(List<String> ocids, Instant seenAt) {
		if (ocids.isEmpty()) {
			return 0;
		}
		Timestamp seen = Timestamp.from(seenAt);
		jdbc.batchUpdate(UPSERT_CENSUS, ocids, ocids.size(), (PreparedStatement ps, String ocid) -> {
			ps.setString(1, ocid);
			setInteger(ps, 2, ContractingProcess.fileNumberOf(ocid));
			// Un proceso recién censado toca ya: nunca se ha pedido su detalle.
			ps.setTimestamp(3, seen);
			ps.setTimestamp(4, seen);
			ps.setTimestamp(5, seen);
		});
		return ocids.size();
	}

	@Override
	@Transactional
	public int markDocumentedList(Set<String> documented) {
		if (documented.isEmpty()) {
			// Salvaguarda de ADR-013 §2: un listado vacío no desmarca nada.
			return 0;
		}
		String[] ocids = documented.toArray(String[]::new);
		jdbc.update("UPDATE spending_process SET in_documented_list = (ocid = ANY (?))",
				ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", ocids)));
		return (int) count("SELECT count(*) FROM spending_process WHERE in_documented_list");
	}

	@Override
	@Transactional(readOnly = true)
	public Set<String> allOcids() {
		return new LinkedHashSet<>(jdbc.queryForList("SELECT ocid FROM spending_process", String.class));
	}

	@Override
	@Transactional(readOnly = true)
	public List<DueRelease> due(Instant now, int limit) {
		return jdbc.query("""
				SELECT ocid, release_status, attempts FROM spending_process
				WHERE next_attempt_at <= ?
				ORDER BY next_attempt_at, file_number NULLS LAST
				LIMIT ?
				""", (rs, row) -> new DueRelease(rs.getString("ocid"),
						ReleaseStatus.valueOf(rs.getString("release_status")), rs.getInt("attempts")),
				Timestamp.from(now), limit);
	}

	@Override
	@Transactional
	public void recordRelease(String ocid, ReleaseStatus status, ReleaseContent content, Stage stage, int attempts,
			Instant attemptedAt, Instant nextAttemptAt) {
		if (content == null) {
			jdbc.update(UPDATE_WITHOUT_RELEASE, status.name(), attempts, Timestamp.from(attemptedAt),
					Timestamp.from(nextAttemptAt), ocid);
			return;
		}
		Tender tender = content.tender();
		BigDecimal awarded = awardedAmount(content);
		jdbc.update(UPDATE_WITH_RELEASE, ps -> {
			int i = 0;
			ps.setString(++i, status.name());
			ps.setInt(++i, attempts);
			ps.setTimestamp(++i, Timestamp.from(attemptedAt));
			ps.setTimestamp(++i, Timestamp.from(nextAttemptAt));
			setTimestamp(ps, ++i, content.publishedAt());
			ps.setString(++i, content.releaseId());
			ps.setString(++i, content.tags());
			ps.setString(++i, content.initiationType());
			ps.setString(++i, tender.title());
			ps.setString(++i, tender.description());
			ps.setString(++i, tender.status());
			ps.setString(++i, tender.procurementMethod());
			ps.setString(++i, tender.category());
			ps.setString(++i, tender.awardCriteria());
			setInteger(ps, ++i, tender.numberOfTenderers());
			setDecimal(ps, ++i, amountOf(tender.value()));
			setDecimal(ps, ++i, amountOf(tender.minValue()));
			ps.setString(++i, currencyOf(tender.value()));
			ps.setString(++i, content.procuringEntityName());
			ps.setString(++i, content.procuringEntityId());
			ps.setString(++i, stage == null ? null : stage.name());
			setDecimal(ps, ++i, awarded);
			ps.setString(++i, ocid);
		});
		replaceChildren(ocid, content);
	}

	@Override
	@Transactional
	public void recordFailedAttempt(String ocid, Instant attemptedAt, Instant nextAttemptAt) {
		// Ni estado ni intentos: un fallo de red no dice nada del proceso (ADR-017 §5).
		jdbc.update("UPDATE spending_process SET last_attempt_at = ?, next_attempt_at = ? WHERE ocid = ?",
				Timestamp.from(attemptedAt), Timestamp.from(nextAttemptAt), ocid);
	}

	/** Suma de las adjudicaciones activas con importe; {@code null} si no hay ninguna. */
	private static BigDecimal awardedAmount(ReleaseContent content) {
		BigDecimal total = null;
		for (Award award : content.awards()) {
			BigDecimal amount = amountOf(award.value());
			if (!award.isActive() || amount == null) {
				continue;
			}
			total = total == null ? amount : total.add(amount);
		}
		return total;
	}

	private void replaceChildren(String ocid, ReleaseContent content) {
		// El borrado en cascada de `spending_award` se lleva por delante sus partes.
		jdbc.update("DELETE FROM spending_award WHERE ocid = ?", ocid);
		jdbc.update("DELETE FROM spending_contract WHERE ocid = ?", ocid);
		jdbc.update("DELETE FROM spending_process_cpv WHERE ocid = ?", ocid);

		List<Award> awards = content.awards();
		if (!awards.isEmpty()) {
			jdbc.batchUpdate("""
					INSERT INTO spending_award (ocid, award_id, title, description, status, awarded_on, amount,
					    currency)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?)
					""", awards, awards.size(), (PreparedStatement ps, Award award) -> {
				ps.setString(1, ocid);
				ps.setString(2, award.awardId());
				ps.setString(3, award.title());
				ps.setString(4, award.description());
				ps.setString(5, award.status());
				setTimestamp(ps, 6, award.awardedOn());
				setDecimal(ps, 7, amountOf(award.value()));
				ps.setString(8, currencyOf(award.value()));
			});

			record PartyRow(String awardId, int ordinal, PartyIdentity party) {
			}
			List<PartyRow> parties = new ArrayList<>();
			for (Award award : awards) {
				int ordinal = 0;
				for (PartyIdentity party : award.parties()) {
					parties.add(new PartyRow(award.awardId(), ordinal++, party));
				}
			}
			if (!parties.isEmpty()) {
				jdbc.batchUpdate("""
						INSERT INTO spending_award_party (ocid, award_id, ordinal, tax_id, name, natural_person)
						VALUES (?, ?, ?, ?, ?, ?)
						""", parties, parties.size(), (PreparedStatement ps, PartyRow row) -> {
					ps.setString(1, ocid);
					ps.setString(2, row.awardId());
					ps.setInt(3, row.ordinal());
					// De una persona física no entra ni NIF ni nombre; la restricción de la tabla lo impone
					// también desde abajo (ADR-017 §2).
					ps.setString(4, row.party().taxId());
					ps.setString(5, row.party().name());
					ps.setBoolean(6, row.party().naturalPerson());
				});
			}
		}

		List<Contract> contracts = content.contracts();
		if (!contracts.isEmpty()) {
			jdbc.batchUpdate("""
					INSERT INTO spending_contract (ocid, contract_id, award_id, title, description, status,
					    signed_on, amount, currency, period_start, period_end)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					""", contracts, contracts.size(), (PreparedStatement ps, Contract contract) -> {
				ps.setString(1, ocid);
				ps.setString(2, contract.contractId());
				ps.setString(3, contract.awardId());
				ps.setString(4, contract.title());
				ps.setString(5, contract.description());
				ps.setString(6, contract.status());
				setTimestamp(ps, 7, contract.signedOn());
				setDecimal(ps, 8, amountOf(contract.value()));
				ps.setString(9, currencyOf(contract.value()));
				setTimestamp(ps, 10, contract.periodStart());
				setTimestamp(ps, 11, contract.periodEnd());
			});
		}

		List<Cpv> cpvs = content.cpvs();
		if (!cpvs.isEmpty()) {
			jdbc.batchUpdate("INSERT INTO spending_process_cpv (ocid, code, description, main) VALUES (?, ?, ?, ?)",
					cpvs, cpvs.size(), (PreparedStatement ps, Cpv cpv) -> {
						ps.setString(1, ocid);
						ps.setString(2, cpv.code());
						ps.setString(3, cpv.description());
						ps.setBoolean(4, cpv.main());
					});
		}
	}

	// --- lectura -----------------------------------------------------------------------------------------

	@Override
	@Transactional(readOnly = true)
	public long count() {
		return count("SELECT count(*) FROM spending_process");
	}

	@Override
	@Transactional(readOnly = true)
	public long count(ProcessQuery filters) {
		var where = new Where(filters);
		Long total = jdbc.queryForObject("SELECT count(*) FROM spending_process p" + where.clause(), Long.class,
				where.args());
		return total == null ? 0 : total;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<ContractingProcess> byOcid(String ocid) {
		List<ContractingProcess> found = jdbc.query(
				"SELECT " + PROCESS_COLUMNS + " FROM spending_process p WHERE p.ocid = ?",
				JdbcContractingProcessRepository::mapProcess, ocid);
		return found.isEmpty() ? Optional.empty() : Optional.of(withChildren(found).get(0));
	}

	@Override
	@Transactional(readOnly = true)
	public ProcessPage search(ProcessQuery query) {
		var where = new Where(query);
		Long total = jdbc.queryForObject("SELECT count(*) FROM spending_process p" + where.clause(), Long.class,
				where.args());
		String direction = query.ascending() ? "ASC" : "DESC";
		// Desempate estable por ocid: sin él, dos páginas de procesos publicados el mismo día pueden repetir filas.
		String sql = "SELECT " + PROCESS_COLUMNS + " FROM spending_process p" + where.clause() + " ORDER BY "
				+ query.sortField().column() + " " + direction + " NULLS LAST, p.ocid " + direction
				+ " LIMIT ? OFFSET ?";
		var args = new ArrayList<Object>(Arrays.asList(where.args()));
		args.add(query.size());
		args.add(query.page() * query.size());
		List<ContractingProcess> items = jdbc.query(sql, JdbcContractingProcessRepository::mapProcess,
				args.toArray());
		return new ProcessPage(withChildren(items), total == null ? 0 : total, query.page(), query.size());
	}

	/** Carga adjudicaciones, partes, contratos y CPV de la página con una consulta por tabla, no una por proceso. */
	private List<ContractingProcess> withChildren(List<ContractingProcess> items) {
		if (items.isEmpty()) {
			return items;
		}
		Object[] ocids = items.stream().map(ContractingProcess::ocid).toArray();
		String placeholders = String.join(",", items.stream().map(item -> "?").toList());

		Map<String, Map<String, List<PartyIdentity>>> partiesByProcess = new LinkedHashMap<>();
		jdbc.query("""
				SELECT ocid, award_id, tax_id, name, natural_person FROM spending_award_party
				WHERE ocid IN (%s) ORDER BY ocid, award_id, ordinal
				""".formatted(placeholders), rs -> {
			partiesByProcess.computeIfAbsent(rs.getString("ocid"), key -> new LinkedHashMap<>())
					.computeIfAbsent(rs.getString("award_id"), key -> new ArrayList<>())
					.add(new PartyIdentity(rs.getString("tax_id"), rs.getString("name"),
							rs.getBoolean("natural_person")));
		}, ocids);

		Map<String, List<Award>> awardsByProcess = new LinkedHashMap<>();
		jdbc.query("""
				SELECT ocid, award_id, title, description, status, awarded_on, amount, currency
				FROM spending_award WHERE ocid IN (%s) ORDER BY ocid, award_id
				""".formatted(placeholders), rs -> {
			String ocid = rs.getString("ocid");
			String awardId = rs.getString("award_id");
			List<PartyIdentity> parties = partiesByProcess.getOrDefault(ocid, Map.of())
					.getOrDefault(awardId, List.of());
			awardsByProcess.computeIfAbsent(ocid, key -> new ArrayList<>())
					.add(new Award(awardId, rs.getString("title"), rs.getString("description"),
							rs.getString("status"), instantOrNull(rs, "awarded_on"),
							Money.of(rs.getBigDecimal("amount"), rs.getString("currency")), parties));
		}, ocids);

		Map<String, List<Contract>> contractsByProcess = new LinkedHashMap<>();
		jdbc.query("""
				SELECT ocid, contract_id, award_id, title, description, status, signed_on, amount, currency,
				       period_start, period_end
				FROM spending_contract WHERE ocid IN (%s) ORDER BY ocid, contract_id
				""".formatted(placeholders), rs -> {
			contractsByProcess.computeIfAbsent(rs.getString("ocid"), key -> new ArrayList<>())
					.add(new Contract(rs.getString("contract_id"), rs.getString("award_id"), rs.getString("title"),
							rs.getString("description"), rs.getString("status"), instantOrNull(rs, "signed_on"),
							Money.of(rs.getBigDecimal("amount"), rs.getString("currency")),
							instantOrNull(rs, "period_start"), instantOrNull(rs, "period_end")));
		}, ocids);

		Map<String, List<Cpv>> cpvsByProcess = new LinkedHashMap<>();
		jdbc.query("""
				SELECT ocid, code, description, main FROM spending_process_cpv
				WHERE ocid IN (%s) ORDER BY ocid, main DESC, code
				""".formatted(placeholders), rs -> {
			cpvsByProcess.computeIfAbsent(rs.getString("ocid"), key -> new ArrayList<>())
					.add(new Cpv(rs.getString("code"), rs.getString("description"), rs.getBoolean("main")));
		}, ocids);

		return items.stream()
				.map(item -> new ContractingProcess(item.ocid(), item.fileNumber(), item.inDocumentedList(),
						item.releaseStatus(), item.attempts(), item.lastAttemptAt(), item.publishedAt(),
						item.releaseId(), item.tags(), item.initiationType(), item.tender(),
						item.procuringEntityName(), item.procuringEntityId(), item.stage(),
						awardsByProcess.getOrDefault(item.ocid(), List.of()),
						contractsByProcess.getOrDefault(item.ocid(), List.of()),
						cpvsByProcess.getOrDefault(item.ocid(), List.of()), item.firstSeenAt(), item.lastSeenAt()))
				.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public List<AggregationBucket> aggregate(AggregationAxis axis, ProcessQuery filters) {
		var where = new Where(filters);
		String join = switch (axis) {
			case CPV -> " JOIN spending_process_cpv c ON c.ocid = p.ocid";
			case SUPPLIER -> " JOIN spending_award a ON a.ocid = p.ocid"
					+ " JOIN spending_award_party sp ON sp.ocid = a.ocid AND sp.award_id = a.award_id";
			default -> "";
		};
		String key = switch (axis) {
			case YEAR -> "extract(year from p.published_at)::int::text";
			case TAG -> "p.tags";
			case TENDER_STATUS -> "p.tender_status";
			case PROCUREMENT_METHOD -> "p.procurement_method";
			case CATEGORY -> "p.procurement_category";
			case STAGE -> "p.stage";
			case PROCURING_ENTITY -> "p.procuring_entity_name";
			case RELEASE_STATUS -> "p.release_status";
			case CPV -> "c.code";
			// Las de persona física no tienen con qué agruparse: de ellas no se guarda identidad. Salen juntas,
			// contadas y sin nombre, que es exactamente lo que se sabe de ellas (ADR-017 §2).
			case SUPPLIER -> "coalesce(sp.tax_id, case when sp.natural_person then '(persona física)' "
					+ "else sp.name end)";
		};
		String year = axis == AggregationAxis.YEAR ? "extract(year from p.published_at)::int" : "NULL::int";
		String label = switch (axis) {
			case CPV -> "max(c.description)";
			case SUPPLIER -> "max(sp.name)";
			default -> "NULL::text";
		};
		// El importe licitado no se suma en el eje de adjudicatarias: un proceso con dos adjudicaciones lo
		// contaría dos veces, y son 195 (ADR-017 §7).
		String tendered = axis == AggregationAxis.SUPPLIER ? "NULL::numeric" : "sum(p.tender_amount)";
		String awarded = axis == AggregationAxis.SUPPLIER ? "sum(a.amount)" : "sum(p.awarded_amount)";
		String awards = axis == AggregationAxis.SUPPLIER ? "count(DISTINCT (a.ocid, a.award_id))"
				: "coalesce(sum(aw.n), 0)";
		String awardCounts = axis == AggregationAxis.SUPPLIER ? ""
				: " LEFT JOIN (SELECT ocid, count(*) AS n FROM spending_award GROUP BY ocid) aw ON aw.ocid = p.ocid";

		String sql = """
				SELECT %s AS bucket_key, %s AS bucket_year, %s AS bucket_label,
				       count(DISTINCT p.ocid) AS processes,
				       %s AS awards,
				       count(DISTINCT p.ocid) FILTER (WHERE p.release_status = 'PUBLISHED') AS with_release,
				       count(DISTINCT p.ocid) FILTER (
				           WHERE p.release_status = 'PUBLISHED' AND p.stage IS NULL) AS without_stage,
				       %s AS tendered_amount, %s AS awarded_amount
				FROM spending_process p%s%s%s
				GROUP BY 1, 2
				ORDER BY 1 NULLS LAST, 2
				""".formatted(key, year, label, awards, tendered, awarded, join, awardCounts, where.clause());
		return jdbc.query(sql,
				(rs, row) -> new AggregationBucket(rs.getString("bucket_key"), rs.getString("bucket_label"),
						nullableInt(rs, "bucket_year"), rs.getLong("processes"), rs.getLong("awards"),
						rs.getLong("with_release"), rs.getLong("without_stage"),
						rs.getBigDecimal("tendered_amount"), rs.getBigDecimal("awarded_amount")),
				where.args());
	}

	@Override
	@Transactional(readOnly = true)
	public SpendingTotals totals(ProcessQuery filters) {
		var where = new Where(filters);
		Object[] args = where.args();
		// Los recuentos sobre las tablas hijas se acotan al mismo conjunto de procesos que el resto del resumen.
		String scope = "ocid IN (SELECT p.ocid FROM spending_process p" + where.clause() + ")";

		var byStatus = new EnumMap<ReleaseStatus, Long>(ReleaseStatus.class);
		for (ReleaseStatus status : ReleaseStatus.values()) {
			byStatus.put(status, 0L);
		}
		jdbc.query("SELECT p.release_status, count(*) AS n FROM spending_process p" + where.clause()
				+ " GROUP BY p.release_status",
				(rs, row) -> Map.entry(ReleaseStatus.valueOf(rs.getString("release_status")), rs.getLong("n")),
				args).forEach(entry -> byStatus.put(entry.getKey(), entry.getValue()));

		long emptyContracts = count("""
				SELECT count(*) FROM spending_contract
				WHERE award_id IS NULL AND signed_on IS NULL AND status IS NULL AND description IS NULL AND \
				""" + scope, args);
		long awards = count("SELECT count(*) FROM spending_award WHERE " + scope, args);
		long naturalPersons = count("SELECT count(*) FROM spending_award_party WHERE natural_person AND " + scope,
				args);

		return jdbc.queryForObject("""
				SELECT count(*) AS processes,
				       count(*) FILTER (WHERE NOT p.in_documented_list) AS not_in_documented,
				       count(*) FILTER (
				           WHERE p.release_status = 'PUBLISHED' AND p.stage IS NULL) AS without_stage,
				       sum(p.tender_amount) AS tendered_amount, sum(p.awarded_amount) AS awarded_amount,
				       min(p.published_at) AS earliest, max(p.published_at) AS latest
				FROM spending_process p%s
				""".formatted(where.clause()),
				(rs, row) -> new SpendingTotals(rs.getLong("processes"), byStatus, rs.getLong("not_in_documented"),
						rs.getLong("without_stage"), emptyContracts, awards, naturalPersons,
						rs.getBigDecimal("tendered_amount"), rs.getBigDecimal("awarded_amount"),
						instantOrNull(rs, "earliest"), instantOrNull(rs, "latest")),
				args);
	}

	// --- construcción del filtro ------------------------------------------------------------------------

	/** Cláusula {@code WHERE} y sus argumentos, construida solo con nombres de columna fijos y parámetros. */
	private static final class Where {

		private final StringBuilder clause = new StringBuilder();
		private final List<Object> args = new ArrayList<>();

		Where(ProcessQuery query) {
			add("extract(year from p.published_at) = ?", query.year());
			add("p.tender_status = ?", query.tenderStatus());
			add("p.procurement_method = ?", query.procurementMethod());
			add("p.procurement_category = ?", query.category());
			add("p.stage = ?", query.stage() == null ? null : query.stage().name());
			add("p.release_status = ?", query.releaseStatus() == null ? null : query.releaseStatus().name());
			add("p.procuring_entity_name = ?", query.procuringEntity());
			add("p.in_documented_list = ?", query.inDocumentedList());
			add("EXISTS (SELECT 1 FROM spending_process_cpv x WHERE x.ocid = p.ocid AND x.code = ?)", query.cpv());
			add("""
					EXISTS (SELECT 1 FROM spending_award_party x WHERE x.ocid = p.ocid AND x.tax_id = ?)""",
					query.taxId());
			// `unaccent` no está instalada y no se va a instalar por un filtro: `ILIKE` basta sobre 8.001 filas.
			add("p.tender_title ILIKE ?", query.titleContains() == null ? null : "%" + query.titleContains() + "%");
			add("p.published_at >= ?", query.from() == null ? null : Timestamp.from(query.from()));
			add("p.published_at < ?", query.to() == null ? null : Timestamp.from(query.to()));
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

	private long count(String sql, Object[] args) {
		Long total = jdbc.queryForObject(sql, Long.class, args);
		return total == null ? 0 : total;
	}

	private static ContractingProcess mapProcess(ResultSet rs, int rowNumber) throws SQLException {
		var tender = new Tender(rs.getString("tender_title"), rs.getString("tender_description"),
				rs.getString("tender_status"), rs.getString("procurement_method"),
				rs.getString("procurement_category"), rs.getString("award_criteria"),
				nullableInt(rs, "number_of_tenderers"),
				Money.of(rs.getBigDecimal("tender_amount"), rs.getString("tender_currency")),
				Money.of(rs.getBigDecimal("tender_min_amount"), rs.getString("tender_currency")));
		String stage = rs.getString("stage");
		return new ContractingProcess(rs.getString("ocid"), nullableInt(rs, "file_number"),
				rs.getBoolean("in_documented_list"), ReleaseStatus.valueOf(rs.getString("release_status")),
				rs.getInt("attempts"), instantOrNull(rs, "last_attempt_at"), instantOrNull(rs, "published_at"),
				rs.getString("release_id"), rs.getString("tags"), rs.getString("initiation_type"), tender,
				rs.getString("procuring_entity_name"), rs.getString("procuring_entity_id"),
				stage == null ? null : Stage.valueOf(stage), List.of(), List.of(), List.of(),
				rs.getTimestamp("first_seen_at").toInstant(), rs.getTimestamp("last_seen_at").toInstant());
	}

	private static BigDecimal amountOf(Money money) {
		return money == null ? null : money.amount();
	}

	private static String currencyOf(Money money) {
		return money == null ? null : money.currency();
	}

	private static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
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

}
