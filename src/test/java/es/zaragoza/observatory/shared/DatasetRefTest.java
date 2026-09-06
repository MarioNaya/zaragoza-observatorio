package es.zaragoza.observatory.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class DatasetRefTest {

	@Test
	void keyRoundTrips() {
		var ref = DatasetRef.of(Sources.DATA_SPACE, "catalogo");

		assertThat(ref.key()).isEqualTo("data-space:catalogo");
		assertThat(DatasetRef.fromKey("data-space:catalogo")).isEqualTo(ref);
		assertThat(ref).hasToString("data-space:catalogo");
	}

	@Test
	void idMayContainColons() {
		var ref = DatasetRef.fromKey("ocds:contracting-process:ocds-1xraxc-8136");

		assertThat(ref.source()).isEqualTo("ocds");
		assertThat(ref.id()).isEqualTo("contracting-process:ocds-1xraxc-8136");
	}

	@Test
	void rejectsBlankOrMalformedValues() {
		assertThatIllegalArgumentException().isThrownBy(() -> DatasetRef.of(" ", "x"));
		assertThatIllegalArgumentException().isThrownBy(() -> DatasetRef.of("sede", ""));
		assertThatIllegalArgumentException().isThrownBy(() -> DatasetRef.of("a:b", "x"));
		assertThatIllegalArgumentException().isThrownBy(() -> DatasetRef.fromKey("sin-separador"));
		assertThatIllegalArgumentException().isThrownBy(() -> DatasetRef.fromKey(":id"));
	}

}
