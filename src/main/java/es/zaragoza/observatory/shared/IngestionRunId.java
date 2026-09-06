package es.zaragoza.observatory.shared;

import java.util.Objects;
import java.util.UUID;

/** Identificador de una ejecución de ingesta (SPEC.md §4.5). Lo emite {@code ingestion} y lo referencian los eventos. */
public record IngestionRunId(UUID value) {

	public IngestionRunId {
		Objects.requireNonNull(value, "value must not be null");
	}

	public static IngestionRunId newId() {
		return new IngestionRunId(UUID.randomUUID());
	}

	public static IngestionRunId of(String value) {
		return new IngestionRunId(UUID.fromString(value));
	}

	@Override
	public String toString() {
		return value.toString();
	}

}
