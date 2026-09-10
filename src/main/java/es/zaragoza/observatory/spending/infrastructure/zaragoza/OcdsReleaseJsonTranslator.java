package es.zaragoza.observatory.spending.infrastructure.zaragoza;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import es.zaragoza.observatory.shared.ZaragozaTime;
import es.zaragoza.observatory.spending.domain.Award;
import es.zaragoza.observatory.spending.domain.Contract;
import es.zaragoza.observatory.spending.domain.Cpv;
import es.zaragoza.observatory.spending.domain.Money;
import es.zaragoza.observatory.spending.domain.PartyIdentity;
import es.zaragoza.observatory.spending.domain.ReleaseContent;
import es.zaragoza.observatory.spending.domain.Tender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Traductor anti-corrupción del release package de un proceso (SPEC.md §4.4, S3.1 §4).
 * <p>
 * Tres cosas que este traductor hace y conviene no deshacer:
 * <ul>
 * <li><b>Un 200 no garantiza release</b>: 16 packages responden 200 con {@code releases} vacío. Devuelve
 * {@code null} y el adaptador lo traduce a {@code EMPTY}, que no es lo mismo que un 404.</li>
 * <li><b>Un package trae como mucho un release</b> —5.606 traen uno y ninguno trae más—, así que se lee el
 * primero y se avisa si algún día llegan varios, en vez de implementar compiled releases que no hacen falta.</li>
 * <li><b>El identificador de las partes no se copia</b>: lleva el NIF dentro y se descompone con
 * {@link PartyIdentity} (ADR-017 §2). Es la mitad de la garantía que vive en el traductor; la otra la impone la
 * base de datos.</li>
 * </ul>
 * El texto libre sí se lee y sí se guarda (ADR-017 §3): es lo que dice qué se contrató, y el barrido completo de
 * los 5.622 documentos no encontró un solo DNI ni NIE con letra de control válida.
 */
@Component
public class OcdsReleaseJsonTranslator {

	private static final Logger log = LoggerFactory.getLogger(OcdsReleaseJsonTranslator.class);

	private static final String CPV_SCHEME = "CPV";

	private final JsonMapper json;

	public OcdsReleaseJsonTranslator(JsonMapper json) {
		this.json = json;
	}

	/** El contenido del package, o {@code null} si no trae ningún release. */
	public ReleaseContent translate(String ocid, String body) {
		JsonNode root = json.readTree(body);
		if (!root.isObject()) {
			throw new IllegalArgumentException("expected an OCDS release package object, got " + root.getNodeType());
		}
		JsonNode releases = root.path("releases");
		if (!releases.isArray() || releases.isEmpty()) {
			return null;
		}
		if (releases.size() > 1) {
			// No se ha observado ninguno en los 5.622 documentos. Si aparece, hay que decidirlo con datos, no
			// quedarse con el primero en silencio.
			log.warn("spending: el package de {} trae {} releases; se lee el primero", ocid, releases.size());
		}
		JsonNode release = releases.get(0);
		JsonNode tender = release.path("tender");
		var cpvs = new LinkedHashMap<String, Cpv>();
		List<Award> awards = awards(release.path("awards"), ocid, cpvs);
		collectCpvs(tender.path("items"), cpvs);
		return new ReleaseContent(ZaragozaTime.parseInstant(text(root, "publishedDate")), text(release, "id"),
				tags(release.path("tag")), text(release, "initiationType"), tender(tender),
				procuringEntityName(release, tender), text(tender.path("procuringEntity").path("identifier"), "id"),
				awards, contracts(release.path("contracts"), ocid, cpvs), List.copyOf(cpvs.values()));
	}

	private Tender tender(JsonNode tender) {
		if (tender.isMissingNode() || tender.isNull()) {
			return Tender.NONE;
		}
		return new Tender(text(tender, "title"), text(tender, "description"), text(tender, "status"),
				text(tender, "procurementMethod"), text(tender, "mainProcurementCategory"),
				text(tender, "awardCriteria"), integer(tender, "numberOfTenderers"), money(tender.path("value")),
				money(tender.path("minValue")));
	}

	/**
	 * El órgano de contratación. Se prefiere {@code tender.procuringEntity.name} y, si falta, la parte con rol
	 * {@code procuringEntity}, que aparece en los 5.606 documentos.
	 */
	private String procuringEntityName(JsonNode release, JsonNode tender) {
		String name = text(tender.path("procuringEntity"), "name");
		if (name != null) {
			return name;
		}
		for (JsonNode party : release.path("parties")) {
			for (JsonNode role : party.path("roles")) {
				if ("procuringEntity".equals(role.asString())) {
					return text(party, "name");
				}
			}
		}
		return null;
	}

