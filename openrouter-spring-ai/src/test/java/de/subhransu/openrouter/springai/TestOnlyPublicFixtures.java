package de.subhransu.openrouter.springai;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * Production-side fixtures with public and protected access for
 * {@code TestOnlyProductionCodeRuleTests}. The tests reach them from this package and
 * from the {@code rulefixtures} package.
 */
public final class TestOnlyPublicFixtures {

	private TestOnlyPublicFixtures() {
	}

	/**
	 * Production calls the method from this package; a test in another package needs
	 * public.
	 */
	public static class PublicWidened {

		public int used() {
			return 1;
		}

	}

	/**
	 * Production calls the method from this package; a subclass in another package needs
	 * protected.
	 */
	public static class ProtectedBase {

		// A framework creates the base; the constructor is not what the test exercises.
		@Autowired
		public ProtectedBase() {
		}

		protected int used() {
			return 1;
		}

	}

	/**
	 * The member is protected, but the class is package-private, so callers get package
	 * access. Production needs only private access.
	 */
	static class Hidden {

		protected int used() {
			return 1;
		}

		int callsUsed() {
			return used();
		}

	}

	/** Stands for a supported type: it has callers outside this repository. */
	public static class Api {

		public int publicOnlyTests() {
			return 1;
		}

		protected int protectedOnlyTests() {
			return 1;
		}

		int packageOnlyTests() {
			return 1;
		}

	}

}
