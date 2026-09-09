package es.zaragoza.observatory.urban;

import es.zaragoza.observatory.shared.DatasetRef;
import es.zaragoza.observatory.shared.Sources;

/** Referencias de las fuentes que ingiere el módulo {@code urban} (S2.4, ADR-016). */
public final class UrbanSources {

	/**
	 * Locales con licencia: {@code sede/servicio/registro-licencia.json} (dataset 1420). <b>Una sola
	 * referencia</b>, al contrario que {@code citizen}, que necesitaba dos: aquí el barrido completo y el
	 * incremental recorren el mismo eje ({@code id asc}) y solo se diferencian en si llevan filtro de fecha, así
	 * que comparten marca de agua y registro de ejecuciones (S2.4 §5).
	 */
	public static final DatasetRef PREMISES = DatasetRef.of(Sources.SEDE, "registro-licencia");

	private UrbanSources() {
	}

}
