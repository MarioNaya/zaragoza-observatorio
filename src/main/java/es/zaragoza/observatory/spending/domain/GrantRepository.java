package es.zaragoza.observatory.spending.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Puerto de persistencia de las subvenciones. Hay <b>cuatro escrituras</b> porque la fuente son cuatro recursos
 * independientes y ninguno espera a otro: las convocatorias y el directorio de beneficiarios vienen de un sitio,
 * las concesiones de otro y el enlace entre concesión y beneficiario de un tercero (ADR-018 §1).
 * <p>
 * Por eso el enlace vive en su propia tabla en vez de en una columna de la concesión: así el orden de ingesta da
 * igual y no hacen falta filas a medias. Si un día llega un enlace de una concesión que todavía no se ha leído,
 * se guarda igual y se resuelve al leer.
 * <p>
 * Todo upsert es idempotente (regla 5) y <b>nada se borra</b>: una concesión que dejara de aparecer conserva su
 * fila, como las fichas dadas de baja de ADR-013.
 */
public interface GrantRepository {

	/** Da de alta o actualiza convocatorias. */
	int upsertCalls(List<GrantCall> calls);

	/** Da de alta o actualiza concesiones. */
	int upsertGrants(List<Grant> grants);

	/** Da de alta o actualiza beneficiarios. El directorio repite identificadores: el upsert los absorbe. */
	int upsertBeneficiaries(List<GrantBeneficiary> beneficiaries);

	/**
	 * Guarda el enlace concesión → beneficiario que publica la v2.
	 *
	 * @param links identificador de concesión → identificador de beneficiario
	 */
	int upsertLinks(Map<Long, String> links);

	/**
	 * Marca como persona física a los beneficiarios que llevan el identificador enmascarado y <b>les retira</b>
	 * el nombre y el NIF si los tenían (ADR-018 §4). Es la señal que el directorio se deja en 107 casos, y queda
	 * anotada en la fila para que releer el directorio no la deshaga.
	 *
	 * @return cuántas filas cambiaron
	 */
	int markNaturalPersons(Set<String> beneficiaryIds);

	/**
	 * Guarda el NIF de persona jurídica que publica la v2. <b>Nunca</b> toca a un beneficiario marcado como
	 * persona física: ahí el identificador que publica la fuente viene enmascarado y no entra.
	 */
	int setLegalNif(Map<String, String> byBeneficiary);

	/**
	 * El identificador de concesión más alto ya guardado, que es la marca de agua de la ingesta incremental: el
	 * identificador de esta fuente es creciente y FIQL lo filtra (S3.3 §9). Vacío si no hay ninguna.
	 */
	Optional<Long> highestGrantId();

	/** Una página de concesiones en el orden pedido, con su convocatoria y su beneficiario resueltos. */
	GrantRead.Page search(GrantQuery query);

	Optional<GrantRead> grant(long id);

	/** Las convocatorias, de la más reciente a la más antigua. */
	List<GrantCall> calls();

	Optional<GrantCall> call(int id);

	/** Agrega por el eje pedido, con los filtros de la consulta aplicados. */
	List<GrantBucket> aggregate(GrantAxis axis, GrantQuery filters);

	GrantTotals totals();

}
