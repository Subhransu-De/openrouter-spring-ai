package de.subhransu.openrouter.springai.autoconfigure;

import de.subhransu.openrouter.springai.OpenRouterIdentifiers;
import org.springframework.ai.model.SpringAIModelProperties;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Matches when OpenRouter is selected for at least one supported model type. Chat,
 * embeddings and images count when their selector is unset; the opt-in multimodal
 * embedding, audio and decision models count only when their selector names OpenRouter.
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

	// The multimodal embedding, audio and decision models are opt-in, so only a selector
	// that
	// names OpenRouter counts.
	@ConditionalOnProperty(name = SpringAIModelProperties.MULTI_MODAL_EMBEDDING_MODEL,
			havingValue = OpenRouterIdentifiers.PROVIDER_ID)
	private static final class MultimodalEmbeddingModelSelected {

	}

	@ConditionalOnProperty(name = SpringAIModelProperties.AUDIO_SPEECH_MODEL,
			havingValue = OpenRouterIdentifiers.PROVIDER_ID)
	private static final class SpeechModelSelected {

	}

	@ConditionalOnProperty(name = SpringAIModelProperties.AUDIO_TRANSCRIPTION_MODEL,
			havingValue = OpenRouterIdentifiers.PROVIDER_ID)
	private static final class TranscriptionModelSelected {

	}

	@ConditionalOnProperty(name = SpringAIModelProperties.DECISION_MODEL,
			havingValue = OpenRouterIdentifiers.PROVIDER_ID)
	private static final class DecisionModelSelected {

	}

}
