package es.zaragoza.observatory.ingestion.domain;

import es.zaragoza.observatory.ingestion.RawPage;
import es.zaragoza.observatory.ingestion.SourceDescriptor;

/**
 * Puerto hacia la API municipal. El adaptador aplica las reglas de S0.5 (extensión, {@code rows}, timeouts,
 * concurrencia, reintentos solo en 5xx/timeouts, circuit breaker por fuente).
 */
public interface SourceGateway {

	/**
	 * Trae una página.
	 *
	 * @param source qué traer
	 * @param pageNumber índice de página desde 0
	 * @param start valor de {@code start} a enviar (ignorado por el adaptador cuando la paginación es {@code NONE})
	 * @throws SourceAccessException si la fuente no responde como se espera tras agotar los reintentos
	 */
	RawPage fetch(SourceDescriptor source, int pageNumber, int start);

}
