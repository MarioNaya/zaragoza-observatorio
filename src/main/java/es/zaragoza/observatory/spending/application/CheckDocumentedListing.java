package es.zaragoza.observatory.spending.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import es.zaragoza.observatory.spending.domain.ContractingProcessRepository;
import es.zaragoza.observatory.spending.domain.ReleaseSource;

/**
 * Pide el listado <b>sin filtro</b> —el que documenta la API— y hace con él las dos cosas que S3.1 dejó pedidas
 * (ADR-017 §1).
 * <p>
 * <b>1. Comprueba que sigue siendo subconjunto del ampliado.</b> El censo se pide con
 * {@code after=2030-01-01T00:00:00Z}, que no es una fecha sino un interruptor: por debajo de un umbral situado
 * entre el 1 y el 2 de enero de 2017 no hace nada y por encima el valor da igual. Ese umbral puede moverse, y la
 * defensa posible es esta: si el listado documentado deja de estar contenido en el ampliado, la enumeración ya
 * no es completa y hay que <b>fallar ruidosamente</b> en vez de adivinar.
 * <p>
 * <b>2. Publica qué procesos esconde.</b> 2.271 de 8.001 no aparecen en él, así que quien pagine el listado
 * documentado se lleva el 71,6 % del histórico creyendo que lo tiene entero. Eso es un hecho fechado sobre el
 * listado, no una afirmación sobre el dato ni sobre lo que hizo el publicador (regla 6).
 * <p>
 * Un listado vacío o que no se pudo leer <b>no marca nada</b>: es la misma salvaguarda que ADR-013 §2 en el
 * catálogo, porque un fallo de la fuente no puede parecerse a «no hay ninguno».
 */
public class CheckDocumentedListing {

	private static final Logger log = LoggerFactory.getLogger(CheckDocumentedListing.class);

	private final ContractingProcessRepository processes;
	private final ReleaseSource source;

	public CheckDocumentedListing(ContractingProcessRepository processes, ReleaseSource source) {
		this.processes = Objects.requireNonNull(processes);
		this.source = Objects.requireNonNull(source);
	}

	/**
	 * <b>Sin {@code @Transactional} a propósito.</b> Este método hace una petición HTTP, y envolverlo en una
	 * transacción dejaría una conexión de la base de datos retenida mientras la fuente responde —hasta 60 s si
	 * agota el tiempo de lectura—. Las dos escrituras que necesita ya son transaccionales cada una en el
	 * adaptador, y no hacen falta juntas: si entre las dos apareciera un ocid nuevo, se marcaría en la ingesta
	 * siguiente.
	 */
	public Summary check() {
		Optional<List<String>> documented = source.documentedOcids();
		if (documented.isEmpty()) {
			log.warn("spending: el listado documentado no se pudo leer; no se marca nada");
			return new Summary(false, 0, 0, 0);
		}
		Set<String> listed = new LinkedHashSet<>(documented.get());
		if (listed.isEmpty()) {
			log.warn("spending: el listado documentado llegó vacío; no se marca nada (salvaguarda de ADR-013 §2)");
			return new Summary(false, 0, 0, 0);
		}
		Set<String> census = processes.allOcids();
		Set<String> onlyInDocumented = new LinkedHashSet<>(listed);
		onlyInDocumented.removeAll(census);
		if (!onlyInDocumented.isEmpty()) {
			// Si esto salta, el interruptor `after` ha cambiado de sentido y el censo ya no es el universo. No se
			// adivina: se para y se mira (ADR-017 §1).
			throw new IllegalStateException("el listado documentado ya no es subconjunto del ampliado: "
					+ onlyInDocumented.size() + " ocids solo aparecen en el documentado, p. ej. "
					+ onlyInDocumented.stream().limit(5).toList());
		}
		int marked = processes.markDocumentedList(listed);
		int hidden = census.size() - marked;
		log.info("spending: listado documentado con {} ocids; {} de {} procesos no aparecen en él", listed.size(),
				hidden, census.size());
		return new Summary(true, listed.size(), marked, hidden);
	}

	/**
	 * @param checked si se llegó a comprobar y marcar
	 * @param documented ocids del listado sin filtro
	 * @param marked procesos marcados como presentes en él
	 * @param hidden procesos del censo que ese listado no publica
	 */
	public record Summary(boolean checked, int documented, int marked, int hidden) {
	}

}
