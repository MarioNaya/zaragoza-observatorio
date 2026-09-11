package es.zaragoza.observatory.shared;

import java.util.Map;

/**
 * Lo que un módulo de dominio con eje territorial entrega para un cruce (ADR-019 §2 y §6): el recuento por junta
 * <b>y</b> la cobertura del conjunto con la que hay que leerlo.
 * <p>
 * Las dos cosas van juntas a propósito y no se pueden pedir por separado. El recuento por junta solo contiene lo
 * que se pudo situar —sin punto no hay junta, ADR-011 §2—, así que <b>la suma de las juntas no es
 * {@code total}</b>: la diferencia son los registros sin asignar, y una columna de 29 filas sin esa cifra al
 * lado parece el total y no lo es. La cobertura de la fuente va del 16 % al 45 % según el año en {@code citizen}
 * (S2.2) y es del 89,4 % en {@code urban} (S2.4), así que la diferencia no es un matiz.
 * <p>
 * Nada aquí está ajustado por cobertura, ni lo estará (ADR-015, ADR-019 §7).
 *
 * @param byDistrict recuento por junta resuelta; las juntas sin registros pueden faltar del mapa
 * @param total registros que pasan los filtros, tengan junta o no
 * @param withPoint los que traen punto, únicos que pueden tener junta
 * @param assigned los que además cayeron dentro de una junta (los de {@code OUTSIDE} tienen punto y no junta)
 */
public record TerritorialTally(Map<Integer, Long> byDistrict, long total, long withPoint, long assigned) {

	public TerritorialTally {
		byDistrict = byDistrict == null ? Map.of() : Map.copyOf(byDistrict);
		if (total < 0 || withPoint < 0 || assigned < 0) {
			throw new IllegalArgumentException("counts must not be negative");
		}
		if (withPoint > total || assigned > withPoint) {
			throw new IllegalArgumentException("assigned <= withPoint <= total (got " + assigned + ", " + withPoint
					+ ", " + total + ")");
		}
	}

	public static TerritorialTally empty() {
		return new TerritorialTally(Map.of(), 0, 0, 0);
	}

	/** Registros que no han podido situarse en ninguna junta: sin punto o fuera del término. */
	public long unassigned() {
		return total - assigned;
	}

	/** Proporción del conjunto que trae punto, entre 0 y 1; {@code null} si no hay registros que medir. */
	public Double pointCoverage() {
		return total == 0 ? null : (double) withPoint / total;
	}

	public long of(int districtId) {
		return byDistrict.getOrDefault(districtId, 0L);
	}

}
