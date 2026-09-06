package es.zaragoza.observatory.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;

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

}
