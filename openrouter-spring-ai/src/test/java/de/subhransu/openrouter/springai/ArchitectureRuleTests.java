package de.subhransu.openrouter.springai;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import de.subhransu.openrouter.springai.api.dto.ArchitectureDtoFixtures;
import org.junit.jupiter.api.Test;

class ArchitectureRuleTests {

	@Test
	void wireRecordsRequireTrueIgnoreUnknownValue() {
		CoreArchitectureTests.dto_records_must_tolerate_unknown_fields
			.check(importClass(ArchitectureDtoFixtures.Tolerant.class));
		for (Class<?> type : new Class<?>[] { ArchitectureDtoFixtures.Strict.class,
				ArchitectureDtoFixtures.Unannotated.class }) {
			violation(CoreArchitectureTests.dto_records_must_tolerate_unknown_fields, type,
					"must declare @JsonIgnoreProperties(ignoreUnknown = true)");
		}
	}

	@Test
	void assertionsRequireJavaDiagnosticDetail() {
		CoreArchitectureTests.java_assertions_have_messages.check(importClass(DetailedAssertions.class));
		violation(CoreArchitectureTests.java_assertions_have_messages, BareAssertion.class,
				"java.lang.AssertionError.<init>()");
		violation(CoreArchitectureTests.java_assertions_have_messages, BareAssertionError.class,
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
