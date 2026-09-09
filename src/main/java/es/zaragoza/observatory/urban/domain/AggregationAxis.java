package es.zaragoza.observatory.urban.domain;

/**
 * Ejes de agregación admitidos (SPEC.md §4.7, ADR-016 §7). Están separados en dos familias porque
 * <b>cuentan unidades distintas</b>: un local con doce licencias es un local y son doce licencias, y sumar las
 * dos cosas en un mismo «total» daría una cifra sin significado.
 */
public enum AggregationAxis {

	/** Locales por junta resuelta. Los que no tienen junta salen aparte, nunca repartidos (regla 7). */
	DISTRICT(Unit.PREMISES),
	/** Locales por agrupación del epígrafe IAE, que es el nivel por el que la taxonomía agrupa de verdad. */
	ACTIVITY(Unit.PREMISES),
	/** Locales por {@code estado} del origen, publicado como código porque no hay taxonomía (ADR-016 §6). */
	STATUS(Unit.PREMISES),
	/** Licencias por año del expediente. Es la serie temporal de la fuente. */
	LICENCE_YEAR(Unit.LICENCES),
	/** Licencias por tipo, con el nombre que publica el origen. */
	LICENCE_TYPE(Unit.LICENCES),
	/**
	 * Licencias por junta y año: el cruce que hace falta para una serie territorial. Cada grupo trae el padrón de
	 * <b>ese</b> año (ADR-015), y la cobertura de punto de cada año va aparte, porque dentro de un grupo
	 * territorial vale siempre 1 por construcción.
	 */
	DISTRICT_LICENCE_YEAR(Unit.LICENCES);

	/** Qué cuenta cada grupo. Va en la respuesta: sin decirlo, dos agregaciones parecen comparables y no lo son. */
	public enum Unit {
		PREMISES, LICENCES
	}

	private final Unit unit;

	AggregationAxis(Unit unit) {
		this.unit = unit;
	}

	public Unit unit() {
		return unit;
	}

	public boolean isTerritorial() {
		return this == DISTRICT || this == DISTRICT_LICENCE_YEAR;
	}

}
