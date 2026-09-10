package es.zaragoza.observatory.spending.domain;

/**
 * Ejes de agregación admitidos (SPEC.md §4.7, ADR-017 §7). Como en {@code urban}, están separados por
 * <b>unidad</b>, porque un proceso y una adjudicación no son lo mismo y sumarlos daría una cifra sin
 * significado: 3.410 procesos tienen adjudicación y 195 tienen más de una.
 * <p>
 * Ningún eje es territorial, y no por olvido: esta fuente no publica localización (ADR-003 §1).
 */
public enum AggregationAxis {

	/** Procesos por año de publicación. Es la serie temporal de la fuente. */
	YEAR(Unit.PROCESSES),
	/** Procesos por {@code releases[].tag}: award · contract · tender · tenderCancellation. */
	TAG(Unit.PROCESSES),
	/** Procesos por {@code tender.status}, sin traducir. */
	TENDER_STATUS(Unit.PROCESSES),
	/** Procesos por procedimiento: open · limited · direct · selective, y 53 sin valor. */
	PROCUREMENT_METHOD(Unit.PROCESSES),
	/** Procesos por categoría: services · goods · works. */
	CATEGORY(Unit.PROCESSES),
	/**
	 * Procesos por etapa. El grupo sin etapa <b>sale como tal</b>, con su clave nula: son 1.560 procesos
	 * completos cuyo contrato no dice cuándo se firmó, y esconderlos borraría el hallazgo (ADR-017 §6).
	 */
	STAGE(Unit.PROCESSES),
	/** Procesos por órgano de contratación, con el nombre que publica el origen. */
	PROCURING_ENTITY(Unit.PROCESSES),
	/**
	 * Procesos por situación del detalle. Es el eje que enseña el 29,7 % sin release, y por eso existe: sin él,
	 * el hueco solo se vería restando.
	 */
	RELEASE_STATUS(Unit.PROCESSES),
	/**
	 * Procesos por código CPV. <b>Un proceso con dos CPV cuenta en los dos</b>, así que la suma de los grupos no
	 * es el total; la respuesta lo dice en vez de dejar que se note. Solo el 42,7 % de los procesos trae CPV.
	 */
	CPV(Unit.PROCESSES),
	/**
	 * Adjudicaciones por adjudicataria, agrupadas por NIF de persona jurídica. Las de persona física no tienen
	 * clave con la que agrupar —de ellas no se guarda identidad, ADR-017 §2— y salen en un grupo propio.
	 * <p>
	 * Una adjudicación con varias adjudicatarias <b>cuenta entera en cada una</b>, que es lo que hace cualquiera
	 * que mire quién se lleva un contrato conjunto; por eso la suma de los grupos tampoco es el total. Y en este
	 * eje no hay importe licitado: sumar el de la licitación por adjudicación lo contaría dos veces en los 195
	 * procesos que tienen más de una.
	 */
	SUPPLIER(Unit.AWARDS);

	/** Qué cuenta cada grupo. Va en la respuesta: sin decirlo, dos agregaciones parecen comparables y no lo son. */
	public enum Unit {
		PROCESSES, AWARDS
	}

	private final Unit unit;

	AggregationAxis(Unit unit) {
		this.unit = unit;
	}

	public Unit unit() {
		return unit;
	}

	/** Si un registro puede caer en más de un grupo, y por tanto la suma de los grupos no es el total. */
	public boolean isOverlapping() {
		return this == CPV || this == SUPPLIER;
	}

}
