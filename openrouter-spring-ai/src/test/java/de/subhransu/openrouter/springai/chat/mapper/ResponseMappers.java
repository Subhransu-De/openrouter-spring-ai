package de.subhransu.openrouter.springai.chat.mapper;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.errors.OpenRouterDiagnosticSanitizer;
import de.subhransu.openrouter.springai.image.mapper.OpenRouterImageResponseMapper;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

/**
 * Builds the response mappers with their production defaults for the mapper tests, so a
 * test names the sanitizer and limits it relies on instead of a convenience constructor.
 */
public final class ResponseMappers {

	private ResponseMappers() {
	}

	public static OpenRouterChatResponseMapper chat() {
		return new OpenRouterChatResponseMapper(OpenRouterDiagnosticSanitizer.keyless());
	}

	public static OpenRouterResponsesResponseMapper responses() {
		return new OpenRouterResponsesResponseMapper(OpenRouterDiagnosticSanitizer.keyless());
	}

	public static OpenRouterResponsesStreamingResponseMapper responsesStreaming() {
		return new OpenRouterResponsesStreamingResponseMapper(new ObjectMapper(),
				OpenRouterStreamingToolCallAggregator.DEFAULT_MAX_BYTES,
				OpenRouterStreamingToolCallAggregator.DEFAULT_MAX_CHUNKS,
				OpenRouterStreamingToolCallAggregator.DEFAULT_MAX_DURATION, OpenRouterDiagnosticSanitizer.keyless());
	}

	public static Flux<ChatResponse> chatStream(Flux<ChatCompletionChunk> chunks) {
		return chatStreaming().map(chunks, null);
	}

	public static OpenRouterStreamingResponseMapper chatStreaming() {
		return new OpenRouterStreamingResponseMapper(OpenRouterStreamingResponseMapper.DEFAULT_MAX_STATE_BYTES,
				OpenRouterStreamingResponseMapper.DEFAULT_MAX_STATE_CHOICES, OpenRouterDiagnosticSanitizer.keyless());
	}

	public static OpenRouterStreamingToolCallAggregator aggregator() {
		return new OpenRouterStreamingToolCallAggregator(new ObjectMapper(),
				OpenRouterStreamingToolCallAggregator.DEFAULT_MAX_BYTES,
				OpenRouterStreamingToolCallAggregator.DEFAULT_MAX_CHUNKS,
				OpenRouterStreamingToolCallAggregator.DEFAULT_MAX_DURATION);
	}

	public static OpenRouterImageResponseMapper image() {
		return new OpenRouterImageResponseMapper(OpenRouterDiagnosticSanitizer.keyless());
	}

	/** Maps one event as the first and only element of a fresh stream subscription. */
	public static ChatResponse responsesEvent(ResponsesStreamEvent event) {
		return responsesStreaming().map(Flux.just(event)).blockFirst();
	}

	/** Maps one chunk as the first and only element of a fresh stream subscription. */
	public static ChatResponse chunk(ChatCompletionChunk chunk) {
		return chatStream(Flux.just(chunk)).blockFirst();
	}

}
