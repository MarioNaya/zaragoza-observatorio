package es.zaragoza.observatory.spending.domain;

/**
 * En qué situación está el detalle de un proceso. Los cuatro estados se publican tal cual, porque el hueco es
 * parte del dato: el 29,7 % del universo no tiene release y son justo los expedientes recientes (S3.1 §3).
 */
public enum ReleaseStatus {

	/** El ocid está en el censo y su detalle no se ha pedido todavía. */
	PENDING,

	/** 200 con al menos un release. Son 5.606 y ninguno trae más de uno: no hay compiled releases que resolver. */
	PUBLISHED,

	/**
	 * 200 con {@code releases} vacío. Son 16, y existen para que nadie escriba en el código que un 200 garantiza
	 * un release.
	 */
	EMPTY,

	/**
	 * 404. El proceso existe en el censo y su release aún no se ha publicado; no es un error de ingesta ni un
	 * proceso inexistente. Llega además con un cuerpo que dice {@code {"status":400,…}}: la cabecera y el cuerpo
	 * se contradicen, y manda la cabecera.
	 */
	ABSENT;

	/** Si el estado permite que el detalle aparezca más adelante (y por tanto merezca reintento). */
	public boolean isMissing() {
		return this == PENDING || this == EMPTY || this == ABSENT;
	}

}
