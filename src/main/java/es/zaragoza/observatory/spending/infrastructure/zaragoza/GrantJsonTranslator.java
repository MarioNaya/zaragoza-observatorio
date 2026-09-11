package es.zaragoza.observatory.spending.infrastructure.zaragoza;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import es.zaragoza.observatory.spending.domain.Grant;
import es.zaragoza.observatory.spending.domain.GrantBeneficiary;
import es.zaragoza.observatory.spending.domain.GrantCall;
import es.zaragoza.observatory.spending.domain.GrantIdentity;
import es.zaragoza.observatory.spending.domain.GrantLinks;
import es.zaragoza.observatory.spending.domain.GrantTitle;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Traductor anti-corrupción de la familia de subvenciones (SPEC.md §4.4). Traduce los cuatro recursos porque los
 * cuatro son el mismo servicio con las mismas mañas, y separarlos repartiría la misma regla por cuatro sitios.
 * <p>
 * Lo que hace, y por qué:
 * <ul>
 * <li><b>No lee nunca {@code adjudicatario}</b>, ni siquiera si llegara. La proyección ya impide que llegue
 * (ADR-018 §3), y esto es la segunda garantía: dos, no una, como en {@code citizen}.</li>
 * <li><b>Redacta el documento de identidad del título</b> ({@link GrantTitle}). 2.378 DNI y 381 NIE, todos con
 * letra de control válida (S3.3 §5).</li>
 * <li><b>Aplica la regla del beneficiario</b> ({@link GrantIdentity}): seudónimo siempre, identidad solo si no
 * es una persona física, y persona física por cualquiera de las dos señales.</li>
 * <li><b>No lee ningún dato de contacto</b> del directorio: ni domicilio, ni código postal, ni teléfono, ni
 * correo, ni web (ADR-018 §4).</li>
 * <li><b>Acepta las dos formas del envoltorio</b>, {@code result} y {@code records}: el mismo tag sirve las dos
 * (S3.3 §1).</li>
 * <li><b>Deja pasar las fechas imposibles</b> (años 0002, 0019 y 0022, siete registros). No se corrigen ni se
 * tiran: su grupo sale como tal, igual que la licencia de 2033 de S2.4 (regla 6).</li>
 * </ul>
 */
@Component
public class GrantJsonTranslator {

	private static final Logger log = LoggerFactory.getLogger(GrantJsonTranslator.class);

	private final JsonMapper json;

	public GrantJsonTranslator(JsonMapper json) {
		this.json = json;
	}

	/** Las concesiones de una página del censo de la v1. */
	public List<Grant> grants(String body) {
		var grants = new ArrayList<Grant>();
		int withoutId = 0;
		for (JsonNode node : items(body)) {
			long id = node.path("id").asLong(0);
			if (id <= 0) {
				withoutId++;
				continue;
			}
			var title = GrantTitle.redact(raw(node, "title"));
			Integer callId = integer(node.path("convocatoria"), "id");
			grants.add(new Grant(id, callId, title.text(), title.redacted(), text(node, "expediente"),
					decimal(node, "importeSolicitado"), decimal(node, "importeConcedido"),
					decimal(node, "importeAnual"), integer(node, "numAnualidades"), date(node, "fechaSolicitud"),
					date(node, "fechaConcesion"), date(node, "fechaAcuerdo")));
		}
		if (withoutId > 0) {
			log.warn("spending: {} concesiones llegaron sin identificador y se descartaron", withoutId);
		}
		return grants;
	}

	/** Las convocatorias de una página. */
	public List<GrantCall> calls(String body) {
		var calls = new ArrayList<GrantCall>();
		for (JsonNode node : items(body)) {
			int id = node.path("id").asInt(0);
			if (id <= 0) {
				continue;
			}
			JsonNode line = node.path("lineaEstrategica").path("lineaAuxiliar");
			JsonNode scope = node.path("lineaAmbito");
			JsonNode area = scope.path("ambito");
			calls.add(new GrantCall(id, GrantTitle.redact(raw(node, "title")).text(), text(node, "ejercicioClave"),
					bool(node, "esPlurianual"), date(node, "fechaInicioVigencia"), date(node, "fechaFinVigencia"),
					date(node, "fechaInicioPresentacion"), date(node, "fechaFinPresentacion"),
					decimal(node, "presupuesto"), integer(node, "porcentajeAnticipado"),
					id(node.path("gestor")), text(node.path("gestor"), "title"),
					id(node.path("funciones")), text(node.path("funciones"), "title"),
					id(node.path("objetos")), text(node.path("objetos"), "title"),
					id(node.path("tipo")), text(node.path("tipo"), "title"),
					id(line), text(line, "title"), id(scope), text(scope, "title"), id(area), text(area, "title")));
		}
		return calls;
	}

