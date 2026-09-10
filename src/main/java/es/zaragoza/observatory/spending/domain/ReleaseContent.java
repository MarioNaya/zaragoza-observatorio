package es.zaragoza.observatory.spending.domain;

import java.time.Instant;
import java.util.List;

/**
 * Lo que trae el release package de un proceso, ya traducido y sin nada que no vaya a guardarse. Es lo que el
 * puerto {@link ReleaseSource} devuelve y lo que el caso de uso funde con la fila del censo; el proceso no se
 * reconstruye desde fuera para que la contabilidad de intentos y de fechas de aparición no dependa del adaptador.
 * <p>
 * <b>Un package trae como mucho un release</b>: 5.606 traen exactamente uno y 16 traen cero. Ninguno trae más,
 * así que no hay que implementar compiled releases (S3.1 §4).
 *
 * @param publishedAt {@code publishedDate} del package
 * @param tags {@code releases[].tag[]} unido por comas
 * @param tender la licitación, nunca {@code null}
 * @param cpvs códigos CPV de los artículos; vacío en el 57,3 % de los procesos
 */
public record ReleaseContent(Instant publishedAt, String releaseId, String tags, String initiationType,
		Tender tender, String procuringEntityName, String procuringEntityId, List<Award> awards,
		List<Contract> contracts, List<Cpv> cpvs) {

	public ReleaseContent {
		tender = tender == null ? Tender.NONE : tender;
		awards = awards == null ? List.of() : List.copyOf(awards);
		contracts = contracts == null ? List.of() : List.copyOf(contracts);
		cpvs = cpvs == null ? List.of() : List.copyOf(cpvs);
	}

}
