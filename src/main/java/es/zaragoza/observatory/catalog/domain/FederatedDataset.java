package es.zaragoza.observatory.catalog.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Un dataset del publicador municipal tal como lo lista datos.gob.es (S1.3): la federación estatal. Se enlaza con
 * la ficha del catálogo por {@code sourceId}, que datos.gob.es publica en {@code identifier}
 * ({@code …/servicio/catalogo/<id>}, en el 100 % de los casos). Puede no tener ficha en el listado municipal:
 * las partes de series y colecciones están federadas pero {@code catalogo.json} no las devuelve.
 *
 * @param sourceId {@code id} municipal extraído de {@code identifier}
 * @param url {@code _about}: la ficha en datos.gob.es
 * @param title {@code title[0]._value}, o {@code null}
 * @param firstSeenAt primera ingesta en la que apareció
 * @param lastSeenAt última ingesta en la que apareció
 */
public record FederatedDataset(int sourceId, String url, String title, Instant firstSeenAt, Instant lastSeenAt) {

	public FederatedDataset {
		if (sourceId <= 0) {
			throw new IllegalArgumentException("sourceId must be positive");
		}
		if (url == null || url.isBlank()) {
			throw new IllegalArgumentException("url must not be blank (dataset " + sourceId + ")");
		}
		title = title == null || title.isBlank() ? null : title.strip();
	}

	public FederatedDataset seen(Instant firstSeenAt, Instant lastSeenAt) {
		return new FederatedDataset(sourceId, url, title, Objects.requireNonNull(firstSeenAt),
				Objects.requireNonNull(lastSeenAt));
	}

}
