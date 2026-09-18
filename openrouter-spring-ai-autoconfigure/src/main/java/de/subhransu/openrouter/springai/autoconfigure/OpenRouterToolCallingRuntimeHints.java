package de.subhransu.openrouter.springai.autoconfigure;

import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

final class OpenRouterToolCallingRuntimeHints implements RuntimeHintsRegistrar {

	@Override
	public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
		SpringAiToolFailurePolicyAdapter.registerHints(hints);
	}

}