	/**
	 * Los beneficiarios de una página del directorio, con <b>una</b> de las dos señales de persona física: la
	 * clasificación. La otra —el identificador enmascarado— llega de otro recurso, y la aplica la persistencia al
	 * guardar el enlace, para que <b>el orden de ingesta no cambie el resultado</b> (ADR-018 §4).
	 * <p>
	 * El {@code legalNif} no sale de aquí: el directorio no publica identificador fiscal.
	 */
	public List<GrantBeneficiary> beneficiaries(String body) {
		var beneficiaries = new ArrayList<GrantBeneficiary>();
		for (JsonNode node : items(body)) {
			String id = text(node, "id");
			if (id == null) {
				continue;
			}
			String classification = classification(text(node, "classification"));
			boolean naturalPerson = GrantIdentity.isNaturalPersonClass(classification);
			beneficiaries.add(new GrantBeneficiary(id, GrantIdentity.name(raw(node, "title"), naturalPerson), null,
					naturalPerson, classification));
		}
		return beneficiaries;
	}

	/**
	 * El enlace concesión → beneficiario que publica la v2, y de paso qué beneficiarios llevan el identificador
	 * enmascarado. <b>El NIF enmascarado no se guarda</b>: solo se usa para decidir si es una persona física.
	 */
	public GrantLinks links(String body) {
		var byGrant = new HashMap<Long, String>();
		var masked = new TreeSet<String>();
		var legalNif = new HashMap<String, String>();
		for (JsonNode node : items(body)) {
			long id = node.path("id").asLong(0);
			String beneficiary = text(node, "beneficiario");
			if (id <= 0 || beneficiary == null) {
				continue;
			}
			byGrant.put(id, beneficiary);
			String nif = text(node, "nifcif");
			if (GrantIdentity.isMaskedIdentifier(nif)) {
				masked.add(beneficiary);
			}
			else {
				String legal = GrantIdentity.legalNif(nif, false);
				if (legal != null) {
					legalNif.put(beneficiary, legal);
				}
			}
		}
		return new GrantLinks(byGrant, masked, legalNif);
	}

	/** Acepta las dos formas del envoltorio: {@code result} (v1) y {@code records} (v2). */
	private Iterable<JsonNode> items(String body) {
		JsonNode root = json.readTree(body);
		JsonNode items = root.path("result");
		if (!items.isArray()) {
			items = root.path("records");
		}
		if (!items.isArray()) {
			throw new IllegalArgumentException(
					"expected result[] or records[] of grants, got " + root.getNodeType());
		}
		return items;
	}

	/** La última parte de la URI de clasificación, que es el código que publica el origen. */
	static String classification(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.strip().replace("<", "").replace(">", "");
		int slash = trimmed.lastIndexOf('/');
		String tail = slash < 0 ? trimmed : trimmed.substring(slash + 1);
		return tail.isEmpty() ? null : tail;
	}

	private static String id(JsonNode node) {
		JsonNode value = node.path("id");
		if (value.isNumber()) {
			return String.valueOf(value.asLong());
		}
		return value.isString() && !value.stringValue().isBlank() ? value.stringValue().strip() : null;
	}

	private static LocalDate date(JsonNode node, String field) {
		String value = raw(node, field);
		if (value == null || value.length() < 10) {
			return null;
		}
		try {
			return LocalDate.parse(value.substring(0, 10));
		}
		catch (DateTimeParseException ex) {
			return null;
		}
	}

	private static BigDecimal decimal(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isNumber() ? value.decimalValue() : null;
	}

	private static Integer integer(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isNumber() ? value.asInt() : null;
	}

	private static Boolean bool(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isBoolean() ? value.asBoolean() : null;
	}

	private static String raw(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isString() ? value.stringValue() : null;
	}

	private static String text(JsonNode node, String field) {
		String value = raw(node, field);
		if (value == null) {
			JsonNode number = node.path(field);
			return number.isNumber() ? String.valueOf(number.asLong()) : null;
		}
		String trimmed = value.strip();
		return trimmed.isEmpty() ? null : trimmed;
	}

}
