package de.subhransu.openrouter.springai.support;

import java.math.BigDecimal;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Checks shared by the extension validators. Key messages follow the request-mode message
 * form: the mode, the option and its value, then what to do.
 *
 * @author Subhransu De
 */
final class ExtensionChecks {

	static final String CHAT_MODE = "CHAT_COMPLETIONS";

	private static final String RESPONSES_MODE = "RESPONSES";

	private ExtensionChecks() {
	}

	static String unsupportedRootKey(@Nullable String key, Set<String> accepted, boolean responses,
			Set<String> chatKeys) {
		String mode = responses ? RESPONSES_MODE : CHAT_MODE;
		// Set.of(...).contains(null) throws, so test the key first.
		boolean chatAccepts = responses && key != null && chatKeys.contains(key);
		String fix = chatAccepts ? "; remove it or use " + CHAT_MODE : "";
		return mode + " does not support extraBody key " + OptionChecks.describe(key) + fix + ". Accepted keys in "
				+ mode + ": " + sorted(accepted);
	}

	static String unsupportedProviderKey(@Nullable String key, Set<String> accepted) {
		return "providerExtraBody does not support key " + OptionChecks.describe(key) + "; accepted keys: "
				+ sorted(accepted);
	}

	// Boot binds numeric extension properties as strings.
	static BigDecimal number(String name, String text) {
		try {
			return new BigDecimal(text);
		}
		catch (NumberFormatException ex) {
			throw new IllegalArgumentException(name + " must be a number, but was " + OptionChecks.describe(text), ex);
		}
	}

	private static String sorted(Set<String> keys) {
		return String.join(", ", keys.stream().sorted().toList());
	}

}
