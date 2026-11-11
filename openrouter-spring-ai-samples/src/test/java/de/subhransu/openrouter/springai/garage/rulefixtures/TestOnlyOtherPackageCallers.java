package de.subhransu.openrouter.springai.garage.rulefixtures;

import de.subhransu.openrouter.springai.garage.TestOnlyPublicFixtures;

/**
 * Test callers outside the package of the fixtures, for
 * {@code TestOnlyProductionCodeRuleTests}.
 */
public final class TestOnlyOtherPackageCallers {

	private TestOnlyOtherPackageCallers() {
	}

	public static int callPublic(TestOnlyPublicFixtures.PublicWidened widened) {
		return widened.used();
	}

	/** A subclass in another package reaches the protected method. */
	public static final class Subclass extends TestOnlyPublicFixtures.ProtectedBase {

		public int callProtected() {
			return used();
		}

	}

}
