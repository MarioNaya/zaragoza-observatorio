package es.zaragoza.observatory;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * SPEC.md §4.4 y regla 4 de CLAUDE.md: hexagonal estricta dentro de cada módulo.
 */
@AnalyzeClasses(packages = "es.zaragoza.observatory", importOptions = ImportOption.DoNotIncludeTests.class)
class HexagonalArchitectureTests {

	@ArchTest
	static final ArchRule domainIsFrameworkFree = noClasses()
			.that().resideInAPackage("..domain..")
			.should().dependOnClassesThat().resideInAnyPackage(
					"org.springframework..", "jakarta.persistence..", "org.hibernate..", "tools.jackson..",
					"io.github.resilience4j..", "..application..", "..infrastructure..")
			.because("el paquete domain no importa Spring, JPA ni nada de infrastructure (SPEC.md regla 4)");

	@ArchTest
	static final ArchRule applicationDoesNotDependOnInfrastructure = noClasses()
			.that().resideInAPackage("..application..")
			.should().dependOnClassesThat().resideInAnyPackage("..infrastructure..", "jakarta.persistence..",
					"org.hibernate..", "org.springframework.web..", "org.springframework.data..")
			.because("los casos de uso dependen de puertos del dominio, no de adaptadores");

	@ArchTest
	static final ArchRule moduleApiDoesNotDependOnInternals = noClasses()
			.that().resideInAPackage("es.zaragoza.observatory.(*)")
			.should().dependOnClassesThat().resideInAnyPackage("..application..", "..infrastructure..")
			.because("la superficie pública de un módulo (su paquete raíz) no expone adaptadores ni casos de uso");

}
