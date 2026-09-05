package es.zaragoza.observatory.spikes.support;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/** Utilidades de inspección de JSON para los spikes (Jackson 3, paquete tools.jackson). */
public final class SpikeJson {

	private SpikeJson() {
	}

	/** Texto de un nodo escalar; objetos y arrays se serializan; missing/null devuelven "". */
	public static String text(JsonNode n) {
		if (n == null || n.isMissingNode() || n.isNull()) {
			return "";
		}
		if (n.isString()) {
			return n.stringValue();
		}
		return n.toString();
	}

	public static String text(JsonNode n, String field) {
		return text(n.path(field));
	}

	public static boolean isEmpty(JsonNode v) {
		return v == null || v.isNull() || v.isMissingNode() || (v.isString() && v.stringValue().isBlank())
				|| ((v.isArray() || v.isObject()) && v.size() == 0);
	}

	/** Todos los caminos (a.b[].c) presentes en el documento, con arrays marcados como []. */
	public static Set<String> paths(JsonNode node) {
		var out = new TreeSet<String>();
		walk(node, "", out);
		return out;
	}

	private static void walk(JsonNode n, String prefix, Set<String> out) {
		if (n.isObject()) {
			n.properties().forEach(e -> {
				String p = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
				out.add(p);
				walk(e.getValue(), p, out);
			});
		}
		else if (n.isArray()) {
			String p = prefix + "[]";
			for (JsonNode child : n) {
				walk(child, p, out);
			}
		}
	}

	/** Número de documentos en los que aparece cada camino. */
	public static Map<String, Integer> coverage(Collection<JsonNode> docs) {
		Map<String, Integer> counts = new TreeMap<>();
		for (JsonNode d : docs) {
			for (String p : paths(d)) {
				counts.merge(p, 1, Integer::sum);
			}
		}
		return counts;
	}

	public static Map<String, Integer> filter(Map<String, Integer> counts, String regex) {
		Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
		Map<String, Integer> out = new TreeMap<>();
		counts.forEach((k, v) -> {
			if (pattern.matcher(k).find()) {
				out.put(k, v);
			}
		});
		return out;
	}

	/** Filas (camino, n) ordenadas por n desc y camino asc, limitadas. */
	public static List<List<String>> rows(Map<String, Integer> counts, int limit) {
		return counts.entrySet().stream()
				.sorted((a, b) -> {
					int c = Integer.compare(b.getValue(), a.getValue());
					return c != 0 ? c : a.getKey().compareTo(b.getKey());
				})
				.limit(limit)
				.map(e -> List.of(e.getKey(), String.valueOf(e.getValue())))
				.toList();
	}

	/** Acepta 2019-10-23T00:00:00, 2026-09-04T12:07:57Z, 2026-09-04T12:07:57+02:00 y 2019-10-23. */
	public static LocalDateTime date(JsonNode node) {
		String s = text(node).trim();
		if (s.isEmpty()) {
			return null;
		}
		try {
			return LocalDateTime.parse(s);
		}
		catch (DateTimeParseException e) {
			try {
				return OffsetDateTime.parse(s).toLocalDateTime();
			}
			catch (DateTimeParseException e2) {
				try {
					return LocalDate.parse(s.substring(0, Math.min(10, s.length()))).atStartOfDay();
				}
				catch (RuntimeException e3) {
					return null;
				}
			}
		}
	}

	/** Percentil simple sobre una lista ya ordenada. */
	public static long percentile(List<Long> sorted, double p) {
		if (sorted.isEmpty()) {
			return -1;
		}
		int idx = (int) Math.min(sorted.size() - 1, Math.floor(p * sorted.size()));
		return sorted.get(idx);
	}

}
