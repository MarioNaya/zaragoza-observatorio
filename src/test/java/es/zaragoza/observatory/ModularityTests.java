package es.zaragoza.observatory;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * SPEC.md §6 y regla 3 de CLAUDE.md: cada cambio debe pasar {@code ApplicationModules.verify()}.
 * La documentación generada (C4 + canvas por módulo) se escribe en target/spring-modulith-docs.
 */
class ModularityTests {

	static final ApplicationModules modules = ApplicationModules.of(ObservatorioZaragozaApplication.class);

	@Test
	void modulesRespectDeclaredDependencies() {
		modules.forEach(System.out::println);
		modules.verify();
	}

	@Test
	void writesModuleDocumentation() {
		new Documenter(modules).writeDocumentation();
	}

}
