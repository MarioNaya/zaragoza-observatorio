package es.zaragoza.observatory.spending.application;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import es.zaragoza.observatory.spending.domain.Grant;
import es.zaragoza.observatory.spending.domain.GrantBeneficiary;
import es.zaragoza.observatory.spending.domain.GrantCall;
import es.zaragoza.observatory.spending.domain.GrantRepository;
import es.zaragoza.observatory.spending.domain.GrantLinks;

/**
 * Caso de uso «guardar lo que trae una página de subvenciones» (S3.3, ADR-018). Son cuatro entradas porque la
 * fuente son cuatro recursos independientes, y ninguna espera a las otras: cada una es un upsert idempotente
 * (regla 5) y el resultado no depende del orden en que lleguen.
 * <p>
 * La única que hace algo más que guardar es {@link #registerLinks(GrantLinks)}, y es justo la que sostiene la regla de
 * ADR-018 §4: el identificador enmascarado es la <b>segunda</b> señal de persona física, llega por este recurso y
 * <b>retira la identidad</b> de un beneficiario que el directorio había dejado con nombre.
 */
public class RegisterGrants {

	private static final Logger log = LoggerFactory.getLogger(RegisterGrants.class);

	private final GrantRepository repository;

	public RegisterGrants(GrantRepository repository) {
		this.repository = repository;
	}

	@Transactional
	public int registerCalls(List<GrantCall> calls) {
		return calls.isEmpty() ? 0 : repository.upsertCalls(calls);
	}

	@Transactional
	public int registerGrants(List<Grant> grants) {
		return grants.isEmpty() ? 0 : repository.upsertGrants(grants);
	}

	@Transactional
	public int registerBeneficiaries(List<GrantBeneficiary> beneficiaries) {
		return beneficiaries.isEmpty() ? 0 : repository.upsertBeneficiaries(beneficiaries);
	}

	/**
	 * Guarda el enlace concesión → beneficiario y aplica las dos consecuencias del identificador fiscal que trae
	 * ese mismo recurso: marcar como persona física a quien lo lleva enmascarado —retirándole nombre e
	 * identificador si los tenía— y guardar el NIF de quien es una persona jurídica.
	 */
	@Transactional
	public int registerLinks(GrantLinks links) {
		if (!links.masked().isEmpty()) {
			int withdrawn = repository.markNaturalPersons(links.masked());
			if (withdrawn > 0) {
				log.debug("spending: {} beneficiarios marcados como persona física por identificador enmascarado",
						withdrawn);
			}
		}
		if (!links.legalNif().isEmpty()) {
			repository.setLegalNif(links.legalNif());
		}
		return links.byGrant().isEmpty() ? 0 : repository.upsertLinks(links.byGrant());
	}

	/** La marca de agua de la ingesta incremental de concesiones (S3.3 §9). */
	public Optional<Long> watermark() {
		return repository.highestGrantId();
	}

}
