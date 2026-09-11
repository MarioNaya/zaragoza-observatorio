package es.zaragoza.observatory.urban;

import java.time.Instant;

import es.zaragoza.observatory.shared.DateWindow;
import es.zaragoza.observatory.shared.TerritorialTally;

/**
 * Superficie pública del módulo {@code urban} para el resto de módulos (ADR-019 §9). La consume
 * {@code territory} para componer el cruce territorial; nadie más, y nunca sus tablas ni sus puertos (regla 3).
 * <p>
 * Son <b>dos</b> métodos y no uno porque cuentan <b>unidades distintas</b> (ADR-016 §7): un local con doce
 * licencias es un local y son doce licencias, y un solo método obligaría a elegir cuál de las dos cifras es «el
 * total».
 * <p>
 * En los dos, la ventana se aplica sobre el <b>alta del local</b>, nunca sobre el año del expediente de
 * licencia: una ventana de 2024 sobre {@link #licencesByDistrict} son las licencias de los locales dados de alta
 * en 2024, que no son las licencias de 2024 (ADR-019 §5). Quien quiera la serie por año de licencia la tiene en
 * {@code GET /api/v1/urban/aggregations?by=district_licence_year}.
 */
public interface Urban {

	/** Locales con licencia por junta resuelta en la ventana dada, con la cobertura del conjunto. */
	TerritorialTally premisesByDistrict(DateWindow window);

	/** Licencias por junta resuelta del local en la ventana dada, con la cobertura del conjunto. */
	TerritorialTally licencesByDistrict(DateWindow window);

	/** Fin de la última ingesta con éxito; {@code null} si todavía no ha terminado ninguna. */
	Instant ingestedAt();

}
