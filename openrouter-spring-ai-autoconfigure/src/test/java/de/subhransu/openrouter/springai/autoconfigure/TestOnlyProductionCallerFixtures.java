package de.subhransu.openrouter.springai.autoconfigure;

/**
 * Production callers for {@code TestOnlyProductionCodeRuleTests}.
 */
final class TestOnlyProductionCallerFixtures {

	private TestOnlyProductionCallerFixtures() {
	}

	static int callShared(TestOnlyProductionFixtures.Compliant compliant) {
		return compliant.shared();
	}

	static int callPublic(TestOnlyPublicFixtures.PublicWidened widened) {
		return widened.used();
	}

	static int callProtected(TestOnlyPublicFixtures.ProtectedBase base) {
		return new TestOnlyPublicFixtures.ProtectedBase().used() + base.used();
	}

}
