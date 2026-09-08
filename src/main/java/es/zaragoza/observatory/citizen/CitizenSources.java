package es.zaragoza.observatory.citizen;

import es.zaragoza.observatory.shared.DatasetRef;
import es.zaragoza.observatory.shared.Sources;

/**
 * Referencias de las fuentes que ingiere el módulo {@code citizen} (S0.3, S2.2).
 * <p>
 * Son <b>dos referencias sobre el mismo endpoint</b>, no una: un {@link DatasetRef} identifica una ejecución
 * periódica con su propio intervalo y su propia marca de agua, y las altas y los cierres se recorren por ejes
 * distintos ({@code requested_datetime} y {@code updated_datetime}). Un incremental que solo mirase las altas no
 * vería nunca el cierre de un expediente antiguo, y S2.2 encontró uno de 2015 cerrado en 2026.
 */
public final class CitizenSources {

	/** Altas: {@code sede/servicio/quejas-sugerencias/list.json} recorrido por {@code requested_datetime}. */
	public static final DatasetRef REQUESTS = DatasetRef.of(Sources.SEDE, "quejas-sugerencias");

	/** Cierres y actualizaciones: el mismo listado recorrido por {@code updated_datetime}. */
	public static final DatasetRef CLOSURES = DatasetRef.of(Sources.SEDE, "quejas-sugerencias-cierres");

	private CitizenSources() {
	}

}
