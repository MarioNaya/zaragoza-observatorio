package es.zaragoza.observatory.catalog.infrastructure.web;

import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import es.zaragoza.observatory.catalog.domain.DatasetReadModel.DatasetSort.Direction;
import es.zaragoza.observatory.catalog.domain.DatasetReadModel.PageRequest;

/** Parámetro {@code sort=campo[,asc|desc]} con lista blanca por endpoint (SPEC.md §4.7, regla 8) y paginación. */
final class Sorting {

	private Sorting() {
	}

	record Parsed<F>(String name, F field, Direction direction) {

		String describe() {
			return name + "," + direction.name().toLowerCase(Locale.ROOT);
		}
	}

	static <F> Parsed<F> parse(String sort, Map<String, F> fields) {
		String[] parts = sort.split(",", -1);
		String name = parts[0].strip();
		F field = fields.get(name);
		if (field == null || parts.length > 2) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sort no admitido: '" + sort + "'. Campos: "
					+ String.join(", ", fields.keySet().stream().sorted().toList()) + "; dirección: asc|desc");
		}
		Direction direction = Direction.ASC;
		if (parts.length == 2) {
			direction = switch (parts[1].strip().toLowerCase(Locale.ROOT)) {
				case "asc" -> Direction.ASC;
				case "desc" -> Direction.DESC;
				default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
						"dirección de sort no admitida: '" + parts[1] + "' (asc|desc)");
			};
		}
		return new Parsed<>(name, field, direction);
	}

	static PageRequest pageRequest(int page, int size) {
		try {
			return new PageRequest(page, size);
		}
		catch (IllegalArgumentException ex) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
		}
	}

}
