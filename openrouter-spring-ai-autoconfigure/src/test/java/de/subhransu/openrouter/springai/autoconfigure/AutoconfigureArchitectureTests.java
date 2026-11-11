package de.subhransu.openrouter.springai.autoconfigure;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.ASSERTIONS_SHOULD_HAVE_DETAIL_MESSAGE;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * Architecture rules over the production classes of this module. ArchUnit caches an
 * import per location set, so every rule class in this module should declare exactly this
 * {@code @AnalyzeClasses} location set and reuse the one import instead of paying for
 * another. {@code ArchitectureRuleTests} proves each rule against fixture classes.
 */
@AnalyzeClasses(packages = "de.subhransu.openrouter.springai.autoconfigure",
		importOptions = ImportOption.DoNotIncludeTests.class)
class AutoconfigureArchitectureTests {

	private AutoconfigureArchitectureTests() {
	}

	@ArchTest
	static void the_import_holds_production_classes_only(JavaClasses classes) {
		assertThat(classes).extracting(JavaClass::getName)
			.contains(OpenRouterApiAutoConfiguration.class.getName())
			.doesNotContain(AutoconfigureArchitectureTests.class.getName());
	}

	@ArchTest
	static final ArchRule java_assertions_have_messages = ASSERTIONS_SHOULD_HAVE_DETAIL_MESSAGE
		.because("production Java assertions and AssertionError need diagnostic detail, not JUnit or AssertJ messages");

	@ArchTest
	static final ArchRule auto_configuration_must_not_depend_on_wire_dtos = noClasses().that()
		.resideInAPackage("..autoconfigure..")
		.should()
		.dependOnClassesThat()
		.resideInAPackage("..api.dto..")
		.because("auto-configuration wires models and must not handle wire DTOs");

	@ArchTest
	static final ArchRule auto_configuration_must_not_depend_on_mappers = noClasses().that()
		.resideInAPackage("..autoconfigure..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("..chat.mapper..", "..embedding.mapper..", "..image.mapper..")
		.because("auto-configuration wires models and must not translate wire requests or responses");

	@ArchTest
	static final ArchRule auto_configurations_are_final = classes().that()
		.areAnnotatedWith(AutoConfiguration.class)
		.should()
		.haveModifier(JavaModifier.FINAL)
		.because("Spring Boot 4 enforces that auto-configurations are not API, and proxyBeanMethods is always "
				+ "false, so nothing needs to subclass them");

	@ArchTest
	static final ArchRule auto_configurations_expose_no_public_methods = methods().that()
		.areDeclaredInClassesThat()
		.areAnnotatedWith(AutoConfiguration.class)
		.should()
		.notBePublic()
		.because("Spring Boot 4 removed public members from auto-configuration classes; users replace beans, "
				+ "they do not call bean methods");

}
