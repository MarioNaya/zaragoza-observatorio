package es.zaragoza.observatory.spending.domain;

import java.util.Map;
import java.util.Set;

/**
 * Lo que aporta una página del recurso de la v2 (ADR-018 §1 y §4). Es el único sitio del que sale el enlace entre
 * una concesión y su beneficiario, porque la v1 —que es el censo completo— no lo publica.
 * <p>
 * De ese recurso se leen tres campos y ninguno más. El identificador fiscal enmascarado <b>no se guarda</b>: solo
 * sirve para decidir que el beneficiario es una persona física, que es la señal que el directorio se deja en 107
 * casos.
 *
 * @param byGrant concesión → beneficiario
 * @param masked beneficiarios a los que alguna concesión asocia un identificador enmascarado: son personas
 * físicas, y guardarlos así <b>retira</b> el nombre que el directorio les hubiera dejado
 * @param legalNif beneficiario → NIF de persona jurídica, que sí entra
 */
public record GrantLinks(Map<Long, String> byGrant, Set<String> masked, Map<String, String> legalNif) {

	public GrantLinks {
		byGrant = byGrant == null ? Map.of() : Map.copyOf(byGrant);
		masked = masked == null ? Set.of() : Set.copyOf(masked);
		legalNif = legalNif == null ? Map.of() : Map.copyOf(legalNif);
	}

	public boolean isEmpty() {
		return byGrant.isEmpty() && masked.isEmpty() && legalNif.isEmpty();
	}

}
