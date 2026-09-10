package es.zaragoza.observatory.spending.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import org.springframework.transaction.annotation.Transactional;

import es.zaragoza.observatory.spending.domain.BudgetRepository;

/**
 * Censa las instantáneas del presupuesto: da de alta las fechas nuevas y refresca la última vez que se vio a las
 * conocidas (regla 5, upsert idempotente).
 * <p>
 * El censo <b>no trae contenido</b>: {@code gasto-corriente/fecha.json} publica 140 URL y nada más. Las partidas
 * llegan después, instantánea a instantánea, por el planificador del módulo (S3.2 §11).
 * <p>
 * Un censo vacío <b>no borra ni marca nada</b>, igual que en ADR-013 §2: la fuente publicando cero fechas es un
 * fallo suyo, no la desaparición del presupuesto municipal.
 */
public class RegisterBudgetSnapshots {

	private final BudgetRepository budget;

	public RegisterBudgetSnapshots(BudgetRepository budget) {
		this.budget = Objects.requireNonNull(budget);
	}

	@Transactional
	public int register(List<LocalDate> dates, Instant seenAt) {
		if (dates.isEmpty()) {
			return 0;
		}
		return budget.upsertCensus(dates, seenAt);
	}

}
