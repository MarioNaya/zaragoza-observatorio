/**
 * Actividad urbana privada registrada por el ayuntamiento (ADR-016): hoy, los locales con licencia
 * ({@code registro-licencia}, S2.4). No es gasto público —eso es {@code spending}, ADR-003— ni servicio
 * municipal: es lo que pasa en los bajos de la ciudad, con su epígrafe, su año de licencia y su punto.
 * <p>
 * Depende de {@code geo} porque {@code geo} es shared kernel: le pide la junta de un punto. No al revés. Y no
 * depende de {@code citizen}: que las dos fuentes sean territoriales no las hace vecinas, y cruzarlas es trabajo
 * de quien lee, no del modelo.
 */
@ApplicationModule(displayName = "Urban", allowedDependencies = { "shared", "ingestion", "geo" })
package es.zaragoza.observatory.urban;

import org.springframework.modulith.ApplicationModule;
