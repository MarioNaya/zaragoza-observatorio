package es.zaragoza.observatory.shared;

import java.time.ZoneId;

/** Las fechas sin zona de la API municipal son hora local de Zaragoza (S0.5, recomendación 4). */
public final class ZaragozaTime {

	public static final ZoneId ZONE = ZoneId.of("Europe/Madrid");

	private ZaragozaTime() {
	}

}
