package es.zaragoza.observatory.catalog.infrastructure.zaragoza;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import es.zaragoza.observatory.catalog.domain.FederatedDataset;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Anti-corruption layer de la federación (SPEC.md §4.4): traduce una página de la Linked Data API de datos.gob.es
 * ({@code apidata/catalog/dataset/publisher/L01502973.json}, S1.3; fixture {@code datos-gob-es-page0.json}) a
 * {@link FederatedDataset}. Cada item trae {@code identifier} con la URL municipal de la ficha
 * ({@code …/servicio/catalogo/<id>}, 369 de 369), {@code _about} (la ficha en datos.gob.es) y {@code title} como
 * lista de {@code {_value, _lang}}. Un item sin id municipal o sin {@code _about} hace fallar la ingesta para que
 * el cambio se vea.
 */
@Component
public class FederationJsonTranslator {

	static final Pattern CATALOG_ID = Pattern.compile("/espacio-de-datos/servicio/catalogo/(\\d+)/?$");

	private final JsonMapper json;

	public FederationJsonTranslator(JsonMapper json) {
		this.json = json;
	}

	/** Traduce {@code {"result":{"items":[...]}}}; una página más allá del final trae {@code items} vacío. */
	public List<FederatedDataset> translate(String body, Instant seenAt) {
		JsonNode items = json.readTree(body).path("result").path("items");
		if (!items.isArray()) {
			throw new IllegalArgumentException("federation page has no 'result.items' array");
		}
		List<FederatedDataset> datasets = new ArrayList<>(items.size());
		for (JsonNode item : items) {
			String identifier = CatalogJsonTranslator.text(item, "identifier");
			Matcher m = identifier == null ? null : CATALOG_ID.matcher(identifier.strip());
			if (m == null || !m.find()) {
				throw new IllegalArgumentException("federated dataset without municipal id in 'identifier': "
						+ identifier + " (" + CatalogJsonTranslator.text(item, "_about") + ")");
			}
			datasets.add(new FederatedDataset(Integer.parseInt(m.group(1)), CatalogJsonTranslator.text(item, "_about"),
					title(item.path("title")), seenAt, seenAt));
		}
		return datasets;
	}

	/** {@code title} llega como {@code [{"_value":…,"_lang":"es"}]}; se tolera también un objeto o una cadena. */
	static String title(JsonNode title) {
		if (title.isArray()) {
			for (JsonNode t : title) {
				String value = title(t);
				if (value != null) {
					return value;
				}
			}
			return null;
		}
		if (title.isObject()) {
			return CatalogJsonTranslator.text(title, "_value");
		}
		if (title.isString()) {
			String s = title.stringValue().strip();
			return s.isEmpty() ? null : s;
		}
		return null;
	}

}
