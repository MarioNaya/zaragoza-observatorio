package es.zaragoza.observatory.citizen.infrastructure.zaragoza;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import es.zaragoza.observatory.citizen.domain.ServiceRequestDraft;
import es.zaragoza.observatory.citizen.domain.ServiceRequestStatus;
import es.zaragoza.observatory.geo.GeoPoint;
import es.zaragoza.observatory.shared.ZaragozaTime;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Traductor anti-corrupción del listado de quejas (SPEC.md §4.4). La respuesta es un array JSON en la raíz, sin
 * envoltorio ni {@code totalCount} (S0.3).
 * <p>
 * <b>El texto libre no se lee.</b> La ingesta no lo pide (ADR-012) y este traductor tampoco lo mira: si un día
 * el origen dejase de respetar {@code fl} y devolviese {@code title} o {@code description}, seguirían sin entrar
 * en el sistema. Es la segunda mitad de la garantía —la primera es no pedirlo— y está probada en
 * {@code ServiceRequestJsonTranslatorTest}.
 */
@Component
public class ServiceRequestJsonTranslator {

	private static final Logger log = LoggerFactory.getLogger(ServiceRequestJsonTranslator.class);

	/**
	 * Campos de texto escrito por el ciudadano. No se leen nunca; la constante existe para poder afirmarlo en un
	 * test y para que el nombre aparezca en el código junto a la razón (ADR-012, regla 22).
	 */
	public static final Set<String> CITIZEN_TEXT_FIELDS = Set.of("title", "description", "service_notice");

	private final JsonMapper json;

	public ServiceRequestJsonTranslator(JsonMapper json) {
		this.json = json;
	}

	public List<ServiceRequestDraft> translate(String body) {
		JsonNode root = json.readTree(body);
		if (!root.isArray()) {
			throw new IllegalArgumentException("expected a JSON array of service requests, got " + root.getNodeType());
		}
		List<ServiceRequestDraft> drafts = new ArrayList<>(root.size());
		for (JsonNode node : root) {
			ServiceRequestDraft draft = translate(node);
			if (draft != null) {
				drafts.add(draft);
			}
		}
		return drafts;
	}

	/** Un registro, o {@code null} si le falta lo imprescindible (id o fecha de alta): se descarta y se avisa. */
	private ServiceRequestDraft translate(JsonNode node) {
		long sourceId = node.path("service_request_id").asLong(0);
		if (sourceId <= 0) {
			log.warn("citizen: service request without service_request_id, skipped");
			return null;
		}
		var requestedAt = ZaragozaTime.parseInstant(text(node, "requested_datetime"));
		if (requestedAt == null) {
			log.warn("citizen: service request {} without a usable requested_datetime, skipped", sourceId);
			return null;
		}
		String serviceCode = text(node, "service_code");
		if (serviceCode == null) {
			// La taxonomía es un dato de la fuente, no una inferencia: sin código no se inventa uno.
			log.warn("citizen: service request {} without service_code, skipped", sourceId);
			return null;
		}
		return new ServiceRequestDraft(sourceId, ServiceRequestStatus.of(text(node, "status")), serviceCode,
				text(node, "service_name"), requestedAt, ZaragozaTime.parseInstant(text(node, "updated_datetime")),
				point(node.path("geometry"), sourceId), text(node, "district"));
	}

	/**
	 * Punto de la geometría GeoJSON con {@code srsname=wgs84}: {@code [lon, lat]} (S0.5). Solo se acepta
	 * {@code Point}; cualquier otra geometría se ignora en vez de reducirse a un centroide inventado.
	 */
	private GeoPoint point(JsonNode geometry, long sourceId) {
		if (geometry.isMissingNode() || geometry.isNull()) {
			return null;
		}
		String type = text(geometry, "type");
		JsonNode coordinates = geometry.path("coordinates");
		if (!"Point".equals(type) || !coordinates.isArray() || coordinates.size() < 2) {
			log.debug("citizen: service request {} has geometry of type {}, ignored", sourceId, type);
			return null;
		}
		try {
			return new GeoPoint(coordinates.get(0).asDouble(), coordinates.get(1).asDouble());
		}
		catch (IllegalArgumentException ex) {
			// GeoPoint valida el rango: un punto invertido o absurdo se descarta con aviso, no se resuelve
			// silenciosamente a «fuera del término».
			log.warn("citizen: service request {} has an out-of-range point, ignored: {}", sourceId, ex.getMessage());
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
