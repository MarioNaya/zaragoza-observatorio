package es.zaragoza.observatory.geo.infrastructure.zaragoza;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import es.zaragoza.observatory.geo.domain.DistrictProfile;
import es.zaragoza.observatory.geo.domain.DistrictProfileReader;
import es.zaragoza.observatory.geo.domain.PopulationRecord;
import es.zaragoza.observatory.geo.infrastructure.GeoProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lee {@code sede/servicio/distrito/{id}.json?fl=id,title,indicadores} con el cliente HTTP común de la
 * aplicación (regla 23), con una pausa entre peticiones por cortesía (S0.5). Sin reintentos ni circuit breaker:
 * lo que falle hoy se reintenta en la siguiente ingesta, como en el muestreo observado (ADR-005).
 * <p>
 * De {@code indicadores} solo se copian los recuentos y la superficie. Los índices que la fuente ya trae
 * calculados (envejecimiento, dependencia, feminidad, natalidad…) no se guardan: son lecturas, y las lecturas
 * las hace quien consulta (regla 6).
 * <p>
 * {@code iddatosab} se comprueba contra el id pedido —S2.1 verificó que coinciden en las 29 juntas—: si dejaran
 * de coincidir, la junta se descarta con un aviso en vez de mezclar dos numeraciones.
 */
public class DistrictProfileHttpReader implements DistrictProfileReader {

	private static final Logger log = LoggerFactory.getLogger(DistrictProfileHttpReader.class);
	private static final String FIELDS = "id,title,indicadores";

	private final RestClient rest;
	private final JsonMapper json;
	private final GeoProperties properties;
	private final Clock clock;
	private final Duration requestDelay;
	private long lastRequestNanos;
	private boolean requested;

	public DistrictProfileHttpReader(RestClient rest, JsonMapper json, GeoProperties properties, Clock clock) {
		this.rest = Objects.requireNonNull(rest);
		this.json = Objects.requireNonNull(json);
		this.properties = Objects.requireNonNull(properties);
		this.clock = Objects.requireNonNull(clock);
		this.requestDelay = properties.profileRequestDelay();
	}

	@Override
	public Optional<DistrictProfile> read(int districtId) {
		URI uri = URI.create(properties.districtDetailUrl(districtId) + "?fl=" + FIELDS);
		String body;
		try {
			pause();
			body = rest.get().uri(uri).retrieve().body(String.class);
		}
		catch (RuntimeException e) {
			log.warn("district {} detail failed ({}): {}", districtId, uri, e.toString());
			return Optional.empty();
		}
		if (body == null || body.isBlank()) {
			log.warn("district {} detail returned an empty body ({})", districtId, uri);
			return Optional.empty();
		}
		try {
			return Optional.of(parse(districtId, body, clock.instant()));
		}
		catch (RuntimeException e) {
			log.warn("district {} detail could not be read ({}): {}", districtId, uri, e.toString());
			return Optional.empty();
		}
	}

	DistrictProfile parse(int districtId, String body, Instant ingestedAt) {
		JsonNode indicators = json.readTree(body).path("indicadores");
		if (!indicators.isArray() || indicators.isEmpty()) {
			return new DistrictProfile(districtId, null, List.of());
		}
		var padronIds = new TreeSet<Integer>();
		List<PopulationRecord> records = new ArrayList<>(indicators.size());
		for (JsonNode year : indicators) {
			int declaredId = integer(year, "iddatosab") == null ? districtId : integer(year, "iddatosab");
			if (declaredId != districtId) {
				throw new IllegalStateException(
						"district " + districtId + " reports iddatosab=" + declaredId + ": numbering changed in origin");
			}
			Integer padronId = integer(year, "idpadron");
			if (padronId != null) {
				padronIds.add(padronId);
			}
			Integer anyo = integer(year, "anyo");
			if (anyo == null) {
				continue;
			}
			records.add(new PopulationRecord(districtId, anyo, integer(year, "totpob"), integer(year, "esp"),
					integer(year, "ext"), integer(year, "menor16"), integer(year, "menor18"), integer(year, "nhogar"),
					decimal(year, "km2"), ingestedAt));
		}
		if (padronIds.size() > 1) {
			throw new IllegalStateException(
					"district " + districtId + " reports several idpadron values across years: " + padronIds);
		}
		records.sort(Comparator.comparingInt(PopulationRecord::year).reversed());
		return new DistrictProfile(districtId, padronIds.isEmpty() ? null : padronIds.first(), records);
	}

	/** Los indicadores llegan con los números unas veces como número y otras como cadena. */
	private static Integer integer(JsonNode node, String field) {
		Double value = decimal(node, field);
		return value == null ? null : (int) Math.round(value);
	}

	private static Double decimal(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		if (value.isNumber()) {
			return value.asDouble();
		}
		String text = value.isString() ? value.stringValue().strip() : value.toString().strip();
		if (text.isEmpty()) {
			return null;
		}
		try {
			return Double.valueOf(text);
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

	private void pause() {
		if (requested && !requestDelay.isZero()) {
			long remaining = requestDelay.toNanos() - (System.nanoTime() - lastRequestNanos);
			if (remaining > 0) {
				try {
					Thread.sleep(Duration.ofNanos(remaining));
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}
		requested = true;
		lastRequestNanos = System.nanoTime();
	}

}
