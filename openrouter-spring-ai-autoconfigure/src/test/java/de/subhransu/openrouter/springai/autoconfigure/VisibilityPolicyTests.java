package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

class VisibilityPolicyTests {

	@Test
	void wiringRejectsEveryMapperFamily() {
		for (Class<?> type : new Class<?>[] { ChatMapping.class, EmbeddingMapping.class, ImageMapping.class }) {
			var report = AutoconfigureArchitectureTests.auto_configuration_must_not_depend_on_mappers
				.evaluate(importClass(type))
				.getFailureReport();
			assertThat(report.getDetails()).hasSize(1);
			assertThat(report.toString()).contains(type.getName(), "mapper", "because");
		}
	}

	@Test
	void packageVisibilityRejectsPublicImplementations() {
		AutoconfigureArchitectureTests.packagePrivateImplementation(Compliant.class.getName())
			.check(importClass(Compliant.class));
		violation(AutoconfigureArchitectureTests.packagePrivateImplementation(PublicImplementation.class.getName()),
				PublicImplementation.class, "has modifier PUBLIC");
	}

	@Test
	void privateStateAndHelpersRejectAccessibleMembers() {
		AutoconfigureArchitectureTests.privateFields(Compliant.class.getName()).check(importClass(Compliant.class));
		AutoconfigureArchitectureTests.privateHelpers(Compliant.class.getName()).check(importClass(Compliant.class));
		violation(AutoconfigureArchitectureTests.privateFields(AccessibleMembers.class.getName()),
				AccessibleMembers.class, "buffer", "does not have modifier PRIVATE");
		violation(AutoconfigureArchitectureTests.privateHelpers(AccessibleMembers.class.getName()),
				AccessibleMembers.class, "registerField", "does not have modifier PRIVATE");
	}

	@Test
	void utilitiesRejectImplicitAndExplicitAccessibleConstructors() {
		AutoconfigureArchitectureTests.utilityConstructors(Compliant.class.getName())
			.check(importClass(Compliant.class));
		for (Class<?> type : new Class<?>[] { PublicImplementation.class, ImplicitUtility.class,
				AccessibleMembers.class }) {
			violation(AutoconfigureArchitectureTests.utilityConstructors(type.getName()), type, "constructor",
					"is not private");
		}
	}

	@Test
	void assertionsRequireJavaDiagnosticDetail() {
		AutoconfigureArchitectureTests.java_assertions_have_messages.check(importClass(DetailedAssertions.class));
		violation(AutoconfigureArchitectureTests.java_assertions_have_messages, BareAssertion.class,
				"java.lang.AssertionError.<init>()");
		violation(AutoconfigureArchitectureTests.java_assertions_have_messages, BareAssertionError.class,
				"java.lang.AssertionError.<init>()");
	}

	@Test
	void emptyPolicySelectionsFail() {
		String missing = "synthetic.MissingImplementation";
		for (ArchRule rule : new ArchRule[] { AutoconfigureArchitectureTests.packagePrivateImplementation(missing),
				AutoconfigureArchitectureTests.privateFields(missing),
				AutoconfigureArchitectureTests.privateHelpers(missing),
				AutoconfigureArchitectureTests.utilityConstructors(missing),
				AutoconfigureArchitectureTests.privateHelpers(ImplicitUtility.class.getName()) }) {
			assertThatThrownBy(() -> rule.check(importClass(ImplicitUtility.class))).isInstanceOf(AssertionError.class)
				.hasMessageContaining("failed to check any classes");
		}
	}

	private static JavaClasses importClass(Class<?> type) {
		return new ClassFileImporter().importClasses(type);
	}

	private static void violation(ArchRule rule, Class<?> type, String... details) {
		var report = rule.evaluate(importClass(type)).getFailureReport();
		assertThat(report.getDetails()).hasSize(1);
		assertThat(report.toString()).contains(type.getName(), "because").contains(details);
	}

	// ArchUnit reads the field type as a dependency.
	@SuppressWarnings("PMD.UnusedPrivateField")
	static class ChatMapping {

		private de.subhransu.openrouter.springai.chat.mapper.OpenRouterChatRequestMapper mapper;

	}

	// ArchUnit reads the field type as a dependency.
	@SuppressWarnings("PMD.UnusedPrivateField")
	static class EmbeddingMapping {

		private de.subhransu.openrouter.springai.embedding.mapper.OpenRouterEmbeddingRequestMapper mapper;

	}

	// ArchUnit reads the field type as a dependency.
	@SuppressWarnings("PMD.UnusedPrivateField")
	static class ImageMapping {

		private de.subhransu.openrouter.springai.image.mapper.OpenRouterImageRequestMapper mapper;

	}

	// ArchUnit reads these declarations from bytecode; they are never executed.
	@SuppressWarnings({ "PMD.UnusedPrivateField", "PMD.UnusedPrivateMethod" })
	static class Compliant {

		private String buffer;

		private Compliant() {
		}

		private void registerField() {
		}

	}

	public static class PublicImplementation {

	}

	static class ImplicitUtility {

	}

	static class AccessibleMembers {

		public String buffer;

		AccessibleMembers() {
		}

		void registerField() {
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
