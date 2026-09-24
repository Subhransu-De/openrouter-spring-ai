package de.subhransu.openrouter.springai;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.ASSERTIONS_SHOULD_HAVE_DETAIL_MESSAGE;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

@AnalyzeClasses(packages = "de.subhransu.openrouter.springai", importOptions = ImportOption.DoNotIncludeTests.class)
class CoreArchitectureTests {

	private CoreArchitectureTests() {
	}

	private static final String BUDGET = "de.subhransu.openrouter.springai.chat.mapper.ChatStreamBudget";

	private static final String VALUES = "de.subhransu.openrouter.springai.chat.mapper.ResponseValues";

	// These final helpers have only same-package mapper callers, no serialization,
	// reflective construction or subclass contract. Public mappers remain public for
	// model callers in other packages. Budget operations remain package-private;
	// only its accounting helpers and directly declared state must stay private.
	@ArchTest
	static final ArchRule budget_is_package_private = packagePrivateImplementation(BUDGET);

	@ArchTest
	static final ArchRule response_values_is_package_private = packagePrivateImplementation(VALUES);

	@ArchTest
	static final ArchRule budget_state_is_private = privateFields(BUDGET);

	@ArchTest
	static final ArchRule budget_helpers_are_private = privateHelpers(BUDGET);

	// ResponseValues is stateless and never constructed. Stateful budgets, DTOs,
	// model extension points and framework objects are deliberately not utilities.
	@ArchTest
	static final ArchRule response_values_is_non_instantiable = utilityConstructors(VALUES);

	@ArchTest
	static final ArchRule java_assertions_have_messages = ASSERTIONS_SHOULD_HAVE_DETAIL_MESSAGE
		.because("production Java assertions and AssertionError need diagnostic detail, not JUnit or AssertJ messages");

	@ArchTest
	static void visibility_policy_targets_exist(JavaClasses imported) {
		assertThat(imported.get(BUDGET).getFields()).extracting(field -> field.getName())
			.containsExactlyInAnyOrder("objectMapper", "maxBytes", "maxChoices", "choices", "bytes");
		assertThat(imported.get(BUDGET).getMethods())
			.filteredOn(method -> method.getName().matches("size|retain|limit"))
			.extracting(method -> method.getName())
			.containsExactlyInAnyOrder("size", "retain", "limit");
		assertThat(imported.get(VALUES).getConstructors()).hasSize(1);
	}

	static ArchRule packagePrivateImplementation(String name) {
		return classes().that()
			.haveFullyQualifiedName(name)
			.should()
			.bePackagePrivate()
			.because("selected implementation helpers have only same-package callers");
	}

	static ArchRule privateFields(String name) {
		return fields().that()
			.areDeclaredIn(name)
			.should()
			.bePrivate()
			.because("budget accounting state must only be mutated through its operations");
	}

	static ArchRule privateHelpers(String name) {
		return methods().that()
			.areDeclaredIn(name)
			.and()
			.haveNameMatching("size|retain|limit")
			.should()
			.bePrivate()
			.because("accounting helpers are internal; mapper callers use the package-private budget operations");
	}

	static ArchRule utilityConstructors(String name) {
		return classes().that()
			.haveFullyQualifiedName(name)
			.should()
			.haveOnlyPrivateConstructors()
			.because("selected stateless utilities must not acquire explicit or implicit accessible constructors");
	}

	@ArchTest
	static final ArchRule api_must_not_depend_on_chat = noClasses().that()
		.resideInAPackage("..api..")
		.should()
		.dependOnClassesThat()
		.resideInAPackage("..chat..");

	@ArchTest
	static final ArchRule api_must_not_depend_on_spring_ai = noClasses().that()
		.resideInAPackage("..api..")
		.should()
		.dependOnClassesThat()
		.resideInAPackage("org.springframework.ai..");

	@ArchTest
	static final ArchRule only_api_may_use_http_clients = noClasses().that()
		.resideOutsideOfPackage("..api..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("org.springframework.web.client..", "org.springframework.web.reactive.function.client..",
				"org.springframework.http.codec..");

	@ArchTest
	static final ArchRule mappers_are_the_wire_to_spring_ai_seam = noClasses()
		.that(not(resideInAPackage("..api..").or(resideInAPackage("..chat.mapper.."))
			.or(resideInAPackage("..embedding.mapper.."))
			.or(resideInAPackage("..image.mapper.."))))
		.and()
		.doNotHaveSimpleName("OpenRouterChatModel")
		.and()
		.doNotHaveSimpleName("OpenRouterEmbeddingModel")
		.and()
		.doNotHaveSimpleName("OpenRouterImageModel")
		.should()
		.dependOnClassesThat()
		.resideInAPackage("..api.dto..")
		.because("the mapper packages should be the seam where OpenRouter wire DTOs meet Spring AI types; "
				+ "the model classes are temporarily allowed while request dispatch still holds DTO variables");

	@ArchTest
	static final ArchRule dto_records_must_tolerate_unknown_fields = classes().that()
		.resideInAPackage("..api.dto..")
		.and()
		.areRecords()
		.should(tolerateUnknownJsonFields())
		.because("DD-01 requires OpenRouter wire DTOs to survive additive provider fields; "
				+ "non-record helpers such as custom deserializers carry no bound fields to protect");

	@ArchTest
	static final ArchRule core_packages_must_not_form_cycles = SlicesRuleDefinition.slices()
		.matching("de.subhransu.openrouter.springai.(*)..")
		.should()
		.beFreeOfCycles();

	private static ArchCondition<JavaClass> tolerateUnknownJsonFields() {
		return new ArchCondition<>("be annotated with @JsonIgnoreProperties(ignoreUnknown = true)") {

			@Override
			public void check(JavaClass item, ConditionEvents events) {
				JsonIgnoreProperties annotation = item.reflect().getAnnotation(JsonIgnoreProperties.class);
				boolean satisfied = annotation != null && annotation.ignoreUnknown();
				String message = item.getName() + " must declare @JsonIgnoreProperties(ignoreUnknown = true)";
				events.add(new SimpleConditionEvent(item, satisfied, message));
			}
		};
	}

}
