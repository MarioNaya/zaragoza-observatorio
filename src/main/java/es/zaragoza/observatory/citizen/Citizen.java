package es.zaragoza.observatory.citizen;

import java.time.Instant;

import es.zaragoza.observatory.shared.DateWindow;
import es.zaragoza.observatory.shared.TerritorialTally;

/**
 * Superficie pública del módulo {@code citizen} para el resto de módulos (ADR-019 §9). La consume
 * {@code territory} para componer el cruce territorial; nadie más, y nunca sus tablas ni sus puertos (regla 3).
 * <p>
 * Deliberadamente pequeña: aquí solo sale lo que un cruce por junta necesita. El listado, los filtros, las
 * agregaciones por categoría o mes y el contraste entre junta resuelta y declarada son producto de la API de
 * {@code citizen} y no se replican aquí. Y por aquí <b>no sale ni saldrá texto de la queja</b>: no está en la
 * base de datos porque no se pidió al origen (ADR-012).
 */
public interface Citizen {

	/**
	 * Quejas por junta resuelta en la ventana dada, con la cobertura del conjunto (ADR-019 §6). La ventana se
	 * aplica sobre el <b>alta</b> de la queja ({@code requested_at}).
	 * <p>
	 * Los servicios {@code INTERNAL} <b>cuentan</b>, como en toda la API del módulo: excluirlos en silencio sería
	 * decidir qué es una queja (ADR-015).
	 */
	TerritorialTally requestsByDistrict(DateWindow window);

	/**
	 * Fin de la última ingesta con éxito, tomando la más reciente de los dos ejes (altas y cierres): lo que
	 * interesa saber es cuándo se miró el origen por última vez, no cuál de los dos recorridos fue.
	 * {@code null} si todavía no ha terminado ninguna.
	 */
	Instant ingestedAt();

}
