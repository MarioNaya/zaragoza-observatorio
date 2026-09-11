package es.zaragoza.observatory.spending.infrastructure.zaragoza;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import es.zaragoza.observatory.ingestion.IngestionJob;
import es.zaragoza.observatory.ingestion.RawPage;
import es.zaragoza.observatory.ingestion.SourceDescriptor;
import es.zaragoza.observatory.ingestion.SourceDescriptor.Pagination;
import es.zaragoza.observatory.ingestion.SourceDescriptor.Pagination.Mode;
import es.zaragoza.observatory.ingestion.SourceDescriptor.ResponseShape;
import es.zaragoza.observatory.shared.DatasetRef;
import es.zaragoza.observatory.spending.SpendingSources;
import es.zaragoza.observatory.spending.application.RegisterGrants;
import es.zaragoza.observatory.spending.infrastructure.SpendingProperties.Grants;

/**
 * Los cuatro trabajos de ingesta de las subvenciones (S3.3, ADR-018 §1). Van juntos porque comparten las reglas
 * de la fuente y las diferencias entre ellos caben en una línea cada una; separarlos en cuatro ficheros
 * repartiría por cuatro sitios lo que se entiende de una vez.
 * <p>
 * Los cuatro paginan por <b>desplazamiento</b> ({@code start}), incluidos los de la v2, y ninguno manda
 * {@code page}: el desplazamiento lo calcula {@code pageSize} aunque el tamaño lo fije {@code rows}, así que
 * mezclarlos solapa páginas en silencio (S3.3 §2). Y los cuatro mandan {@code sort=id asc}.
 * <p>
 * <b>Solo el de convocatorias guarda la página cruda.</b> Las otras tres traen, o pueden traer, identidad: las
 * concesiones llevan el título con el documento dentro antes de redactarlo, y las dos de la v2 llevan el
 * identificador enmascarado y no se pueden proyectar —{@code fl} sobre la v2 devuelve {@code &#123;&#125;}—. Guardar
 * catorce días una página con eso dentro sería conservar justo lo que ADR-018 decide no tener; es la misma
 * decisión que ADR-016 tomó en {@code urban}, y el precio es el mismo: no se puede reprocesar una página sin
 * volver a pedirla.
 */
public final class GrantIngestionJobs {

	private GrantIngestionJobs() {
	}

	/**
	 * Las convocatorias: 1.589 en cuatro peticiones y 776 KB con la proyección. Sin ella serían 21 MB, porque
	 * cada convocatoria trae dentro todas sus concesiones, con los nombres y los documentos de identidad
	 * (S3.3 §8).
	 */
	public static final class Calls extends GrantJob {

		public Calls(Grants properties, GrantJsonTranslator translator, RegisterGrants register) {
			super(properties, translator, register);
		}

		@Override
		public SourceDescriptor source() {
			return sede(SpendingSources.GRANT_CALLS, properties.callsUrl(), properties.callFields());
		}

		@Override
		public void handle(RawPage page) {
			register.registerCalls(translator.calls(page.body()));
		}

		/** La única de las cuatro sin identidad dentro: aquí la página cruda sí se guarda. */
		@Override
		public boolean keepsRawPayload() {
			return true;
		}
	}

	/**
	 * Las concesiones, que son el censo completo (46.925, 2013-2026). La proyección <b>no incluye
	 * {@code adjudicatario}</b>: es la garantía de ADR-018 §3.
	 * <p>
	 * El barrido va por marca de agua ({@code id=gt=}) salvo que se pida el completo: el identificador es
	 * creciente y FIQL lo filtra, así que la ingesta diaria cuesta una petición en vez de 94 (S3.3 §9).
	 */
	public static final class Concessions extends GrantJob {

		public Concessions(Grants properties, GrantJsonTranslator translator, RegisterGrants register) {
			super(properties, translator, register);
		}

		@Override
		public SourceDescriptor source() {
			var query = new LinkedHashMap<>(query(properties.grantFields()));
			if (!properties.fullSweep()) {
				register.watermark().ifPresent(id -> query.put(GrantListing.FILTER, GrantListing.above(id)));
			}
			return new SourceDescriptor(SpendingSources.GRANTS, properties.grantsUrl(), query,
					Pagination.offset(properties.rows()), ResponseShape.ENVELOPE);
		}

		@Override
		public void handle(RawPage page) {
			register.registerGrants(translator.grants(page.body()));
		}
	}

	/** El directorio de beneficiarios: 23.403 registros con 20.910 identificadores distintos (S3.3 §6). */
	public static final class Beneficiaries extends GrantJob {

		public Beneficiaries(Grants properties, GrantJsonTranslator translator, RegisterGrants register) {
			super(properties, translator, register);
		}

		@Override
		public SourceDescriptor source() {
			return v2(SpendingSources.GRANT_BENEFICIARIES, properties.beneficiariesUrl());
		}

		@Override
		public void handle(RawPage page) {
			register.registerBeneficiaries(translator.beneficiaries(page.body()));
		}
	}

	/** El enlace concesión → beneficiario, el único campo que la v1 no publica (ADR-018 §1). */
	public static final class Links extends GrantJob {

		public Links(Grants properties, GrantJsonTranslator translator, RegisterGrants register) {
			super(properties, translator, register);
		}

		@Override
		public SourceDescriptor source() {
			return v2(SpendingSources.GRANT_LINKS, properties.linksUrl());
		}

		@Override
		public void handle(RawPage page) {
			register.registerLinks(translator.links(page.body()));
		}
	}

	/** Lo común de los cuatro: las reglas de la fuente y la decisión de no guardar la página cruda. */
	abstract static class GrantJob implements IngestionJob {

		final Grants properties;
		final GrantJsonTranslator translator;
		final RegisterGrants register;

		GrantJob(Grants properties, GrantJsonTranslator translator, RegisterGrants register) {
			this.properties = properties;
			this.translator = translator;
			this.register = register;
		}

		@Override
		public Duration interval() {
			return properties.interval();
		}

		@Override
		public boolean keepsRawPayload() {
			return false;
		}

		SourceDescriptor sede(DatasetRef dataset, java.net.URI url, String fields) {
			return new SourceDescriptor(dataset, url, query(fields), Pagination.offset(properties.rows()),
					ResponseShape.ENVELOPE);
		}

		/**
		 * La v2 se pide entera porque no admite proyección, y se pagina por {@code start} con el tamaño en
		 * {@code pageSize}: su envoltorio es el de páginas, no el de la sede.
		 */
		SourceDescriptor v2(DatasetRef dataset, java.net.URI url) {
			return new SourceDescriptor(dataset, url, query(null),
					new Pagination(Mode.OFFSET, properties.pageSize(), "start", GrantListing.PAGE_SIZE),
					ResponseShape.PAGED_RECORDS);
		}

		Map<String, String> query(String fields) {
			var query = new LinkedHashMap<String, String>();
			query.put(GrantListing.SORT, GrantListing.SORT_BY_ID);
			if (fields != null && !fields.isBlank()) {
				query.put(GrantListing.FIELDS, fields);
			}
			return query;
		}
	}

}
