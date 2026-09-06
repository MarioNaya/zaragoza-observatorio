package es.zaragoza.observatory.catalog.domain;

/**
 * Puerto hacia las distribuciones de una ficha (S1.1, recomendación 2): el adaptador prueba, en este orden, la
 * API de la sede, los ficheros descargables y el WFS, y devuelve la primera observación con medida; si ninguna
 * la tiene, el primer intento fallido; si no hay nada que intentar, {@link Observation#notObservable}.
 * Nunca lanza por un fallo de la fuente: lo devuelve como observación fallida.
 */
public interface DistributionObserver {

	Observation observe(Dataset dataset);

}
