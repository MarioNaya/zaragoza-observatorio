package es.zaragoza.observatory.spending.domain;

import java.util.List;
import java.util.Optional;

/**
 * Puerto de lectura de la fuente OCDS para lo que <b>no cabe en el ciclo de una página</b> de {@code ingestion}.
 * Son dos cosas, y las dos tienen la misma justificación que {@code DistrictProfileReader} en {@code geo}: el
 * contrato de {@code ingestion} describe una URL, y aquí hacen falta miles.
 * <ul>
 * <li>{@link #read(String)}: el detalle de un proceso. Son 8.001 peticiones para el histórico, así que las hace
 * el planificador del módulo por lotes (ADR-017 §5).</li>
 * <li>{@link #documentedOcids()}: el listado <b>sin filtro</b>, el que documenta la API. Se pide una vez por
 * ingesta para saber qué procesos esconde y para comprobar que sigue siendo subconjunto del ampliado.</li>
 * </ul>
 * Ninguna de las dos lanza por un fallo de la fuente: devuelven vacío o una lectura no concluyente, y se
 * reintenta. Lo que sí falla ruidosamente es la comprobación de subconjunto, y lo hace el caso de uso.
 */
public interface ReleaseSource {

	ReleaseRead read(String ocid);

	/**
	 * Los ocids del listado documentado, o vacío si la petición falla. <b>Vacío no es «ninguno»</b>: quien lo
	 * reciba no puede concluir que todos los procesos están escondidos, y por eso el caso de uso no marca nada
	 * cuando llega vacío (la salvaguarda de ADR-013 §2, aquí también).
	 */
	Optional<List<String>> documentedOcids();

}
