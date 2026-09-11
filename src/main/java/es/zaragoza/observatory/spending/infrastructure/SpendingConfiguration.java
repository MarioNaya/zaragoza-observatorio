package es.zaragoza.observatory.spending.infrastructure;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import es.zaragoza.observatory.spending.application.CheckDocumentedListing;
import es.zaragoza.observatory.spending.application.ReadBudgetSnapshots;
import es.zaragoza.observatory.spending.application.ReadReleases;
import es.zaragoza.observatory.spending.application.RegisterBudgetSnapshots;
import es.zaragoza.observatory.spending.application.RegisterGrants;
import es.zaragoza.observatory.spending.application.RegisterProcesses;
import es.zaragoza.observatory.spending.domain.BudgetRepository;
import es.zaragoza.observatory.spending.domain.BudgetSource;
import es.zaragoza.observatory.spending.domain.ContractingProcessRepository;
import es.zaragoza.observatory.spending.domain.GrantRepository;
import es.zaragoza.observatory.spending.domain.ReleaseSource;
import es.zaragoza.observatory.spending.domain.RetrySchedule;
import es.zaragoza.observatory.spending.domain.SnapshotSchedule;
import es.zaragoza.observatory.spending.infrastructure.zaragoza.BudgetHttpSource;
import es.zaragoza.observatory.spending.infrastructure.zaragoza.BudgetLineJsonTranslator;
import es.zaragoza.observatory.spending.infrastructure.zaragoza.GrantIngestionJobs;
import es.zaragoza.observatory.spending.infrastructure.zaragoza.GrantJsonTranslator;
import es.zaragoza.observatory.spending.infrastructure.zaragoza.OcdsHttpReleaseSource;
import es.zaragoza.observatory.spending.infrastructure.zaragoza.OcdsListJsonTranslator;
import es.zaragoza.observatory.spending.infrastructure.zaragoza.OcdsReleaseJsonTranslator;

/** Cableado del módulo {@code spending}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SpendingProperties.class)
class SpendingConfiguration {

	/**
	 * El {@code RestClient} es el bean único de la aplicación (regla 23): timeouts, redirecciones y
	 * {@code User-Agent} vienen de la configuración común, y ningún módulo construye otro.
	 */
	@Bean
	ReleaseSource ocdsReleaseSource(RestClient rest, OcdsReleaseJsonTranslator releaseTranslator,
			OcdsListJsonTranslator listTranslator, SpendingProperties properties) {
		return new OcdsHttpReleaseSource(rest, releaseTranslator, listTranslator, properties);
	}

	@Bean
	RetrySchedule ocdsRetrySchedule(SpendingProperties properties) {
		var releases = properties.releases();
		return new RetrySchedule(releases.missingInitialBackoff(), releases.missingMaxBackoff(),
				releases.activeRefresh(), releases.publishedRefresh());
	}

	@Bean
	RegisterProcesses registerProcesses(ContractingProcessRepository processes) {
		return new RegisterProcesses(processes);
	}

	@Bean
	CheckDocumentedListing checkDocumentedListing(ContractingProcessRepository processes, ReleaseSource source) {
		return new CheckDocumentedListing(processes, source);
	}

	@Bean
	ReadReleases readReleases(ContractingProcessRepository processes, ReleaseSource source, RetrySchedule schedule,
			Clock clock) {
		return new ReadReleases(processes, source, schedule, clock);
	}

	// --- presupuesto de gastos (S3.2) --------------------------------------------------------------------

	@Bean
	BudgetSource budgetSource(RestClient rest, BudgetLineJsonTranslator translator, SpendingProperties properties) {
		return new BudgetHttpSource(rest, translator, properties);
	}

	@Bean
	SnapshotSchedule budgetSnapshotSchedule(SpendingProperties properties) {
		var budget = properties.budget();
		return new SnapshotSchedule(budget.missingInitialBackoff(), budget.missingMaxBackoff(),
				budget.latestRefresh());
	}

	@Bean
	RegisterBudgetSnapshots registerBudgetSnapshots(BudgetRepository budget) {
		return new RegisterBudgetSnapshots(budget);
	}

	@Bean
	ReadBudgetSnapshots readBudgetSnapshots(BudgetRepository budget, BudgetSource source, SnapshotSchedule schedule,
			Clock clock) {
		return new ReadBudgetSnapshots(budget, source, schedule, clock);
	}

	// --- subvenciones (S3.3, ADR-018) --------------------------------------------------------------------

	@Bean
	RegisterGrants registerGrants(GrantRepository grants) {
		return new RegisterGrants(grants);
	}

	/**
	 * Los cuatro recursos son cuatro trabajos de ingesta y no un planificador propio, al revés que el detalle de
	 * la contratación y las instantáneas del presupuesto: aquí todo cabe en páginas de una lista, y la carga
	 * entera son 145 peticiones (S3.3 §9).
	 */
	@Bean
	GrantIngestionJobs.Calls grantCallsIngestionJob(SpendingProperties properties, GrantJsonTranslator translator,
			RegisterGrants register) {
		return new GrantIngestionJobs.Calls(properties.grants(), translator, register);
	}

	@Bean
	GrantIngestionJobs.Concessions grantsIngestionJob(SpendingProperties properties, GrantJsonTranslator translator,
			RegisterGrants register) {
		return new GrantIngestionJobs.Concessions(properties.grants(), translator, register);
	}

	@Bean
	GrantIngestionJobs.Beneficiaries grantBeneficiariesIngestionJob(SpendingProperties properties,
			GrantJsonTranslator translator, RegisterGrants register) {
		return new GrantIngestionJobs.Beneficiaries(properties.grants(), translator, register);
	}

	@Bean
	GrantIngestionJobs.Links grantLinksIngestionJob(SpendingProperties properties, GrantJsonTranslator translator,
			RegisterGrants register) {
		return new GrantIngestionJobs.Links(properties.grants(), translator, register);
	}

}
