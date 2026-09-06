package es.zaragoza.observatory.ingestion.domain;

import java.util.Objects;

/**
 * Fallo al consultar la fuente, clasificado según S0.5 («Errores» y recomendación 5) para decidir si se reintenta.
 */
public class SourceAccessException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public enum Kind {
		/** 5xx: la fuente falla; reintentable. */
		SERVER_ERROR(true),
		/** Timeout de conexión o lectura; reintentable. */
		TIMEOUT(true),
		/** Error de E/S (conexión rechazada, reset…); reintentable. */
		IO(true),
		/** Respuesta 2xx/3xx que no es el JSON esperado (HTML de WebLogic, redirección…); reintentable (S0.5). */
		MALFORMED(true),
		/** 404 JSON «Registro no encontrado»: ausencia definitiva del recurso; no reintentable. */
		NOT_FOUND(false),
		/** Otro 4xx (parámetro inválido, FIQL sobre campo no permitido…); no reintentable. */
		CLIENT_ERROR(false),
		/** El circuit breaker de la fuente está abierto; no reintentable en esta ejecución. */
		CIRCUIT_OPEN(false);

		private final boolean retryable;

		Kind(boolean retryable) {
			this.retryable = retryable;
		}

		public boolean retryable() {
			return retryable;
		}
	}

	private final Kind kind;
	private final Integer status;

	public SourceAccessException(Kind kind, Integer status, String message, Throwable cause) {
		super(message, cause);
		this.kind = Objects.requireNonNull(kind);
		this.status = status;
	}

	public SourceAccessException(Kind kind, Integer status, String message) {
		this(kind, status, message, null);
	}

	public Kind kind() {
		return kind;
	}

	/** Código HTTP si lo hubo, o {@code null}. */
	public Integer status() {
		return status;
	}

	public boolean retryable() {
		return kind.retryable;
	}

}
