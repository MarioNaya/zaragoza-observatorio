package es.zaragoza.observatory.geo.domain;

import java.util.List;

/**
 * Resultado de resolver un punto contra las juntas (ADR-011). Tres resultados posibles y ninguno silencioso:
 * <ul>
 * <li>{@code RESOLVED}: el punto cae en una sola junta;</li>
 * <li>{@code AMBIGUOUS}: cae en varias. Los 29 polígonos <b>no son una partición</b> del término (se solapan en
 * el entorno de Juslibol, S2.1): se elige la de menor id por orden determinista y se conservan las candidatas,
 * porque una junta elegida sin decir que había dudas es una conclusión inventada (regla 6);</li>
 * <li>{@code OUTSIDE}: no cae en ninguna. Es un hecho publicable, no un error.</li>
 * </ul>
 * El cuarto caso —un registro <b>sin punto</b>— no llega hasta aquí: lo cuenta como «sin asignar» quien ingiere
 * la fuente. La API municipal no ofrece alternativa: no geocodifica de forma fiable (S2.1).
 *
 * @param status qué ha pasado
 * @param districtId junta asignada, o {@code null} si {@code OUTSIDE}
 * @param candidates todas las juntas que contienen el punto, en orden de id (vacía si {@code OUTSIDE})
 */
public record DistrictLocation(Status status, Integer districtId, List<Integer> candidates) {

	public enum Status {
		RESOLVED, AMBIGUOUS, OUTSIDE
	}

	public DistrictLocation {
		candidates = candidates == null ? List.of() : List.copyOf(candidates);
		if (status == null) {
			throw new IllegalArgumentException("status must not be null");
		}
		if ((status == Status.OUTSIDE) != (districtId == null)) {
			throw new IllegalArgumentException("districtId must be present unless the point is OUTSIDE");
		}
	}

	public static DistrictLocation outside() {
		return new DistrictLocation(Status.OUTSIDE, null, List.of());
	}

	/** Construye el resultado a partir de las juntas que contienen el punto, ya ordenadas por id. */
	public static DistrictLocation of(List<Integer> containing) {
		if (containing == null || containing.isEmpty()) {
			return outside();
		}
		Status status = containing.size() == 1 ? Status.RESOLVED : Status.AMBIGUOUS;
		return new DistrictLocation(status, containing.get(0), containing);
	}

	public boolean isResolved() {
		return status != Status.OUTSIDE;
	}

	public boolean isAmbiguous() {
		return status == Status.AMBIGUOUS;
	}

}
