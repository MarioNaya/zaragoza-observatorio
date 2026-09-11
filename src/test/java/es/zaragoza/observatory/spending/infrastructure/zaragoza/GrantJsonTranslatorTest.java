package es.zaragoza.observatory.spending.infrastructure.zaragoza;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import es.zaragoza.observatory.spending.domain.Grant;
import es.zaragoza.observatory.spending.domain.GrantBeneficiary;
import es.zaragoza.observatory.spending.domain.GrantCall;
import es.zaragoza.observatory.spending.domain.GrantLinks;
import es.zaragoza.observatory.spending.domain.GrantTitle;
import es.zaragoza.observatory.support.Fixtures;
import tools.jackson.databind.json.JsonMapper;

/**
 * El traductor contra los documentos reales que grabó S3.3 (redactados: regla 22) y contra los casos que el
 * fixture no puede tener porque el fixture ya va redactado.
 */
class GrantJsonTranslatorTest {

	private final GrantJsonTranslator translator = new GrantJsonTranslator(JsonMapper.shared());

	@Test
	void leeLasConcesionesDelEnvoltorioDeLaSede() {
		var grants = translator.grants(Fixtures.text("grants/resolucion-rows2-fl.json"));

		assertThat(grants).hasSize(2);
		Grant first = grants.get(0);
		assertThat(first.id()).isEqualTo(1);
		assertThat(first.callId()).isEqualTo(59);
		assertThat(first.fileNumber()).isEqualTo("0618143/2014");
		assertThat(first.granted()).isEqualByComparingTo("21600");
		assertThat(first.grantedOn()).isEqualTo(LocalDate.of(2014, 10, 17));
		assertThat(first.requestedOn()).isEqualTo(LocalDate.of(2014, 11, 19));
		assertThat(first.year()).isEqualTo(2014);
	}

	/**
	 * La segunda garantía de ADR-018 §3: aunque el campo llegara —y en el fixture sin proyección llega—, el
	 * traductor no lo lee. La primera garantía es que la proyección no lo pide.
	 */
	@Test
	void noLeeElAdjudicatarioAunqueLlegueEnLaRespuesta() {
		String body = Fixtures.text("grants/resolucion-rows2-full.json");
		assertThat(body).contains("adjudicatario");

		var grants = translator.grants(body);

		assertThat(grants).hasSize(2);
		// Lo único que puede llevar identidad en el modelo es el título, y va redactado.
		assertThat(grants).allSatisfy(grant -> assertThat(GrantTitle.carriesIdentity(grant.title())).isFalse());
	}

	@Test
	void redactaElDocumentoDeIdentidadQueLaFuenteMeteEnElTitulo() {
		String body = """
				{"totalCount":1,"start":0,"rows":1,"result":[
				 {"id":46616,"title":"LINEA 2.2: OBRAS EN VIVIENDAS, NIF 12345678Z",
				  "expediente":"0005771/2025","importeConcedido":1619.02,
				  "fechaConcesion":"2026-02-02T00:00:00"}]}
				""";

		Grant grant = translator.grants(body).get(0);

		assertThat(grant.titleRedacted()).isTrue();
		assertThat(grant.title()).isEqualTo("LINEA 2.2: OBRAS EN VIVIENDAS, NIF " + GrantTitle.MARKER);
	}

	/** Siete concesiones traen año 0002, 0019 o 0022. No se corrigen ni se tiran (ADR-018 §7). */
	@Test
	void dejaPasarLasFechasImposibles() {
		String body = """
				{"totalCount":1,"start":0,"rows":1,"result":[
				 {"id":9,"title":"AYUDA","fechaConcesion":"0019-11-28T00:00:00","importeConcedido":300}]}
				""";

		assertThat(translator.grants(body).get(0).grantedOn()).isEqualTo(LocalDate.of(19, 11, 28));
	}

