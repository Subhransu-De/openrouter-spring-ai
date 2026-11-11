package de.subhransu.openrouter.springai;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import de.subhransu.openrouter.springai.api.ArchitectureApiFixtures;
import de.subhransu.openrouter.springai.api.dto.ArchitectureDtoFixtures;
import de.subhransu.openrouter.springai.chat.ArchitectureChatFixtures;
import de.subhransu.openrouter.springai.chat.mapper.ArchitectureMapperChatFixtures;
import de.subhransu.openrouter.springai.embedding.ArchitectureEmbeddingFixtures;
import de.subhransu.openrouter.springai.embedding.mapper.ArchitectureMapperEmbeddingFixtures;
import de.subhransu.openrouter.springai.image.ArchitectureImageFixtures;
import de.subhransu.openrouter.springai.image.mapper.ArchitectureMapperImageFixtures;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves that every rule in {@link CoreArchitectureTests} fails on a violating class and
 * passes on a compliant one, so a typo in a package pattern cannot make a rule vacuous.
 * The fixtures are imported once and each case evaluates a subset of them.
 */
class ArchitectureRuleTests {

	private static final JavaClasses FIXTURES = new ClassFileImporter().importClasses(fixtureTypes());

	private static final String UNKNOWN_FIELDS = "must declare @JsonIgnoreProperties(ignoreUnknown = true)";

	private static final String DTO = ArchitectureDtoFixtures.Tolerant.class.getName();

	static Stream<Arguments> rejectedFixtures() {
		return Stream.of(
				rejected("dto record with ignoreUnknown=false",
						CoreArchitectureTests.dto_records_must_tolerate_unknown_fields,
						ArchitectureDtoFixtures.Strict.class, UNKNOWN_FIELDS),
				rejected("dto record without annotation",
						CoreArchitectureTests.dto_records_must_tolerate_unknown_fields,
						ArchitectureDtoFixtures.Unannotated.class, UNKNOWN_FIELDS),
				rejected("bare assert", CoreArchitectureTests.java_assertions_have_messages, BareAssertion.class,
						"java.lang.AssertionError.<init>()"),
				rejected("bare AssertionError", CoreArchitectureTests.java_assertions_have_messages,
						BareAssertionError.class, "java.lang.AssertionError.<init>()"),
				rejected("api depends on chat", CoreArchitectureTests.api_must_not_depend_on_chat,
						ArchitectureApiFixtures.DependsOnChat.class, ArchitectureChatFixtures.DtoUser.class.getName()),
				rejected("api depends on Spring AI", CoreArchitectureTests.api_must_not_depend_on_spring_ai,
						ArchitectureApiFixtures.DependsOnSpringAi.class,
						"org.springframework.ai.chat.messages.UserMessage"),
				rejected("chat uses RestClient", CoreArchitectureTests.only_api_may_use_http_clients,
						ArchitectureChatFixtures.RestClientUser.class, "org.springframework.web.client.RestClient"),
				rejected("chat uses WebClient", CoreArchitectureTests.only_api_may_use_http_clients,
						ArchitectureChatFixtures.WebClientUser.class,
						"org.springframework.web.reactive.function.client.WebClient"),
				rejected("chat uses http codec", CoreArchitectureTests.only_api_may_use_http_clients,
						ArchitectureChatFixtures.CodecUser.class, "org.springframework.http.codec.ServerSentEvent"),
				rejected("chat class uses a wire DTO", CoreArchitectureTests.mappers_are_the_wire_to_spring_ai_seam,
						ArchitectureChatFixtures.DtoUser.class, DTO),
				rejected("embedding class uses a wire DTO",
						CoreArchitectureTests.mappers_are_the_wire_to_spring_ai_seam,
						ArchitectureEmbeddingFixtures.DtoUser.class, DTO),
				rejected("image class uses a wire DTO", CoreArchitectureTests.mappers_are_the_wire_to_spring_ai_seam,
						ArchitectureImageFixtures.DtoUser.class, DTO),
				rejected("chat options setter", CoreArchitectureTests.options_expose_no_setters_or_copy,
						ArchitectureChatFixtures.SetterOptions.class, "setModel"),
				rejected("chat options copy", CoreArchitectureTests.options_expose_no_setters_or_copy,
						ArchitectureChatFixtures.CopyOptions.class, "copy"),
				rejected("embedding options setter", CoreArchitectureTests.options_expose_no_setters_or_copy,
						ArchitectureEmbeddingFixtures.SetterOptions.class, "setModel"),
				rejected("image options setter", CoreArchitectureTests.options_expose_no_setters_or_copy,
						ArchitectureImageFixtures.SetterOptions.class, "setModel"),
				Arguments
					.of(new Case("api and chat form a cycle", CoreArchitectureTests.core_packages_must_not_form_cycles,
							new Class<?>[] { ArchitectureApiFixtures.DependsOnChat.class,
									ArchitectureChatFixtures.DtoUser.class, ArchitectureDtoFixtures.Tolerant.class },
							"Cycle detected")));
	}

