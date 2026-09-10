package es.zaragoza.observatory.spending.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.transaction.annotation.Transactional;

import es.zaragoza.observatory.spending.domain.ContractingProcessRepository;

/**
 * Censa los procesos de contratación: da de alta los ocids nuevos y refresca la última vez que se vio a los
 * conocidos (regla 5, upsert idempotente).
 * <p>
 * El censo <b>no trae contenido</b>. El listado de esta fuente publica solo {@code ocid} e {@code id}: ni fecha,
 * ni importe, ni objeto. Todo lo demás llega después, proceso a proceso, por el planificador del módulo
 * (ADR-017 §5). Por eso un proceso recién censado es una fila legítima con estado {@code PENDING} y no un
 * registro a medias.
 */
public class RegisterProcesses {

	private final ContractingProcessRepository processes;

	public RegisterProcesses(ContractingProcessRepository processes) {
		this.processes = Objects.requireNonNull(processes);
	}

	@Transactional
	public int register(List<String> ocids, Instant seenAt) {
		if (ocids.isEmpty()) {
			return 0;
		}
		return processes.upsertCensus(ocids, seenAt);
	}

}
