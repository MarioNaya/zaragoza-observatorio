package es.zaragoza.observatory.spending.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import es.zaragoza.observatory.spending.domain.ReleaseRead;
import es.zaragoza.observatory.spending.domain.ReleaseSource;
import es.zaragoza.observatory.spending.support.RecordingProcesses;

/**
 * La comprobación de ADR-017 §1: el listado documentado tiene que seguir siendo subconjunto del ampliado. Es la
 * única defensa contra que el interruptor {@code after} cambie de sentido y el censo deje de ser el universo sin
 * que nadie se entere.
 */
class CheckDocumentedListingTest {

	final RecordingProcesses processes = new RecordingProcesses();

	@Test
	void marcaLosQueEstanEnElListadoDocumentadoYCuentaLosQueEsconde() {
		processes.census.addAll(List.of("a", "b", "c", "d"));

		var summary = check(List.of("a", "b")).check();

		assertThat(summary.checked()).isTrue();
		assertThat(summary.documented()).isEqualTo(2);
		assertThat(summary.marked()).isEqualTo(2);
		assertThat(summary.hidden()).as("los 2.271 del histórico real").isEqualTo(2);
		assertThat(processes.documented).containsExactly("a", "b");
	}

	@Test
	void siElListadoDocumentadoDejaDeSerSubconjuntoFallaRuidosamente() {
		processes.census.addAll(List.of("a", "b"));

		assertThatIllegalStateException().isThrownBy(() -> check(List.of("a", "z")).check())
				.withMessageContaining("ya no es subconjunto");
		assertThat(processes.documented).as("no se marca nada si la comprobación falla").isEmpty();
	}

	@Test
	void unListadoQueNoSePudoLeerNoMarcaNada() {
		processes.census.addAll(List.of("a", "b"));

		var summary = check(null).check();

		assertThat(summary.checked()).isFalse();
		assertThat(processes.documented).isEmpty();
	}

	@Test
	void unListadoVacioTampocoDesmarcaNada() {
		// Salvaguarda de ADR-013 §2: un fallo de la fuente no puede parecerse a «no hay ninguno».
		processes.census.addAll(List.of("a", "b"));
		processes.documented.add("a");

		var summary = check(List.of()).check();

		assertThat(summary.checked()).isFalse();
		assertThat(processes.documented).containsExactly("a");
	}

	private CheckDocumentedListing check(List<String> documented) {
		ReleaseSource source = new ReleaseSource() {

			@Override
			public ReleaseRead read(String ocid) {
				throw new UnsupportedOperationException();
			}

			@Override
			public Optional<List<String>> documentedOcids() {
				return Optional.ofNullable(documented);
			}
		};
		return new CheckDocumentedListing(processes, source);
	}

}
