package es.zaragoza.observatory.catalog.infrastructure.zaragoza;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import es.zaragoza.observatory.catalog.domain.Dataset;
import es.zaragoza.observatory.catalog.domain.Dataset.Distribution;
import es.zaragoza.observatory.catalog.domain.Periodicity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Anti-corruption layer del catálogo (SPEC.md §4.4): traduce el JSON real de {@code catalogo.json} (S0.1,
 * fixtures {@code catalogo-rows500-fl.json}) al modelo {@link Dataset}. Si cambia el esquema upstream, cambia
 * solo esta clase; una ficha sin {@code id} o sin {@code title} hace fallar la ingesta para que el cambio se vea.
 */
@Component
public class CatalogJsonTranslator {

	private final JsonMapper json;

	public CatalogJsonTranslator(JsonMapper json) {
		this.json = json;
	}

	/** Traduce una página {@code {"totalCount":N,"start":S,"rows":R,"result":[...]}}. */
	public List<Dataset> translate(String body, Instant seenAt) {
		JsonNode root = json.readTree(body);
		JsonNode result = root.path("result");
		if (!result.isArray()) {
			throw new IllegalArgumentException("catalog page has no 'result' array");
		}
		List<Dataset> datasets = new ArrayList<>(result.size());
		for (JsonNode node : result) {
			datasets.add(toDataset(node, seenAt));
		}
		return datasets;
	}

	Dataset toDataset(JsonNode node, Instant seenAt) {
		JsonNode id = node.path("id");
		if (!id.isNumber()) {
			throw new IllegalArgumentException("catalog entry without numeric 'id': " + node.path("title").asString(""));
		}
		String title = text(node, "title");
		if (title == null) {
			throw new IllegalArgumentException("catalog entry " + id.asInt() + " without 'title'");
		}
		String periodicity = text(node, "accrualPeriodicity");
		List<Distribution> distributions = new ArrayList<>();
		for (JsonNode f : node.path("formato")) {
			distributions.add(new Distribution(f.path("id").isNumber() ? f.path("id").asInt() : null,
					text(f, "mediaType"), text(f, "accessURL"), text(f, "downloadURL"), text(f, "title"),
					text(f, "wfsFeatureName")));
		}
		return new Dataset(id.asInt(), title, text(node, "description_basic"), dateTime(text(node, "issued")),
				dateTime(text(node, "modified")), dateTime(text(node, "lastUpdated")), periodicity,
				Periodicity.days(periodicity), text(node, "status"), yesNo(text(node, "geo")),
				yesNo(text(node, "abierto")), node.path("explorable").asBoolean(false), apiTag(distributions),
				distributions, seenAt, seenAt);
	}

	static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		String s = value.asString("").strip();
		return s.isEmpty() ? null : s;
	}

	/** {@code S}/{@code N} del catálogo; cualquier otra cosa (incluido vacío) es «no declarado». */
	static Boolean yesNo(String value) {
		if (value == null) {
			return null;
		}
		return switch (value.strip().toUpperCase()) {
			case "S" -> Boolean.TRUE;
			case "N" -> Boolean.FALSE;
			default -> null;
		};
	}

	/** Formatos observados: {@code 2019-10-23T00:00:00} (sin zona), con offset, o solo fecha. */
	static LocalDateTime dateTime(String value) {
		if (value == null) {
			return null;
		}
		try {
			return LocalDateTime.parse(value);
		}
		catch (DateTimeParseException notLocal) {
			try {
				return OffsetDateTime.parse(value).toLocalDateTime();
			}
			catch (DateTimeParseException notOffset) {
				try {
					return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value).atStartOfDay();
				}
				catch (DateTimeParseException notDate) {
					return null;
				}
			}
		}
	}

	/** Tag del Swagger: parte tras {@code #/} del {@code accessURL} de la distribución {@code application/api} (S0.1). */
	static String apiTag(List<Distribution> distributions) {
		for (Distribution d : distributions) {
			if (d.isApi() && d.accessUrl() != null) {
				int hash = d.accessUrl().indexOf("#/");
				if (hash >= 0) {
					String tag = URLDecoder.decode(d.accessUrl().substring(hash + 2), StandardCharsets.UTF_8).strip();
					if (!tag.isEmpty()) {
						return tag;
					}
				}
			}
		}
		return null;
	}

}
