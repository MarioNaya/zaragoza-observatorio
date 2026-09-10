package es.zaragoza.observatory.spending.infrastructure.zaragoza;

import java.time.LocalDate;
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
 * Traductor anti-corrupción del censo de instantáneas (SPEC.md §4.4). Convierte el {@code result} —una lista de
 * <b>URL</b>, no de objetos— en fechas distintas y ordenadas de la más antigua a la más reciente.
 * <p>
 * De cada URL solo se acepta la fecha del final y solo si tiene la forma {@code yyyyMMdd}: el censo es entrada
 * externa y no se mete en una ruta lo que venga (misma cautela que {@code SpendingProperties.detailUrl}).
 * <p>
 * Un censo <b>sin {@code result}</b> hace fallar la ingesta en vez de pasar por un censo de cero instantáneas,
 * que es la salvaguarda de ADR-013 §2: una fuente que se rompe no puede parecerse a una fuente vacía.
 */
@Component
public class BudgetDatesJsonTranslator {

	private static final Logger log = LoggerFactory.getLogger(BudgetDatesJsonTranslator.class);

	private final JsonMapper json;

	public BudgetDatesJsonTranslator(JsonMapper json) {
		this.json = json;
	}

	public List<LocalDate> translate(String body) {
		JsonNode root = json.readTree(body);
		JsonNode result = root.path("result");
		if (!result.isArray()) {
			throw new IllegalArgumentException(
					"expected the sede envelope with result[] of snapshot URLs, got " + root.getNodeType());
		}
		Set<LocalDate> seen = new LinkedHashSet<>(result.size());
		int unparseable = 0;
		for (JsonNode node : result) {
			LocalDate date = BudgetListing.dateIn(node.isString() ? node.stringValue() : null);
			if (date == null) {
				unparseable++;
				continue;
			}
			seen.add(date);
		}
		if (unparseable > 0) {
			log.warn("spending: {} URL del censo del presupuesto no terminan en una fecha yyyyMMdd", unparseable);
		}
		List<LocalDate> dates = new ArrayList<>(seen);
		dates.sort(LocalDate::compareTo);
		return dates;
	}

}
