package es.zaragoza.observatory.spending.domain;

/**
 * Situación de la lectura de una instantánea del presupuesto (S3.2 §11). Es el mismo vocabulario que
 * {@link ReleaseStatus} y por la misma razón: el censo dice qué instantáneas existen, y el contenido llega
 * después; una instantánea censada y aún sin leer es una fila legítima, no un registro a medias.
 */
public enum SnapshotStatus {

	/** Está en el censo y todavía no se ha pedido. */
	PENDING,

	/** Se leyó y trajo partidas. */
	LOADED,

	/** Respondió 200 sin una sola partida. No se ha observado ninguna; si aparece, hay que mirarla. */
	EMPTY,

	/** El censo la publica y su URL no responde. Tampoco se ha observado ninguna. */
	ABSENT

}
