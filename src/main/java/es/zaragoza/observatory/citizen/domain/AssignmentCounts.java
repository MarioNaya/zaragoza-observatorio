package es.zaragoza.observatory.citizen.domain;

import java.util.Map;

import es.zaragoza.observatory.geo.Assignment;

/**
 * Cuántos registros hay en cada estado de asignación territorial. Acompaña obligatoriamente a toda agregación
 * territorial (regla 7, ADR-011 §7): un mapa que no diga que la mitad de las quejas no tienen punto es una
 * mentira por omisión.
 *
 * @param byAssignment recuento por estado, con todos los estados presentes aunque valgan 0
 * @param declaredAgrees registros donde la junta declarada coincide con la resuelta
 * @param declaredDisagrees registros donde discrepan
 * @param declaredOnly registros sin junta resuelta pero con junta declarada que casa (no se usan para asignar)
 * @param declaredUnmatched registros con nombre declarado que no casa con ninguna junta oficial
 */
public record AssignmentCounts(Map<Assignment, Long> byAssignment, long declaredAgrees, long declaredDisagrees,
		long declaredOnly, long declaredUnmatched) {

	public AssignmentCounts {
		byAssignment = byAssignment == null ? Map.of() : Map.copyOf(byAssignment);
	}

	public long total() {
		return byAssignment.values().stream().mapToLong(Long::longValue).sum();
	}

	/** Registros sin junta asignada: sin punto o fuera del término. */
	public long unassigned() {
		return byAssignment.getOrDefault(Assignment.NO_POINT, 0L)
				+ byAssignment.getOrDefault(Assignment.OUTSIDE, 0L);
	}

}
