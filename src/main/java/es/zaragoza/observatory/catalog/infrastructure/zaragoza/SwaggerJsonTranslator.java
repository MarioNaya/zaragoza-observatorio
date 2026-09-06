package es.zaragoza.observatory.catalog.infrastructure.zaragoza;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import es.zaragoza.observatory.catalog.domain.ApiEndpoint;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Anti-corruption layer del inventario de endpoints (SPEC.md §4.4): traduce el Swagger 2.0 real de
 * {@code sede/servicio/catalogo/api.json} (S0.1, S1.2; fixture {@code catalog/swagger-api.json}) a
 * {@link ApiEndpoint}. Forma verificada el 2026-09-06: raíz {@code {swagger, info, host, basePath, schemes, paths,
 * definitions}} sin lista de {@code tags}; cada {@code paths.<path>.<method>} trae {@code tags} (siempre uno),
 * {@code summary} (vacío en 73 de 497), {@code produces}, {@code parameters} y {@code responses}; 11 paths llegan
 * sin barra inicial. Un documento que no sea Swagger 2.0 o no tenga {@code paths} ni {@code host} hace fallar la
 * ingesta para que el cambio se vea.
 */
@Component
public class SwaggerJsonTranslator {

	static final Set<String> METHODS = Set.of("get", "post", "put", "delete", "patch", "head", "options");

	private final JsonMapper json;

	public SwaggerJsonTranslator(JsonMapper json) {
		this.json = json;
	}

	/** Una operación por tag declarado, en el orden del documento. */
	public List<ApiEndpoint> translate(String body, Instant seenAt) {
		JsonNode root = json.readTree(body);
		String version = CatalogJsonTranslator.text(root, "swagger");
		if (!"2.0".equals(version)) {
			throw new IllegalArgumentException("not a Swagger 2.0 document: swagger=" + version);
		}
		JsonNode paths = root.path("paths");
		if (!paths.isObject() || paths.isEmpty()) {
			throw new IllegalArgumentException("swagger document has no 'paths'");
		}
		String host = CatalogJsonTranslator.text(root, "host");
		if (host == null) {
			throw new IllegalArgumentException("swagger document has no 'host'");
		}
		String scheme = value(root.path("schemes").path(0));
		String basePath = CatalogJsonTranslator.text(root, "basePath");
		String base = (scheme == null ? "https" : scheme) + "://" + host + (basePath == null ? "" : basePath);
		List<ApiEndpoint> endpoints = new ArrayList<>(paths.size());
		int ordinal = 0;
		for (Map.Entry<String, JsonNode> p : paths.properties()) {
			String path = p.getKey().startsWith("/") ? p.getKey() : "/" + p.getKey();
			for (Map.Entry<String, JsonNode> op : p.getValue().properties()) {
				String method = op.getKey().toLowerCase(Locale.ROOT);
				if (!METHODS.contains(method)) {
					continue;
				}
				JsonNode tags = op.getValue().path("tags");
				if (!tags.isArray() || tags.isEmpty()) {
					throw new IllegalArgumentException("operation " + method + " " + path + " without tags");
				}
				for (JsonNode tag : tags) {
					endpoints.add(new ApiEndpoint(value(tag), method, path, base + path,
							CatalogJsonTranslator.text(op.getValue(), "summary"), ordinal++, seenAt, seenAt));
				}
			}
		}
		return endpoints;
	}

	private static String value(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return null;
		}
		String s = node.asString("").strip();
		return s.isEmpty() ? null : s;
	}

}
