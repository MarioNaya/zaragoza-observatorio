package es.zaragoza.observatory.catalog;

import es.zaragoza.observatory.shared.DatasetRef;
import es.zaragoza.observatory.shared.Sources;

/** Referencias de las fuentes que ingiere el módulo {@code catalog} (S0.1). */
public final class CatalogSources {

	/** El catálogo de datasets del espacio de datos: {@code web/espacio-de-datos/servicio/catalogo.json}. */
	public static final DatasetRef CATALOG = DatasetRef.of(Sources.DATA_SPACE, "catalogo");

	/** El Swagger 2.0 de la API municipal, inventario de endpoints: {@code sede/servicio/catalogo/api.json} (S1.2). */
	public static final DatasetRef API_INVENTORY = DatasetRef.of(Sources.SEDE, "catalogo/api");

	/** Los datasets del publicador municipal en datos.gob.es: {@code apidata/catalog/dataset/publisher/L01502973.json} (S1.3). */
	public static final DatasetRef FEDERATION = DatasetRef.of(Sources.DATOS_GOB_ES, "publisher/L01502973");

	private CatalogSources() {
	}

}
