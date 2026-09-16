package de.subhransu.openrouter.springai.autoconfigure;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

final class OpenRouterToolCallingRuntimeHints implements RuntimeHintsRegistrar {

	@Override
	public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
		SpringAiToolFailurePolicyAdapter.registerHints(hints);
	}

}
