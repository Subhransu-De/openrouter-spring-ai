package de.subhransu.openrouter.springai.autoconfigure;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.ASSERTIONS_SHOULD_HAVE_DETAIL_MESSAGE;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "de.subhransu.openrouter.springai.autoconfigure",
		importOptions = ImportOption.DoNotIncludeTests.class)
class AutoconfigureArchitectureTests {

	private AutoconfigureArchitectureTests() {
	}

	private static final String GUARD = OpenRouterToolCallingManagerGuard.class.getName();

	// The guard is a final, package-local collaborator of configuration. Its visibility
	// is not a consumer extension contract. BeanPostProcessor callbacks remain public;
	// properties and configuration beans are not utility classes.
	@ArchTest
	static final ArchRule guard_is_package_private = packagePrivateImplementation(GUARD);

	@ArchTest
	static final ArchRule guard_state_is_private = privateFields(GUARD);

	@ArchTest
	static final ArchRule java_assertions_have_messages = ASSERTIONS_SHOULD_HAVE_DETAIL_MESSAGE
		.because("production Java assertions and AssertionError need diagnostic detail, not JUnit or AssertJ messages");

	@ArchTest
	static void visibility_policy_targets_exist(JavaClasses imported) {
		assertThat(imported.get(GUARD).getFields()).extracting(field -> field.getName())
			.containsExactlyInAnyOrder("SPRING_TOOL_CALLING_AUTO_CONFIGURATION", "beanFactory",
					"singletonDeclaredProcessors", "transientProcessorQueue", "transientDeclaredProcessors");
	}

	static ArchRule packagePrivateImplementation(String name) {
		return classes().that()
			.haveFullyQualifiedName(name)
			.should()
			.bePackagePrivate()
			.because("selected wiring collaborators have only same-package callers");
	}

	static ArchRule privateFields(String name) {
		return fields().that()
			.areDeclaredIn(name)
			.should()
			.bePrivate()
			.because("guard bookkeeping must only change through its lifecycle operations");
	}

	@ArchTest
	static final ArchRule auto_configuration_must_not_depend_on_wire_dtos = noClasses().that()
		.resideInAPackage("..autoconfigure..")
		.should()
		.dependOnClassesThat()
		.resideInAPackage("..api.dto..");

	@ArchTest
	static final ArchRule auto_configuration_must_not_depend_on_mappers = noClasses().that()
		.resideInAPackage("..autoconfigure..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("..chat.mapper..", "..embedding.mapper..", "..image.mapper..")
		.because("auto-configuration wires models and must not translate wire requests or responses");

}
