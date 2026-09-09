package es.zaragoza.observatory.urban.infrastructure.zaragoza;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import es.zaragoza.observatory.geo.GeoPoint;
import es.zaragoza.observatory.shared.ZaragozaTime;
import es.zaragoza.observatory.urban.domain.IaeActivity;
import es.zaragoza.observatory.urban.domain.Licence;
import es.zaragoza.observatory.urban.domain.LicensedPremisesDraft;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Traductor anti-corrupción del listado de locales con licencia (SPEC.md §4.4). La respuesta es el envoltorio
 * {@code {"totalCount":…,"result":[…]}} de la sede (S2.4 §1).
 * <p>
 * <b>El texto libre no se lee.</b> Al contrario que en {@code citizen}, aquí la ingesta no puede evitar
 * <i>descargarlo</i> —{@code fl} vacía los objetos anidados y {@code removeproperties} no hace nada (S2.4
 * §2)—, así que esta clase es la garantía entera: los cuatro campos de {@link #FREE_TEXT_FIELDS} no se miran, y
 * no existe columna donde guardarlos (ADR-016 §3). Está probado en
 * {@code LicensedPremisesJsonTranslatorTest}.
 */
@Component
public class LicensedPremisesJsonTranslator {

	private static final Logger log = LoggerFactory.getLogger(LicensedPremisesJsonTranslator.class);

	/**
	 * Campos de texto libre del origen. No se leen nunca. La constante existe para poder afirmarlo en un test y
	 * para que el nombre aparezca en el código junto a la razón (ADR-016 §3, regla 22):
	 * <ul>
	 * <li>{@code comments} (del local y de cada licencia): 15 DNI con letra de control válida;</li>
	 * <li>{@code actividad}: 2 DNI, y el epígrafe IAE dice lo mismo codificado;</li>
	 * <li>{@code emplazamiento}: la dirección, sin uso desde ADR-011 §2.</li>
	 * </ul>
	 */
	public static final Set<String> FREE_TEXT_FIELDS = Set.of("comments", "actividad", "emplazamiento");

	private final JsonMapper json;

	public LicensedPremisesJsonTranslator(JsonMapper json) {
		this.json = json;
	}

	public List<LicensedPremisesDraft> translate(String body) {
		JsonNode root = json.readTree(body);
		JsonNode result = root.path("result");
		if (!result.isArray()) {
			// Un listado sin `result` no es un listado vacío: es una respuesta que no se entiende, y tragársela
			// haría que una ingesta rota pareciera correcta (la misma salvaguarda que ADR-013 §2 en el catálogo).
			throw new IllegalArgumentException(
					"expected a sede envelope with a result array, got " + root.getNodeType());
		}
		List<LicensedPremisesDraft> drafts = new ArrayList<>(result.size());
		for (JsonNode node : result) {
			LicensedPremisesDraft draft = translate(node);
			if (draft != null) {
				drafts.add(draft);
			}
		}
		return drafts;
	}

	/** Un local, o {@code null} si le falta lo imprescindible (id o fecha de alta): se descarta y se avisa. */
	private LicensedPremisesDraft translate(JsonNode node) {
		JsonNode id = node.path("id");
		if (!id.isNumber()) {
			log.warn("urban: premises without a numeric id, skipped");
			return null;
		}
		int sourceId = id.asInt();
		Instant createdAt = ZaragozaTime.parseInstant(text(node, "creationDate"));
		if (createdAt == null) {
			log.warn("urban: premises {} without a usable creationDate, skipped", sourceId);
			return null;
		}
		return new LicensedPremisesDraft(sourceId, activity(node), node.path("estado").asInt(-1),
				text(node, "codPortal"), text(node, "codVia"), text(node, "zonaSaturada"), createdAt,
				ZaragozaTime.parseInstant(text(node, "lastUpdated")),
				ZaragozaTime.parseInstant(text(node, "fechaBaja")), point(node.path("geometry"), sourceId),
				licences(node, sourceId));
	}

	/**
	 * El epígrafe IAE, que es la actividad del producto. Se lee del objeto {@code iae} y, si falta, del
	 * {@code idIAE}/{@code idAgrupacion} planos que el origen repite fuera.
	 */
	private IaeActivity activity(JsonNode node) {
		JsonNode iae = node.path("iae");
		String code = text(iae, "identifier");
		if (code == null) {
			code = text(node, "idIAE");
		}
		Integer group = integer(iae.path("id"), "agrupacion");
		if (group == null) {
			group = parseInteger(text(node, "idAgrupacion"));
		}
		return new IaeActivity(code, text(iae, "title"), integer(iae.path("id"), "seccion"), group);
	}

	/** Las licencias del local, ordenadas por año y expediente para que el orden no dependa del origen. */
	private List<Licence> licences(JsonNode node, int sourceId) {
		JsonNode array = node.path("licencias");
		if (!array.isArray()) {
			return List.of();
		}
		List<Licence> licences = new ArrayList<>(array.size());
		for (JsonNode item : array) {
			JsonNode key = item.path("id");
			if (!key.path("anyo").isNumber() || !key.path("expediente").isNumber()) {
				// Sin (año, expediente) no hay identidad dentro del local (S2.4 §7): no se inventa una.
				log.warn("urban: premises {} has a licence without anyo/expediente, skipped", sourceId);
				continue;
			}
			licences.add(new Licence(key.path("anyo").asInt(), key.path("expediente").asLong(),
					integer(item, "orden"), item.path("tipo").path("id").asInt(-1), text(item.path("tipo"), "title"),
					date(text(item, "resolucion")), integer(item, "idResolucion"),
					ZaragozaTime.parseInstant(text(item, "creationDate")),
					ZaragozaTime.parseInstant(text(item, "lastUpdated")),
					ZaragozaTime.parseInstant(text(item, "fechaBaja"))));
		}
		licences.sort((a, b) -> a.year() != b.year() ? Integer.compare(a.year(), b.year())
				: Long.compare(a.fileNumber(), b.fileNumber()));
		return licences;
	}

	/**
	 * Punto de la geometría GeoJSON con {@code srsname=wgs84}: {@code [lon, lat]} (S0.5). Solo se acepta
	 * {@code Point}; cualquier otra geometría se ignora en vez de reducirse a un centroide inventado.
	 */
	private GeoPoint point(JsonNode geometry, int sourceId) {
		if (geometry.isMissingNode() || geometry.isNull()) {
			return null;
		}
		JsonNode coordinates = geometry.path("coordinates");
		if (!"Point".equals(text(geometry, "type")) || !coordinates.isArray() || coordinates.size() < 2) {
			log.debug("urban: premises {} has geometry of type {}, ignored", sourceId, text(geometry, "type"));
			return null;
		}
		try {
			return new GeoPoint(coordinates.get(0).asDouble(), coordinates.get(1).asDouble());
		}
		catch (IllegalArgumentException ex) {
			log.warn("urban: premises {} has an out-of-range point, ignored: {}", sourceId, ex.getMessage());
			return null;
		}
	}

	/** La resolución llega siempre a las 00:00:00: es una fecha, y como tal se guarda (S2.4 §7). */
	private static LocalDate date(String value) {
		Instant instant = ZaragozaTime.parseInstant(value);
		return instant == null ? null : instant.atZone(ZaragozaTime.ZONE).toLocalDate();
	}

	private static Integer integer(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isNumber() ? value.asInt() : null;
	}

	private static Integer parseInteger(String value) {
		try {
			return value == null ? null : Integer.valueOf(value.strip());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		String raw = value.isString() ? value.stringValue() : value.asString();
		return raw == null || raw.isBlank() ? null : raw.strip();
	}

}
