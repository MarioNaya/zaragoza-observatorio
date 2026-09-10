package es.zaragoza.observatory.spending.domain;

/**
 * El resultado de pedir el detalle de un proceso. Distingue cuatro desenlaces porque no son el mismo, y
 * confundirlos es lo que convierte un fallo de red en un «este proceso no existe».
 * <ul>
 * <li>{@link #published}: 200 con release.</li>
 * <li>{@link #empty}: 200 con {@code releases} vacío. Son 16, y por eso un 200 no garantiza release.</li>
 * <li>{@link #absent}: 404. El release aún no está publicado; el proceso sí existe. Ojo: el cuerpo del 404 dice
 * {@code {"status":400,…}} y manda la cabecera.</li>
 * <li>{@link #unreadable}: la petición no llegó a concluir (tiempo agotado, 5xx, JSON ilegible). <b>No</b> es un
 * 404: no cambia el estado del proceso ni cuenta como intento fallido de publicación, solo aplaza el siguiente
 * intento.</li>
 * </ul>
 *
 * @param status estado observado, {@code null} cuando la lectura no concluyó
 * @param content contenido del release, solo cuando el estado es {@code PUBLISHED}
 * @param conclusive si la respuesta permite afirmar algo sobre el proceso
 */
public record ReleaseRead(String ocid, ReleaseStatus status, ReleaseContent content, boolean conclusive) {

	public ReleaseRead {
		if (ocid == null || ocid.isBlank()) {
			throw new IllegalArgumentException("ocid must not be blank");
		}
		if (conclusive == (status == null)) {
			throw new IllegalArgumentException("una lectura concluyente tiene estado y una no concluyente no");
		}
		if ((status == ReleaseStatus.PUBLISHED) != (content != null)) {
			throw new IllegalArgumentException("hay contenido exactamente cuando el estado es PUBLISHED");
		}
	}

	public static ReleaseRead published(String ocid, ReleaseContent content) {
		return new ReleaseRead(ocid, ReleaseStatus.PUBLISHED, content, true);
	}

	public static ReleaseRead empty(String ocid) {
		return new ReleaseRead(ocid, ReleaseStatus.EMPTY, null, true);
	}

	public static ReleaseRead absent(String ocid) {
		return new ReleaseRead(ocid, ReleaseStatus.ABSENT, null, true);
	}

	public static ReleaseRead unreadable(String ocid) {
		return new ReleaseRead(ocid, null, null, false);
	}

}
