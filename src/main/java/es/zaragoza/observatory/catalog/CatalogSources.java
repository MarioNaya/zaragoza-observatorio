package es.zaragoza.observatory.catalog;

import es.zaragoza.observatory.shared.DatasetRef;
import es.zaragoza.observatory.shared.Sources;

/** Referencias de las fuentes que ingiere el módulo {@code catalog} (S0.1). */
public final class CatalogSources {

	/** El catálogo de datasets del espacio de datos: {@code web/espacio-de-datos/servicio/catalogo.json}. */
	public static final DatasetRef CATALOG = DatasetRef.of(Sources.DATA_SPACE, "catalogo");

	private CatalogSources() {
	}

}