	@Test
	void leeLasConvocatoriasConLaLineaDelTercerNivel() {
		var calls = translator.calls(Fixtures.text("grants/convocatoria-rows2-fl.json"));

		assertThat(calls).hasSize(2);
		GrantCall first = calls.get(0);
		assertThat(first.id()).isEqualTo(1);
		assertThat(first.fiscalYear()).isEqualTo("2014");
		assertThat(first.budget()).isEqualByComparingTo("6375");
		assertThat(first.validFrom()).isEqualTo(LocalDate.of(2014, 9, 8));
		assertThat(first.managerId()).isEqualTo("31");
		assertThat(first.typeId()).isEqualTo("1");
		// La línea vive en el tercer nivel y solo llega con la ruta con punto (S3.3 §3).
		assertThat(first.lineId()).isEqualTo("8");
		// Y el cuarto nivel del ámbito también, con la suya.
		assertThat(first.areaId()).isEqualTo("2");
	}

	@Test
	void leeElDirectorioSinNingunDatoDeContacto() {
		var beneficiaries = translator.beneficiaries(Fixtures.text("grants/organization-rows3.json"));

		assertThat(beneficiaries).isNotEmpty();
		assertThat(beneficiaries).allSatisfy(b -> assertThat(b.legalNif()).isNull());
	}

	@Test
	void aplicaLaClasificacionDelDirectorioComoPrimeraSenal() {
		String body = """
				{"page":1,"pageSize":2,"totalRecords":2,"records":[
				 {"id":"626","title":"Datos de caracter personal","municipioTitle":"Zaragoza",
				  "classification":"<http://vocab.linkeddata.es/datosabiertos/kos/sector-publico/convenio/tipo-entidad/personas-fisicas>"},
				 {"id":"4565","title":"CLUB PATIN ZARAGOZA","streetAddress":"CALLE MAYOR 1","postalCode":"50001",
				  "contactPointTelephone":"976000000","contactPointEmail":"club@example.org",
				  "classification":"<http://vocab.linkeddata.es/datosabiertos/kos/sector-publico/convenio/tipo-entidad/entidad-deportiva>"}]}
				""";

		var beneficiaries = translator.beneficiaries(body);

		GrantBeneficiary person = beneficiaries.get(0);
		assertThat(person.naturalPerson()).isTrue();
		assertThat(person.name()).isNull();
		assertThat(person.classification()).isEqualTo("personas-fisicas");

		GrantBeneficiary club = beneficiaries.get(1);
		assertThat(club.naturalPerson()).isFalse();
		assertThat(club.name()).isEqualTo("CLUB PATIN ZARAGOZA");
		assertThat(club.classification()).isEqualTo("entidad-deportiva");
	}

	@Test
	void elEnlaceTraeLaSegundaSenalYElNifDeLaPersonaJuridicaPeroNuncaElEnmascarado() {
		String body = """
				{"page":1,"pageSize":3,"totalRecords":3,"records":[
				 {"id":2808,"beneficiario":"626","nifcif":"***332**","importeConcedido":1225},
				 {"id":2850,"beneficiario":"4565","nifcif":"G50423219","importeConcedido":200},
				 {"id":2851,"beneficiario":"9999","nifcif":"NIF","importeConcedido":50}]}
				""";

		GrantLinks links = translator.links(body);

		assertThat(links.byGrant()).containsEntry(2808L, "626").containsEntry(2850L, "4565");
		assertThat(links.masked()).containsExactly("626");
		assertThat(links.legalNif()).containsExactly(java.util.Map.entry("4565", "G50423219"));
		// El enmascarado no acaba en ningún sitio salvo en la señal.
		assertThat(links.legalNif()).doesNotContainKey("626");
	}

	@Test
	void aceptaLasDosFormasDelEnvoltorioYRechazaLoQueNoLoEs() {
		assertThat(translator.grants("{\"records\":[{\"id\":7,\"importeConcedido\":1}]}")).hasSize(1);
		assertThatThrownBy(() -> translator.grants("{\"status\":400}"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("result[] or records[]");
	}

}