	static Stream<Arguments> acceptedFixtures() {
		return Stream.of(
				accepted("tolerant dto record", CoreArchitectureTests.dto_records_must_tolerate_unknown_fields,
						ArchitectureDtoFixtures.Tolerant.class),
				accepted("assertions with detail", CoreArchitectureTests.java_assertions_have_messages,
						DetailedAssertions.class),
				accepted("clean api class", CoreArchitectureTests.api_must_not_depend_on_chat,
						ArchitectureApiFixtures.Clean.class, ArchitectureApiFixtures.HttpClients.class),
				accepted("clean api class", CoreArchitectureTests.api_must_not_depend_on_spring_ai,
						ArchitectureApiFixtures.Clean.class, ArchitectureApiFixtures.HttpClients.class),
				accepted("api owns the HTTP clients", CoreArchitectureTests.only_api_may_use_http_clients,
						ArchitectureApiFixtures.HttpClients.class, ArchitectureChatFixtures.SetterHolder.class),
				accepted("api may use wire DTOs", CoreArchitectureTests.mappers_are_the_wire_to_spring_ai_seam,
						ArchitectureApiFixtures.DtoUser.class, ArchitectureChatFixtures.SetterHolder.class),
				accepted("mapper packages may use wire DTOs",
						CoreArchitectureTests.mappers_are_the_wire_to_spring_ai_seam,
						ArchitectureMapperChatFixtures.DtoUser.class, ArchitectureMapperEmbeddingFixtures.DtoUser.class,
						ArchitectureMapperImageFixtures.DtoUser.class, ArchitectureChatFixtures.SetterHolder.class),
				accepted("model classes are the documented exception",
						CoreArchitectureTests.mappers_are_the_wire_to_spring_ai_seam,
						ArchitectureChatFixtures.OpenRouterChatModel.class,
						ArchitectureEmbeddingFixtures.OpenRouterEmbeddingModel.class,
						ArchitectureImageFixtures.OpenRouterImageModel.class,
						ArchitectureChatFixtures.SetterHolder.class),
				accepted("options derive through mutate", CoreArchitectureTests.options_expose_no_setters_or_copy,
						ArchitectureChatFixtures.MutateOptions.class, ArchitectureEmbeddingFixtures.MutateOptions.class,
						ArchitectureImageFixtures.MutateOptions.class),
				accepted("setters outside option classes or option packages",
						CoreArchitectureTests.options_expose_no_setters_or_copy,
						ArchitectureChatFixtures.MutateOptions.class, ArchitectureChatFixtures.SetterHolder.class,
						ArchitectureEmbeddingFixtures.SetterHolder.class, ArchitectureImageFixtures.SetterHolder.class,
						ArchitectureApiFixtures.SetterOptions.class),
				accepted("chat depends on api without a back edge",
						CoreArchitectureTests.core_packages_must_not_form_cycles,
						ArchitectureChatFixtures.DtoUser.class, ArchitectureDtoFixtures.Tolerant.class));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("rejectedFixtures")
	void ruleRejectsViolatingFixture(Case fixture) {
		var report = fixture.rule().evaluate(only(fixture.types())).getFailureReport();
		assertThat(report.getDetails()).isNotEmpty();
		assertThat(report.toString()).contains("because", fixture.types()[0].getName()).contains(fixture.details());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("acceptedFixtures")
	void rulePassesCompliantFixture(Case fixture) {
		fixture.rule().check(only(fixture.types()));
	}

	private static Arguments rejected(String name, ArchRule rule, Class<?> violator, String detail) {
		return Arguments.of(new Case(name, rule, new Class<?>[] { violator }, detail));
	}

	private static Arguments accepted(String name, ArchRule rule, Class<?>... fixtures) {
		return Arguments.of(new Case(name, rule, fixtures));
	}

	private static JavaClasses only(Class<?>[] types) {
		Set<String> names = Arrays.stream(types).map(Class::getName).collect(Collectors.toSet());
		return FIXTURES.that(DescribedPredicate.describe("selected fixtures", type -> names.contains(type.getName())));
	}

	private static Class<?>[] fixtureTypes() {
		return Stream
			.of(ArchitectureRuleTests.class, ArchitectureDtoFixtures.class, ArchitectureApiFixtures.class,
					ArchitectureChatFixtures.class, ArchitectureMapperChatFixtures.class,
					ArchitectureEmbeddingFixtures.class, ArchitectureMapperEmbeddingFixtures.class,
					ArchitectureImageFixtures.class, ArchitectureMapperImageFixtures.class)
			.flatMap(holder -> Arrays.stream(holder.getDeclaredClasses()))
			.toArray(Class<?>[]::new);
	}

	/**
	 * One rule case. The first type is the expected violator and every detail must appear
	 * in the failure report.
	 */
	private record Case(String name, ArchRule rule, Class<?>[] types, String... details) {

		@Override
		public String toString() {
			return this.name;
		}

	}

	static class DetailedAssertions {

		void check(int count) {
			assertThat(count).isPositive();
			assert count > 0 : "count must be positive";
			throw new AssertionError("unreachable state");
		}

	}

	static class BareAssertion {

		void check(int count) {
			assert count > 0;
		}

	}

	static class BareAssertionError {

		void check() {
			throw new AssertionError();
		}

	}

}
