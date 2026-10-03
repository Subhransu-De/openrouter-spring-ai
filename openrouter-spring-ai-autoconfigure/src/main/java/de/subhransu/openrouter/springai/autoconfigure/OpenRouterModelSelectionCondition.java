package de.subhransu.openrouter.springai.autoconfigure;

import de.subhransu.openrouter.springai.OpenRouterIdentifiers;
import org.springframework.ai.model.SpringAIModelProperties;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Matches when OpenRouter is selected for at least one supported model type.
 *
 * @author Subhransu De
 */
final class OpenRouterModelSelectionCondition extends AnyNestedCondition {

	OpenRouterModelSelectionCondition() {
		super(ConfigurationPhase.REGISTER_BEAN);
	}

	@ConditionalOnProperty(name = SpringAIModelProperties.CHAT_MODEL, havingValue = OpenRouterIdentifiers.PROVIDER_ID,
			matchIfMissing = true)
	private static final class ChatModelSelected {

	}

	@ConditionalOnProperty(name = SpringAIModelProperties.EMBEDDING_MODEL,
			havingValue = OpenRouterIdentifiers.PROVIDER_ID, matchIfMissing = true)
	private static final class EmbeddingModelSelected {

	}

	@ConditionalOnProperty(name = SpringAIModelProperties.IMAGE_MODEL, havingValue = OpenRouterIdentifiers.PROVIDER_ID,
			matchIfMissing = true)
	private static final class ImageModelSelected {

	}

}
