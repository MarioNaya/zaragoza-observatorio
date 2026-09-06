package es.zaragoza.observatory.spikes.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Persistencia de los spikes (ADR-002):
 * <ul>
 * <li>respuestas crudas en {@code src/test/resources/fixtures/zaragoza/<fuente>/} (futuros fixtures WireMock, SPEC §6);</li>
 * <li>métricas en {@code target/spikes/<spike>.md}, para pegarlas en el informe de {@code docs/spikes/}.</li>
 * </ul>
 * Las rutas son relativas al directorio del proyecto (surefire ejecuta con {@code basedir} como cwd).
 */
public final class SpikeFixtures {

	public static final Path FIXTURES_ROOT = Path.of("src", "test", "resources", "fixtures", "zaragoza");
	public static final Path METRICS_ROOT = Path.of("target", "spikes");

	private static final JsonMapper JSON = JsonMapper.shared();

	private SpikeFixtures() {
	}

	/** Marcador con el que se sustituye el texto libre redactado en los fixtures. */
	public static final String REDACTED = "[texto omitido en el fixture: puede contener datos personales]";

	public static Path save(String source, String name, String body) {
		try {
			Path dir = FIXTURES_ROOT.resolve(source);
			Files.createDirectories(dir);
			Path file = dir.resolve(name);
			Files.writeString(file, body, StandardCharsets.UTF_8);
			return file;
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Guarda una respuesta JSON sustituyendo por {@link #REDACTED} el valor de los campos indicados, a cualquier
	 * profundidad. Obligatorio para fuentes con texto escrito por ciudadanos (quejas y sugerencias): el ayuntamiento
	 * publica ese texto sin anonimizar y contiene nombres y DNI (comprobado el 2026-09-06). Un fixture con datos
	 * personales de terceros no entra en el repositorio (CLAUDE.md regla 22).
	 */
	public static Path saveRedacted(String source, String name, String body, Set<String> fields) {
		JsonNode tree = JSON.readTree(body);
		redact(tree, fields);
		return save(source, name, JSON.writeValueAsString(tree));
	}

	private static void redact(JsonNode node, Set<String> fields) {
		if (node instanceof ObjectNode object) {
			for (String field : List.copyOf(object.propertyNames())) {
				JsonNode value = object.get(field);
				if (fields.contains(field) && value.isString() && !value.asString().isBlank()) {
					object.put(field, REDACTED);
				}
				else {
					redact(value, fields);
				}
			}
		}
		else if (node.isArray()) {
			for (JsonNode child : node) {
				redact(child, fields);
			}
		}
	}

	/**
	 * Guarda las cabeceras de una respuesta con el mismo formato que los fixtures {@code .headers} grabados con
	 * {@code curl -D} (línea de estado y una cabecera por línea). Nunca escribe {@code Set-Cookie} (regla 22).
	 */
	public static Path saveHeaders(String source, String name, int status, org.springframework.http.HttpHeaders headers) {
		var out = new StringBuilder("HTTP/1.1 ").append(status).append('\n');
		headers.forEach((key, values) -> {
			if (!key.equalsIgnoreCase("Set-Cookie")) {
				for (String value : values) {
					out.append(key).append(": ").append(value).append('\n');
				}
			}
		});
		return save(source, name, out.toString());
	}

	/** Borra las métricas anteriores del spike y escribe la cabecera. Llamar una vez en {@code @BeforeAll}. */
	public static void startMetrics(String spikeId) {
		try {
			Files.createDirectories(METRICS_ROOT);
			Files.writeString(metricsFile(spikeId), "# Métricas " + spikeId + " — " + LocalDateTime.now() + "\n\n",
					StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static void metric(String spikeId, String line) {
		System.out.println("[" + spikeId + "] " + line);
		try {
			Files.createDirectories(METRICS_ROOT);
			Files.writeString(metricsFile(spikeId), line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
					StandardOpenOption.APPEND);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static void heading(String spikeId, String title) {
		metric(spikeId, "\n## " + title + "\n");
	}

	/** Tabla markdown a partir de filas ya ordenadas. */
	public static void table(String spikeId, List<String> header, List<List<String>> rows) {
		metric(spikeId, "| " + String.join(" | ", header) + " |");
		metric(spikeId, "|" + "---|".repeat(header.size()));
		for (List<String> row : rows) {
			metric(spikeId, "| " + String.join(" | ", row) + " |");
		}
	}

	/** Tabla de recuentos ordenada por valor descendente. */
	public static void counts(String spikeId, String keyName, Map<String, ? extends Number> counts) {
		List<List<String>> rows = counts.entrySet().stream()
				.sorted((a, b) -> Long.compare(b.getValue().longValue(), a.getValue().longValue()))
				.map(e -> List.of(e.getKey(), String.valueOf(e.getValue())))
				.toList();
		table(spikeId, List.of(keyName, "n"), rows);
	}

	private static Path metricsFile(String spikeId) {
		return METRICS_ROOT.resolve(spikeId + ".md");
	}

}
