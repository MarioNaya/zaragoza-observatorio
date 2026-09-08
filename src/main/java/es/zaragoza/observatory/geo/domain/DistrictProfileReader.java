package es.zaragoza.observatory.geo.domain;

import java.util.Optional;

/**
 * Puerto de lectura del detalle de una junta ({@code distrito/{id}.json}), del que salen el {@code idpadron} y la
 * serie de padrón (S2.1). Es una petición por junta —29 al día— y no un {@code IngestionJob}, porque el contrato
 * de {@code ingestion} describe una URL, no 29; el listado no devuelve los indicadores aunque el Swagger declare
 * el campo (comprobado el 2026-09-08). Es el mismo patrón que el muestreo observado de {@code catalog} (ADR-005).
 * <p>
 * Nunca lanza por un fallo de la fuente: devuelve vacío y se reintenta en la siguiente ingesta.
 */
public interface DistrictProfileReader {

	Optional<DistrictProfile> read(int districtId);

}
