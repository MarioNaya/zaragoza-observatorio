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

	private SpikeFixtures() {
	}

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
