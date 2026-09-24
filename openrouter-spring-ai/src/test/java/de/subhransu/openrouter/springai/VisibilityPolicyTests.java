package de.subhransu.openrouter.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import de.subhransu.openrouter.springai.api.dto.ArchitectureDtoFixtures;
import org.junit.jupiter.api.Test;

class VisibilityPolicyTests {

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
	void packageVisibilityRejectsPublicImplementations() {
		CoreArchitectureTests.packagePrivateImplementation(Compliant.class.getName())
			.check(importClass(Compliant.class));
		violation(CoreArchitectureTests.packagePrivateImplementation(PublicImplementation.class.getName()),
				PublicImplementation.class, "has modifier PUBLIC");
	}

	@Test
	void privateStateAndHelpersRejectAccessibleMembers() {
		CoreArchitectureTests.privateFields(Compliant.class.getName()).check(importClass(Compliant.class));
		CoreArchitectureTests.privateHelpers(Compliant.class.getName()).check(importClass(Compliant.class));
		violation(CoreArchitectureTests.privateFields(AccessibleMembers.class.getName()), AccessibleMembers.class,
				"buffer", "does not have modifier PRIVATE");
		violation(CoreArchitectureTests.privateHelpers(AccessibleMembers.class.getName()), AccessibleMembers.class,
				"retain", "does not have modifier PRIVATE");
	}

	@Test
	void utilitiesRejectImplicitAndExplicitAccessibleConstructors() {
		CoreArchitectureTests.utilityConstructors(Compliant.class.getName()).check(importClass(Compliant.class));
		for (Class<?> type : new Class<?>[] { PublicImplementation.class, ImplicitUtility.class,
				AccessibleMembers.class }) {
			violation(CoreArchitectureTests.utilityConstructors(type.getName()), type, "constructor", "is not private");
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

	@Test
	void emptyPolicySelectionsFail() {
		String missing = "synthetic.MissingImplementation";
		for (ArchRule rule : new ArchRule[] { CoreArchitectureTests.packagePrivateImplementation(missing),
				CoreArchitectureTests.privateFields(missing), CoreArchitectureTests.privateHelpers(missing),
				CoreArchitectureTests.utilityConstructors(missing),
				CoreArchitectureTests.privateHelpers(ImplicitUtility.class.getName()) }) {
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

	// ArchUnit reads these declarations from bytecode; they are never executed.
	@SuppressWarnings({ "PMD.UnusedPrivateField", "PMD.UnusedPrivateMethod" })
	static class Compliant {

		private String buffer;

		private Compliant() {
		}

		private void retain() {
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

		void retain() {
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
