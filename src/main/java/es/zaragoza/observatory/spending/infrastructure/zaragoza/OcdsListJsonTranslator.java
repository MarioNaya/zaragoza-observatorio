package es.zaragoza.observatory.spending.infrastructure.zaragoza;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Traductor anti-corrupción del censo de procesos (SPEC.md §4.4). Devuelve ocids distintos y en el orden en que
 * llegan, no elementos: contar filas en vez de identificadores es lo que hizo que S0.2 diera por buena una
 * contradicción durante tres meses (lección de S2.2 §10).
 * <p>
 * <b>Acepta las dos formas de la respuesta</b>, porque este endpoint tiene dos (S3.1 §2): un array JSON cuando
 * hay resultados y el envoltorio de la sede {@code {"totalCount":0,"start":0,"rows":0}} —sin {@code result}—
 * cuando no hay ninguno. Un traductor que solo espere el array revienta con la primera respuesta vacía.
 * <p>
 * Lo que no acepta es una respuesta que no se entienda: un objeto que no sea el envoltorio vacío hace fallar la
 * ingesta en vez de pasar por un censo de cero procesos, que es la salvaguarda de ADR-013 §2.
 */
@Component
public class OcdsListJsonTranslator {

	private static final Logger log = LoggerFactory.getLogger(OcdsListJsonTranslator.class);

	private final JsonMapper json;

	public OcdsListJsonTranslator(JsonMapper json) {
		this.json = json;
	}

	public List<String> translate(String body) {
		JsonNode root = json.readTree(body);
		if (root.isObject()) {
			JsonNode total = root.path("totalCount");
			if (total.isNumber() && total.asInt() == 0) {
				return List.of();
			}
			throw new IllegalArgumentException(
					"expected a JSON array of processes or the empty sede envelope, got an object with totalCount="
							+ (total.isMissingNode() ? "<missing>" : total.asString()));
		}
		if (!root.isArray()) {
			throw new IllegalArgumentException("expected a JSON array of processes, got " + root.getNodeType());
		}
		Set<String> seen = new LinkedHashSet<>(root.size());
		List<String> ocids = new ArrayList<>(root.size());
		int withoutOcid = 0;
		for (JsonNode node : root) {
			JsonNode ocid = node.path("ocid");
			if (!ocid.isString() || ocid.stringValue().isBlank()) {
				withoutOcid++;
				continue;
			}
			String value = ocid.stringValue().strip();
			if (seen.add(value)) {
				ocids.add(value);
			}
		}
		if (withoutOcid > 0) {
			log.warn("spending: {} elementos del listado llegaron sin ocid y se descartaron", withoutOcid);
		}
		if (ocids.size() < root.size()) {
			log.info("spending: el listado trae {} elementos y {} ocids distintos", root.size(), ocids.size());
		}
		return ocids;
	}

}
