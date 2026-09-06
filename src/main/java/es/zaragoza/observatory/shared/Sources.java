package es.zaragoza.observatory.shared;

/**
 * Familias de fuentes municipales verificadas en la fase 0 (docs/spikes/README.md). Son los únicos valores
 * válidos de {@link DatasetRef#source()}. Las URL base viven en la configuración del módulo {@code ingestion},
 * no aquí.
 */
public final class Sources {

	/** Catálogo de datasets del portal «espacio de datos»: {@code /web/espacio-de-datos/servicio} (S0.1). */
	public static final String DATA_SPACE = "data-space";

	/** API REST v2 clásica de la sede electrónica: {@code /sede/servicio} (S0.3, S0.4, S0.5, S0.6). */
	public static final String SEDE = "sede";

	/** Contratación pública OCDS: {@code /sede/servicio/contratacion-publica/ocds} (S0.2). */
	public static final String OCDS = "ocds";

	/** Open311: {@code /api/recurso/open311} (S0.3). */
	public static final String OPEN311 = "open311";

	/**
	 * Federación estatal: {@code https://datos.gob.es/apidata/catalog/dataset/publisher/L01502973.json} (S0.1,
	 * S1.3). Otro host, otra paginación ({@code _page}/{@code _pageSize}, tope 200) y otra forma de respuesta.
	 */
	public static final String DATOS_GOB_ES = "datos-gob-es";

	private Sources() {
	}

}
