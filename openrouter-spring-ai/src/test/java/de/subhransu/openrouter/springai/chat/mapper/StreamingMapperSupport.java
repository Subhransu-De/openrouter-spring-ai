package de.subhransu.openrouter.springai.chat.mapper;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.errors.OpenRouterDiagnosticSanitizer;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

/**
 * Builds streaming mappers through the constructors and {@code map} overload that
 * production code calls, so tests do not depend on convenience members.
 */
final class StreamingMapperSupport {

	private StreamingMapperSupport() {
	}

	static OpenRouterStreamingResponseMapper chatMapper() {
		return chatMapper(OpenRouterStreamingResponseMapper.DEFAULT_MAX_STATE_BYTES,
				OpenRouterStreamingResponseMapper.DEFAULT_MAX_STATE_CHOICES);
	}

	static OpenRouterStreamingResponseMapper chatMapper(long maxBytes, int maxChoices) {
		return new OpenRouterStreamingResponseMapper(maxBytes, maxChoices, OpenRouterDiagnosticSanitizer.keyless());
	}

	static Flux<ChatResponse> map(OpenRouterStreamingResponseMapper mapper, Flux<ChatCompletionChunk> chunks) {
		return mapper.map(chunks, null);
	}

	/** Maps one chunk as the first and only element of a fresh stream subscription. */
	static ChatResponse mapFirst(OpenRouterStreamingResponseMapper mapper, ChatCompletionChunk chunk) {
		return map(mapper, Flux.just(chunk)).blockFirst();
	}

}
