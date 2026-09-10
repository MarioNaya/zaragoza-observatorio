package es.zaragoza.observatory.spending.infrastructure.zaragoza;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import es.zaragoza.observatory.spending.domain.BudgetAmounts;
import es.zaragoza.observatory.spending.domain.BudgetHeading;
import es.zaragoza.observatory.spending.domain.BudgetLine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Traductor anti-corrupción de una página de partidas (SPEC.md §4.4). Lo que hace, y por qué:
 * <ul>
 * <li><b>Ignora {@code id}</b>. El identificador del origen es {@code fecha + "-" + concepto}: la clave con la
 * fecha pegada delante. La clave del modelo es {@code (snapshotDate, concept)} (S3.2 §6).</li>
 * <li><b>Recorta los códigos</b>. Los ejercicios antiguos los publican rellenos con espacios
 * ({@code "20200  "}, epígrafes con decenas de blancos). Recortar es normalizar formato, no interpretar
 * (regla 6).</li>
 * <li><b>Deja nulo lo que no existe</b>. {@code programa} no existe en 2010-2014 y llega incompleto en otros
 * seis ejercicios: un nulo ahí es el dato, no un fallo de carga (S3.2 §7).</li>
 * <li><b>No guarda el nombre de la partida cuando nombra a una persona física</b>
 * ({@link BudgetHeading}, S3.2 §8). Es la mitad de la garantía que vive en el traductor; la otra la impone la
 * base de datos.</li>
 * <li><b>Conserva los ceros</b>. Una partida sin ejecutar tiene 0 € de obligación neta, y eso es un dato: aquí
 * el cero no es una ausencia, al revés que en un importe de contratación.</li>
 * </ul>
 * Una fila sin {@code concepto} no se puede guardar —no tiene clave— y se descarta contándolo.
 */
@Component
public class BudgetLineJsonTranslator {

	private static final Logger log = LoggerFactory.getLogger(BudgetLineJsonTranslator.class);

	private final JsonMapper json;

	public BudgetLineJsonTranslator(JsonMapper json) {
		this.json = json;
	}

	/** Las partidas de una página. El envoltorio se espera de la sede: {@code {totalCount,start,rows,result}}. */
	public List<BudgetLine> translate(LocalDate date, String body) {
		JsonNode root = json.readTree(body);
		JsonNode result = root.path("result");
		if (!result.isArray()) {
			throw new IllegalArgumentException(
					"expected the sede envelope with result[] of budget lines, got " + root.getNodeType());
		}
		var lines = new ArrayList<BudgetLine>(result.size());
		int withoutConcept = 0;
		for (JsonNode node : result) {
			String concept = text(node, "concepto");
			if (concept == null) {
				withoutConcept++;
				continue;
			}
			String heading = raw(node, "partida");
			lines.add(new BudgetLine(date, concept, text(node, "idArea"), text(node, "area"),
					integer(node, "idCapitulo"), text(node, "capitulo"), text(node, "idPrograma"),
					text(node, "programa"), text(node, "idOrgano"), text(node, "organo"), text(node, "idEpigrafe"),
					text(node, "epigrafe"), BudgetHeading.sanitize(heading),
					BudgetHeading.namesNaturalPerson(heading), amounts(node)));
		}
		if (withoutConcept > 0) {
			log.warn("spending: {} partidas de la instantánea {} llegaron sin concepto y se descartaron",
					withoutConcept, date);
		}
		return lines;
	}

	/** {@code totalCount} de la página, o -1 si la fuente no lo trae. */
	public int totalCount(String body) {
		JsonNode total = json.readTree(body).path("totalCount");
		return total.isNumber() ? total.asInt() : -1;
	}

	private static BudgetAmounts amounts(JsonNode node) {
		return new BudgetAmounts(decimal(node, "creditoInicial"), decimal(node, "creditoModificacion"),
				decimal(node, "creditoDefinitivo"), decimal(node, "gastoComprometido"),
				decimal(node, "obligacionNeta"), decimal(node, "pagoNeto"), decimal(node, "obligacionPendientePago"),
				decimal(node, "remanenteDeCredito"));
	}

	private static BigDecimal decimal(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isNumber() ? value.decimalValue() : BigDecimal.ZERO;
	}

	private static Integer integer(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isNumber() ? value.asInt() : null;
	}

	private static String raw(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isString() ? value.stringValue() : null;
	}

	/** Texto recortado, o {@code null} si el campo no viene o viene en blanco. */
	private static String text(JsonNode node, String field) {
		String value = raw(node, field);
		if (value == null) {
			return null;
		}
		String trimmed = value.strip();
		return trimmed.isEmpty() ? null : trimmed;
	}

}
