package es.zaragoza.observatory;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * SPEC.md §4.4 y regla 4 de CLAUDE.md: hexagonal estricta dentro de cada módulo.
 * {@code allowEmptyShould(true)} mientras no existan clases de dominio; retirar cuando las haya.
 */
@AnalyzeClasses(packages = "es.zaragoza.observatory", importOptions = ImportOption.DoNotIncludeTests.class)
class HexagonalArchitectureTests {

	@ArchTest
	static final ArchRule domainIsFrameworkFree = noClasses()
			.that().resideInAPackage("..domain..")
			.should().dependOnClassesThat().resideInAnyPackage(
					"org.springframework..", "jakarta.persistence..", "org.hibernate..",
					"..application..", "..infrastructure..")
			.allowEmptyShould(true)
			.because("el paquete domain no importa Spring, JPA ni nada de infrastructure (SPEC.md regla 4)");

	@ArchTest
	static final ArchRule applicationDoesNotDependOnInfrastructure = noClasses()
			.that().resideInAPackage("..application..")
			.should().dependOnClassesThat().resideInAPackage("..infrastructure..")
			.allowEmptyShould(true)
			.because("los casos de uso dependen de puertos del dominio, no de adaptadores");

}
