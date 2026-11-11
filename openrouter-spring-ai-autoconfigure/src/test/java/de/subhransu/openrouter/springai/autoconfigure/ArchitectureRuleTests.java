package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import de.subhransu.openrouter.springai.api.dto.Usage;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterChatRequestMapper;
import de.subhransu.openrouter.springai.embedding.mapper.OpenRouterEmbeddingRequestMapper;
import de.subhransu.openrouter.springai.image.mapper.OpenRouterImageRequestMapper;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * Proves that every rule in {@link AutoconfigureArchitectureTests} fails on a violating
 * class and passes on a compliant one, so a typo in a package pattern or annotation
 * cannot make a rule vacuous. The fixtures are imported once and each case evaluates a
 * subset of them. The Java-assertion rule is ArchUnit's own constant; the fixtures here
 * prove that this module applies it, and {@code AutoconfigureArchitectureTests} proves
 * that its import holds production classes.
 */
class ArchitectureRuleTests {

	private static final JavaClasses FIXTURES = new ClassFileImporter()
		.importClasses(ArchitectureRuleTests.class.getDeclaredClasses());

	static Stream<Arguments> rejectedFixtures() {
		return Stream.of(
				rejected("bare assert", AutoconfigureArchitectureTests.java_assertions_have_messages,
						BareAssertion.class, "java.lang.AssertionError.<init>()"),
				rejected("bare AssertionError", AutoconfigureArchitectureTests.java_assertions_have_messages,
						BareAssertionError.class, "java.lang.AssertionError.<init>()"),
				rejected("wiring depends on a wire DTO",
						AutoconfigureArchitectureTests.auto_configuration_must_not_depend_on_wire_dtos, DtoWiring.class,
						Usage.class.getName()),
				rejected("wiring depends on a chat mapper",
						AutoconfigureArchitectureTests.auto_configuration_must_not_depend_on_mappers, ChatMapping.class,
						OpenRouterChatRequestMapper.class.getName()),
				rejected("wiring depends on an embedding mapper",
						AutoconfigureArchitectureTests.auto_configuration_must_not_depend_on_mappers,
						EmbeddingMapping.class, OpenRouterEmbeddingRequestMapper.class.getName()),
				rejected("wiring depends on an image mapper",
						AutoconfigureArchitectureTests.auto_configuration_must_not_depend_on_mappers,
						ImageMapping.class, OpenRouterImageRequestMapper.class.getName()),
				rejected("auto-configuration is not final",
						AutoconfigureArchitectureTests.auto_configurations_are_final, OpenConfiguration.class,
						"does not have modifier FINAL"),
				rejected("auto-configuration has a public method",
						AutoconfigureArchitectureTests.auto_configurations_expose_no_public_methods,
						PublicMethodConfiguration.class, "publicBean"));
	}

	static Stream<Arguments> acceptedFixtures() {
		return Stream.of(
				accepted("assertions with detail", AutoconfigureArchitectureTests.java_assertions_have_messages,
						DetailedAssertions.class),
				accepted("wiring without wire DTOs",
						AutoconfigureArchitectureTests.auto_configuration_must_not_depend_on_wire_dtos,
						CompliantWiring.class),
				accepted("wiring without mappers",
						AutoconfigureArchitectureTests.auto_configuration_must_not_depend_on_mappers,
						CompliantWiring.class),
				accepted("final auto-configuration", AutoconfigureArchitectureTests.auto_configurations_are_final,
						FinalConfiguration.class),
				accepted("package-private auto-configuration methods",
						AutoconfigureArchitectureTests.auto_configurations_expose_no_public_methods,
						FinalConfiguration.class, PlainPublicMethods.class));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("rejectedFixtures")
	void ruleRejectsViolatingFixture(Case fixture) {
		var report = fixture.rule().evaluate(only(fixture.types())).getFailureReport();
		assertThat(report.getDetails()).hasSize(1);
		assertThat(report.toString()).contains("because", fixture.types()[0].getName()).contains(fixture.detail());
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
		return Arguments.of(new Case(name, rule, fixtures, null));
	}

	private static JavaClasses only(Class<?>[] types) {
		Set<String> names = Arrays.stream(types).map(Class::getName).collect(Collectors.toSet());
		return FIXTURES.that(DescribedPredicate.describe("selected fixtures", type -> names.contains(type.getName())));
	}

	/**
	 * One rule case. For a rejected case the single type is the violator and the detail
	 * must appear in the failure report.
	 */
	private record Case(String name, ArchRule rule, Class<?>[] types, String detail) {

		@Override
		public String toString() {
			return this.name;
		}

	}

	static class DetailedAssertions {

		void check(int count) {
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

		void fail() {
			throw new AssertionError();
		}

	}

	interface DtoWiring {

		Usage usage();

	}

	interface ChatMapping {

		OpenRouterChatRequestMapper mapper();

	}

	interface EmbeddingMapping {

		OpenRouterEmbeddingRequestMapper mapper();

	}

	interface ImageMapping {

		OpenRouterImageRequestMapper mapper();

	}

	interface CompliantWiring {

		String value();

	}

	@AutoConfiguration
	static class OpenConfiguration {

		void bean() {
		}

	}

	@AutoConfiguration
	public static final class PublicMethodConfiguration {

		public void publicBean() {
		}

	}

	@AutoConfiguration
	static final class FinalConfiguration {

		void bean() {
		}

	}

	/** Not an auto-configuration, so its public method is allowed. */
	public static class PlainPublicMethods {

		public void bean() {
		}

	}

}
