package es.zaragoza.observatory.catalog.domain;

/**
 * Cómo se ha observado una ficha (S1.1, recomendación 1). Cada método mide una cosa y solo esa; la API lo
 * publica junto a la medida para que el consumidor sepa qué está viendo (regla 6: ninguna etiqueta interpretativa).
 */
public enum ObservationMethod {

	/** {@code HEAD} a las distribuciones de fichero: {@code observedLastChange} = máximo {@code Last-Modified}. */
	FILE_HEADERS,
	/**
	 * {@code GET <endpoint>.json?rows=1&sort=<campo> desc} en la API de la sede: {@code observedLastChange} = valor de
	 * {@code <campo>} (en {@code observationDetail}) del primer registro; {@code observedRecords} = {@code totalCount}.
	 */
	API_MAX_DATE,
	/** {@code GET <endpoint>.json?rows=1} sin campo de fecha utilizable: solo {@code observedRecords}. */
	API_COUNT,
	/** {@code GetFeature&resultType=hits} en WFS: {@code observedRecords} = {@code numberMatched}. */
	WFS_HITS,
	/** La ficha no tiene ninguna distribución de los tipos anteriores (WMS, SPARQL, buscadores, HTML, ninguna). */
	NOT_OBSERVABLE

}
