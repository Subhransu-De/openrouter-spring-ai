package de.subhransu.openrouter.springai.api;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.api.dto.ResponsesInputMessage;
import de.subhransu.openrouter.springai.api.dto.ResponsesFunctionCall;
import de.subhransu.openrouter.springai.api.dto.ResponsesFunctionCallOutput;
import de.subhransu.openrouter.springai.api.dto.EmbeddingsRequest;
import de.subhransu.openrouter.springai.api.dto.EmbeddingsResponse;
import de.subhransu.openrouter.springai.api.dto.ImagesRequest;
import de.subhransu.openrouter.springai.api.dto.ImagesResponse;
import de.subhransu.openrouter.springai.api.dto.ImagesStreamEvent;
import de.subhransu.openrouter.springai.api.dto.ContentPart;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Binding metadata for the provider's dynamically serialized wire DTOs.
 *
 * @author Subhransu De
 */
public final class OpenRouterSerializationRuntimeHints implements RuntimeHintsRegistrar {

	@Override
	public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
		// Binding follows typed record components. Object-valued input/content also
		// carries these explicitly listed provider DTOs at runtime.
		new BindingReflectionHintsRegistrar().registerReflectionHints(hints.reflection(), ChatCompletionRequest.class,
				ChatCompletionResponse.class, ChatCompletionChunk.class, ResponsesRequest.class, ResponsesResult.class,
				ResponsesStreamEvent.class, ResponsesInputMessage.class, ResponsesFunctionCall.class,
				ResponsesFunctionCallOutput.class, EmbeddingsRequest.class, EmbeddingsResponse.class,
				ImagesRequest.class, ImagesResponse.class, ImagesStreamEvent.class, ContentPart.class,
				OpenRouterErrorResponse.class);
	}

}
