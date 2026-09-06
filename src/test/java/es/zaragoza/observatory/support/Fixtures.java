package es.zaragoza.observatory.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;

/**
 * Acceso a las respuestas reales grabadas por los spikes en {@code src/test/resources/fixtures/zaragoza/}
 * (ADR-002, ADR-004). Se refrescan reejecutando el spike correspondiente, nunca a mano.
 */
public final class Fixtures {

	private static final String ROOT = "fixtures/zaragoza/";

	private Fixtures() {
	}

	public static byte[] bytes(String relativePath) {
		try {
			return new ClassPathResource(ROOT + relativePath).getContentAsByteArray();
		}
		catch (IOException ex) {
			throw new UncheckedIOException("fixture not found: " + relativePath, ex);
		}
	}

	public static String text(String relativePath) {
		return new String(bytes(relativePath), StandardCharsets.UTF_8);
	}

	/**
	 * Cabeceras de un fixture {@code .headers} (línea de estado y una cabecera por línea, como los graba
	 * {@code curl -D} o {@code SpikeFixtures.saveHeaders}).
	 */
	public static HttpHeaders headers(String relativePath) {
		HttpHeaders headers = new HttpHeaders();
		for (String line : text(relativePath).split("\\r?\\n")) {
			if (line.startsWith("HTTP/") || line.isBlank()) {
				continue;
			}
			int colon = line.indexOf(':');
			if (colon > 0) {
				headers.add(line.substring(0, colon).strip(), line.substring(colon + 1).strip());
			}
		}
		return headers;
	}

}
