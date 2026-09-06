package es.zaragoza.observatory.shared;

/**
 * Referencia a un dataset municipal: familia de fuente más identificador dentro de esa fuente (SPEC.md §4.3).
 * <p>
 * {@code source} toma uno de los valores de {@link Sources}; {@code id} es el identificador que usa la fuente
 * (para el catálogo de datasets, el {@code id} numérico de la ficha; para un endpoint de sede, la ruta del
 * servicio sin extensión, p. ej. {@code distrito}; para OCDS, {@code contracting-process}).
 * <p>
 * Cada registro almacenado por cualquier módulo conserva su {@code DatasetRef} y la fecha de ingesta
 * (trazabilidad, SPEC.md §1.1).
 */
public record DatasetRef(String source, String id) {

	public DatasetRef {
		if (source == null || source.isBlank()) {
			throw new IllegalArgumentException("source must not be blank");
		}
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("id must not be blank");
		}
		if (source.contains(":")) {
			throw new IllegalArgumentException("source must not contain ':' (reserved as key separator)");
		}
	}

	public static DatasetRef of(String source, String id) {
		return new DatasetRef(source, id);
	}

	/** Clave estable {@code source:id}, apta para columnas de persistencia y claves de registros. */
	public String key() {
		return source + ":" + id;
	}

	/** Inversa de {@link #key()}. */
	public static DatasetRef fromKey(String key) {
		int separator = key == null ? -1 : key.indexOf(':');
		if (separator <= 0) {
			throw new IllegalArgumentException("key must have the form source:id, got " + key);
		}
		return new DatasetRef(key.substring(0, separator), key.substring(separator + 1));
	}

	@Override
	public String toString() {
		return key();
	}

}
