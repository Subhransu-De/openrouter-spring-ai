package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

class ArchitectureRuleTests {

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
	void assertionsRequireJavaDiagnosticDetail() {
		AutoconfigureArchitectureTests.java_assertions_have_messages.check(importClass(DetailedAssertions.class));
		violation(AutoconfigureArchitectureTests.java_assertions_have_messages, BareAssertion.class,
				"java.lang.AssertionError.<init>()");
		violation(AutoconfigureArchitectureTests.java_assertions_have_messages, BareAssertionError.class,
				"java.lang.AssertionError.<init>()");
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