	private List<Award> awards(JsonNode array, String ocid, Map<String, Cpv> cpvs) {
		if (!array.isArray()) {
			return List.of();
		}
		List<Award> awards = new ArrayList<>(array.size());
		for (JsonNode node : array) {
			String awardId = text(node, "id");
			if (awardId == null) {
				log.warn("spending: {} trae una adjudicación sin id, descartada", ocid);
				continue;
			}
			collectCpvs(node.path("items"), cpvs);
			awards.add(new Award(awardId, text(node, "title"), text(node, "description"), text(node, "status"),
					ZaragozaTime.parseInstant(text(node, "date")), money(node.path("value")),
					parties(node.path("suppliers"))));
		}
		return awards;
	}

	/**
	 * Las adjudicatarias, ya despojadas del identificador del origen. Aquí es donde el NIF deja de estar
	 * incrustado y pasa a ser un campo con dueño conocido, o desaparece si es de persona física (ADR-017 §2).
	 */
	private List<PartyIdentity> parties(JsonNode array) {
		if (!array.isArray()) {
			return List.of();
		}
		List<PartyIdentity> parties = new ArrayList<>(array.size());
		for (JsonNode node : array) {
			parties.add(PartyIdentity.from(text(node, "id"), text(node, "name")));
		}
		return parties;
	}

	private List<Contract> contracts(JsonNode array, String ocid, Map<String, Cpv> cpvs) {
		if (!array.isArray()) {
			return List.of();
		}
		List<Contract> contracts = new ArrayList<>(array.size());
		for (JsonNode node : array) {
			String contractId = text(node, "id");
			if (contractId == null) {
				log.warn("spending: {} trae un contrato sin id, descartado", ocid);
				continue;
			}
			collectCpvs(node.path("items"), cpvs);
			JsonNode period = node.path("period");
			// La cáscara vacía llega aquí con todo a null menos el id, y así se guarda: es un hecho de la fuente
			// (1.560 de 4.970), no un error de carga.
			contracts.add(new Contract(contractId, text(node, "awardID"), text(node, "title"),
					text(node, "description"), text(node, "status"),
					ZaragozaTime.parseInstant(text(node, "dateSigned")), money(node.path("value")),
					ZaragozaTime.parseInstant(text(period, "startDate")),
					ZaragozaTime.parseInstant(text(period, "endDate"))));
		}
		return contracts;
	}

	/**
	 * Recoge los CPV de una lista de artículos. Se queda con la clasificación principal y las adicionales, y un
	 * código que aparezca en las dos se guarda como principal.
	 */
	private void collectCpvs(JsonNode items, Map<String, Cpv> cpvs) {
		if (!items.isArray()) {
			return;
		}
		for (JsonNode item : items) {
			addCpv(item.path("classification"), true, cpvs);
			for (JsonNode additional : item.path("additionalClassifications")) {
				addCpv(additional, false, cpvs);
			}
		}
	}

	private void addCpv(JsonNode classification, boolean main, Map<String, Cpv> cpvs) {
		if (!classification.isObject() || !CPV_SCHEME.equalsIgnoreCase(text(classification, "scheme"))) {
			return;
		}
		String code = text(classification, "id");
		if (code == null) {
			return;
		}
		Cpv existing = cpvs.get(code);
		if (existing == null || (main && !existing.main())) {
			cpvs.put(code, new Cpv(code, text(classification, "description"), main || (existing != null && existing.main())));
		}
	}

	/** {@code releases[].tag} es un array; en los 5.606 documentos trae un solo valor. Se une por comas. */
	private static String tags(JsonNode tag) {
		if (tag.isString()) {
			return tag.stringValue().strip();
		}
		if (!tag.isArray() || tag.isEmpty()) {
			return null;
		}
		var joined = new StringBuilder();
		for (JsonNode node : tag) {
			String value = node.asString();
			if (value == null || value.isBlank()) {
				continue;
			}
			if (!joined.isEmpty()) {
				joined.append(',');
			}
			joined.append(value.strip());
		}
		return joined.isEmpty() ? null : joined.toString();
	}

	/** Un importe con su moneda; {@code null} si el documento no lo trae, nunca un cero inventado. */
	private static Money money(JsonNode value) {
		if (!value.isObject()) {
			return null;
		}
		BigDecimal amount = decimal(value.path("amount"));
		return Money.of(amount, text(value, "currency"));
	}

	private static BigDecimal decimal(JsonNode node) {
		if (node.isMissingNode() || node.isNull()) {
			return null;
		}
		try {
			return new BigDecimal(node.asString().strip());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static Integer integer(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isNumber() ? value.asInt() : null;
	}

	/**
	 * El texto de un campo. Los códigos CPV llegan como número ({@code "id":45232150}) y como cadena según el
	 * documento, así que se normalizan a texto.
	 */
	private static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		String raw = value.isString() ? value.stringValue() : value.asString();
		return raw == null || raw.isBlank() ? null : raw.strip();
	}

}
