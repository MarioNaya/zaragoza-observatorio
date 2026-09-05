package es.zaragoza.observatory.spikes;

import static es.zaragoza.observatory.spikes.support.SpikeFixtures.counts;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.heading;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.metric;
import static es.zaragoza.observatory.spikes.support.SpikeFixtures.table;
import static es.zaragoza.observatory.spikes.support.SpikeJson.text;
import static es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient.SEDE;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import es.zaragoza.observatory.spikes.support.SpikeFixtures;
import es.zaragoza.observatory.spikes.support.SpikeJson;
import es.zaragoza.observatory.spikes.support.ZaragozaSpikeClient;
import tools.jackson.databind.JsonNode;

/**
 * S0.2 — Contratación pública OCDS (SPEC.md §3). Informe: docs/spikes/S0.2-ocds.md.
 * <p>
 * Preguntas: cómo se pagina el listado (rows/before/after), volumen, campos disponibles en el detalle y, sobre todo,
 * proporción de procesos con localización utilizable (riesgo 1 de SPEC.md §7).
 */
@Tag("spike")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S02OcdsSpike {

	static final String ID = "S0.2-ocds";
	static final String OCDS = SEDE + "/contratacion-publica/ocds";
	static final Pattern OCID_NUMBER = Pattern.compile("ocds-1xraxc-(\\d+)-");
	static final int DETAIL_SAMPLE = 240;

	static final ZaragozaSpikeClient api = new ZaragozaSpikeClient();
	static List<String> ocids = new ArrayList<>();
	static List<String> oldOcids = new ArrayList<>();

	@BeforeAll
	static void start() {
		SpikeFixtures.startMetrics(ID);
	}

	@Test
	@Order(1)
	void listingAndPaging() {
		heading(ID, "Listado `contracting-process.json`: rows y start");
		for (String q : List.of("", "?rows=100", "?rows=500", "?rows=1000", "?rows=5000", "?rows=20000", "?rows=10&start=0",
				"?rows=10&start=10", "?rows=10&start=1000")) {
			var r = api.get(OCDS + "/contracting-process.json" + q);
			List<String> ids = ocidsOf(r);
			metric(ID, "`" + (q.isEmpty() ? "(sin parámetros)" : q) + "` -> " + r.status() + " devueltos=" + ids.size()
					+ " primero=" + (ids.isEmpty() ? "-" : ids.get(0)) + " último="
					+ (ids.isEmpty() ? "-" : ids.get(ids.size() - 1)) + " ms=" + r.elapsed().toMillis());
			if (ids.size() > ocids.size()) {
				ocids = ids;
				if (ids.size() <= 5000) {
					SpikeFixtures.save("ocds", "contracting-process-list.json", r.body());
				}
			}
		}
		var numbers = ocids.stream().map(S02OcdsSpike::ocidNumber).filter(n -> n >= 0).sorted().toList();
		metric(ID, "listado mayor: " + ocids.size() + " ocids; número mín=" + (numbers.isEmpty() ? "-" : numbers.get(0))
				+ " máx=" + (numbers.isEmpty() ? "-" : numbers.get(numbers.size() - 1)) + " distintos="
				+ new LinkedHashSet<>(ocids).size());
	}

	@Test
	@Order(2)
	void dateFilters() {
		heading(ID, "Filtros `before` / `after` (formato de fecha y semántica)");
		for (String q : List.of("?after=2026-08-01&rows=500", "?after=2026-08-01T00:00:00Z&rows=20000",
				"?after=2026-09-01T00:00:00Z&rows=20000", "?before=2020-01-01T00:00:00Z&rows=20000",
				"?after=2019-01-01T00:00:00Z&before=2019-12-31T23:59:59Z&rows=20000",
				"?after=2016-01-01T00:00:00Z&before=2016-12-31T23:59:59Z&rows=20000", "?before=2016-01-01T00:00:00Z&rows=20000",
				"?after=01/08/2026&rows=500")) {
			var r = api.get(OCDS + "/contracting-process.json" + q);
			List<String> ids = ocidsOf(r);
			metric(ID, "`" + q + "` -> " + r.status() + " devueltos=" + ids.size() + " primero="
					+ (ids.isEmpty() ? "-" : ids.get(0)) + " último=" + (ids.isEmpty() ? "-" : ids.get(ids.size() - 1))
					+ " ms=" + r.elapsed().toMillis() + (r.status() != 200 ? " body=" + snippet(r.body()) : ""));
			if (q.startsWith("?before=2016-01-01T00") && !ids.isEmpty()) {
				oldOcids = ids;
			}
		}
	}

	@Test
	@Order(3)
	void detailInventory() {
		heading(ID, "Detalle `contracting-process/{ocid}.json`: muestra");
		List<String> sample = sample(ocids, DETAIL_SAMPLE - Math.min(80, oldOcids.size()));
		sample.addAll(sample(oldOcids, 80));
		List<JsonNode> docs = new ArrayList<>();
		List<Long> millis = new ArrayList<>();
		Map<String, Integer> statuses = new TreeMap<>();
		Map<String, int[]> byRange = new TreeMap<>();
		int saved = 0;
		for (String ocid : sample) {
			var r = api.get(OCDS + "/contracting-process/" + ocid + ".json");
			statuses.merge(String.valueOf(r.status()), 1, Integer::sum);
			millis.add(r.elapsed().toMillis());
			long number = ocidNumber(ocid);
			long lower = (number / 500) * 500;
			int[] bucket = byRange.computeIfAbsent(String.format("%05d-%05d", lower, lower + 499), k -> new int[2]);
			bucket[r.status() == 200 ? 0 : 1]++;
			if (r.status() == 200 && r.isJson()) {
				docs.add(r.json());
				if (saved < 12) {
					SpikeFixtures.save("ocds", "contracting-process-" + ocid + ".json", r.body());
					saved++;
				}
			}
		}
		millis.sort(Long::compare);
		List<List<String>> rangeRows = new ArrayList<>();
		byRange.forEach((k, v) -> rangeRows.add(List.of(k, String.valueOf(v[0]), String.valueOf(v[1]))));
		table(ID, List.of("rango numérico del ocid", "200", "otros (404…)"), rangeRows);
		metric(ID, "peticiones=" + sample.size() + " estados=" + statuses + " ms p50=" + SpikeJson.percentile(millis, 0.5)
				+ " p90=" + SpikeJson.percentile(millis, 0.9) + " máx=" + SpikeJson.percentile(millis, 1.0));

		heading(ID, "Estructura del release package (documentos=" + docs.size() + ")");
		Map<String, Integer> releasesPerDoc = new TreeMap<>();
		Map<String, Integer> tags = new TreeMap<>();
		Map<String, Integer> tenderStatus = new TreeMap<>();
		Map<String, Integer> method = new TreeMap<>();
		Map<String, Integer> category = new TreeMap<>();
		Map<String, Integer> roles = new TreeMap<>();
		Map<String, Integer> years = new TreeMap<>();
		Map<String, Integer> publishedYears = new TreeMap<>();
		int withAwards = 0, withContracts = 0, withTenderValue = 0, withAwardValue = 0, withContractValue = 0,
				withSuppliers = 0, withPlanning = 0, textMentionsTerritory = 0, withDocuments = 0;
		Pattern territory = Pattern.compile("barrio|junta|distrito|calle|avda|avenida|plaza|c/", Pattern.CASE_INSENSITIVE);
		for (JsonNode doc : docs) {
			JsonNode releases = doc.path("releases");
			releasesPerDoc.merge(String.valueOf(releases.size()), 1, Integer::sum);
			LocalDateTime published = SpikeJson.date(doc.path("publishedDate"));
			publishedYears.merge(published == null ? "(sin fecha)" : String.valueOf(published.getYear()), 1, Integer::sum);
			boolean awards = false, contracts = false, tenderValue = false, awardValue = false, contractValue = false,
					suppliers = false, planning = false, mentions = false, documents = false;
			for (JsonNode rel : releases) {
				for (JsonNode t : rel.path("tag")) {
					tags.merge(text(t), 1, Integer::sum);
				}
				LocalDateTime date = SpikeJson.date(rel.path("date"));
				years.merge(date == null ? "(sin fecha)" : String.valueOf(date.getYear()), 1, Integer::sum);
				JsonNode tender = rel.path("tender");
				tenderStatus.merge(text(tender, "status").isEmpty() ? "(vacío)" : text(tender, "status"), 1, Integer::sum);
				method.merge(text(tender, "procurementMethod").isEmpty() ? "(vacío)" : text(tender, "procurementMethod"), 1,
						Integer::sum);
				category.merge(text(tender, "mainProcurementCategory").isEmpty() ? "(vacío)"
						: text(tender, "mainProcurementCategory"), 1, Integer::sum);
				for (JsonNode party : rel.path("parties")) {
					for (JsonNode role : party.path("roles")) {
						roles.merge(text(role), 1, Integer::sum);
					}
				}
				awards |= rel.path("awards").size() > 0;
				contracts |= rel.path("contracts").size() > 0;
				planning |= !rel.path("planning").isMissingNode();
				documents |= tender.path("documents").size() > 0;
				tenderValue |= !tender.path("value").path("amount").isMissingNode();
				for (JsonNode a : rel.path("awards")) {
					awardValue |= !a.path("value").path("amount").isMissingNode();
					suppliers |= a.path("suppliers").size() > 0;
				}
				for (JsonNode c : rel.path("contracts")) {
					contractValue |= !c.path("value").path("amount").isMissingNode();
				}
				mentions |= territory.matcher(text(tender, "title") + " " + text(tender, "description")).find();
			}
			withAwards += awards ? 1 : 0;
			withContracts += contracts ? 1 : 0;
			withTenderValue += tenderValue ? 1 : 0;
			withAwardValue += awardValue ? 1 : 0;
			withContractValue += contractValue ? 1 : 0;
			withSuppliers += suppliers ? 1 : 0;
			withPlanning += planning ? 1 : 0;
			withDocuments += documents ? 1 : 0;
			textMentionsTerritory += mentions ? 1 : 0;
		}
		counts(ID, "releases por package", releasesPerDoc);
		counts(ID, "releases[].tag", tags);
		counts(ID, "releases[].date (año)", years);
		counts(ID, "publishedDate (año)", publishedYears);
		counts(ID, "tender.status", tenderStatus);
		counts(ID, "tender.procurementMethod", method);
		counts(ID, "tender.mainProcurementCategory", category);
		counts(ID, "parties[].roles", roles);
		metric(ID, "documentos con awards=" + withAwards + " contracts=" + withContracts + " planning=" + withPlanning
				+ " tender.value=" + withTenderValue + " awards[].value=" + withAwardValue + " contracts[].value="
				+ withContractValue + " awards[].suppliers=" + withSuppliers + " tender.documents=" + withDocuments);
		metric(ID, "documentos cuyo título/descripción menciona barrio/junta/distrito/calle/plaza: " + textMentionsTerritory
				+ " (pista de localización solo textual)");

		heading(ID, "Cobertura de caminos JSON (top 90)");
		Map<String, Integer> coverage = SpikeJson.coverage(docs);
		metric(ID, "caminos distintos=" + coverage.size());
		table(ID, List.of("camino", "docs"), SpikeJson.rows(coverage, 90));

		heading(ID, "Caminos relacionados con localización (TODOS los que aparecen)");
		Map<String, Integer> location = SpikeJson.filter(coverage,
				"location|address|geo|latitude|longitude|\\blat\\b|\\blon\\b|coord|region|postal|locality|street|place|ubicac|emplaz|nuts|country");
		if (location.isEmpty()) {
			metric(ID, "**ninguno**: no hay campos de localización en la muestra");
		}
		else {
			table(ID, List.of("camino", "docs"), SpikeJson.rows(location, 100));
		}
	}

	@Test
	@Order(4)
	void siblingEndpoints() {
		heading(ID, "Endpoints hermanos: award, contract, tender, organisation");
		for (String res : List.of("award", "contract", "tender", "organisation")) {
			var list = api.get(OCDS + "/" + res + ".json?rows=5");
			JsonNode arr = list.status() == 200 && list.isJson() ? list.json() : null;
			int n = arr != null && arr.isArray() ? arr.size() : -1;
			Set<String> keys = arr != null && n > 0 ? SpikeJson.paths(arr.get(0)) : Set.of();
			metric(ID, "`" + res + ".json?rows=5` -> " + list.status() + " devueltos=" + n + " ms="
					+ list.elapsed().toMillis() + " caminos del primero=" + keys);
			if (n > 0) {
				SpikeFixtures.save("ocds", res + "-list.json", list.body());
				String id = text(arr.get(0), "id");
				if (!id.isEmpty()) {
					var detail = api.get(OCDS + "/" + res + "/" + id + ".json");
					metric(ID, "`" + res + "/" + id + ".json` -> " + detail.status() + " bytes=" + detail.body().length()
							+ " caminos=" + (detail.status() == 200 && detail.isJson()
									? snippet(SpikeJson.paths(detail.json()).toString(), 700) : snippet(detail.body())));
					if (detail.status() == 200 && detail.isJson()) {
						SpikeFixtures.save("ocds", res + "-detail.json", detail.body());
					}
					if (res.equals("organisation")) {
						for (String sub : List.of("award", "contracting-process")) {
							var s = api.get(OCDS + "/organisation/" + id + "/" + sub + ".json?rows=5");
							metric(ID, "`organisation/" + id + "/" + sub + ".json?rows=5` -> " + s.status() + " devueltos="
									+ (s.status() == 200 && s.isJson() && s.json().isArray() ? s.json().size() : -1));
						}
					}
				}
			}
		}
		for (String q : List.of("contract.json?status=active&rows=5", "contract.json?status=terminated&rows=5",
				"tender.json?status=complete&rows=5", "tender.json?status=active&rows=5",
				"award.json?after=2026-08-01T00:00:00Z&rows=5000", "contract.json?after=2026-08-01T00:00:00Z&rows=5000",
				"organisation.json?rows=5000")) {
			var r = api.get(OCDS + "/" + q);
			metric(ID, "`" + q + "` -> " + r.status() + " devueltos="
					+ (r.status() == 200 && r.isJson() && r.json().isArray() ? r.json().size() : -1)
					+ (r.status() != 200 || !r.isJson() ? " body=" + snippet(r.body()) : ""));
		}
	}

	@Test
	@Order(5)
	void conditionalHeaders() {
		heading(ID, "Cabeceras condicionales");
		String ocid = ocids.isEmpty() ? "ocds-1xraxc-8136-ContractingProcess" : ocids.get(0);
		var detail = api.get(OCDS + "/contracting-process/" + ocid + ".json");
		metric(ID, detail.summary());
		var ims = api.get(OCDS + "/contracting-process/" + ocid + ".json",
				h -> h.set("If-Modified-Since", "Fri, 04 Sep 2026 00:00:00 GMT"));
		metric(ID, "If-Modified-Since -> " + ims.status());
		var list = api.get(OCDS + "/contracting-process.json?rows=1");
		metric(ID, list.summary());
	}

	// --- helpers -----------------------------------------------------------------------------------------------

	static List<String> ocidsOf(ZaragozaSpikeClient.Response r) {
		List<String> ids = new ArrayList<>();
		if (r.status() == 200 && r.isJson()) {
			JsonNode n = r.json();
			if (n.isArray()) {
				for (JsonNode item : n) {
					ids.add(text(item, "ocid"));
				}
			}
		}
		return ids;
	}

	static long ocidNumber(String ocid) {
		Matcher m = OCID_NUMBER.matcher(ocid);
		return m.find() ? Long.parseLong(m.group(1)) : -1;
	}

	static List<String> sample(List<String> source, int max) {
		List<String> out = new ArrayList<>();
		if (source.isEmpty() || max <= 0) {
			return out;
		}
		double step = Math.max(1.0, (double) source.size() / max);
		for (double i = 0; i < source.size() && out.size() < max; i += step) {
			out.add(source.get((int) i));
		}
		return out;
	}

	static String snippet(String body) {
		return snippet(body, 160);
	}

	static String snippet(String body, int max) {
		String s = body.replaceAll("\\s+", " ");
		return s.length() > max ? s.substring(0, max) + "…" : s;
	}

}
