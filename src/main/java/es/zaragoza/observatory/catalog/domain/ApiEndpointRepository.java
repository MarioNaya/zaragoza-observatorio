package es.zaragoza.observatory.catalog.domain;

import java.time.Instant;
import java.util.List;

/**
 * Puerto de persistencia del inventario de endpoints (S1.2). El Swagger se ingiere entero en cada ejecución, así
 * que la escritura sincroniza la tabla con el documento (regla 5: reejecutar no duplica).
 */
public interface ApiEndpointRepository {

	/**
	 * Sincroniza el inventario con el documento recibido: inserta las operaciones nuevas, actualiza las existentes
	 * (misma clave {@code tag + method + path}) conservando {@code firstSeenAt}, y elimina las que ya no aparecen.
	 *
	 * @return número de operaciones tras la sincronización
	 */
	int replaceAll(List<ApiEndpoint> endpoints, Instant seenAt);

	/** Operaciones de un tag en el orden del documento; vacío si el tag no existe. */
	List<ApiEndpoint> findByTag(String tag);

	long count();

}
