package de.subhransu.openrouter.springai.chat;

import org.springframework.ai.chat.messages.part.StreamingParts;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

/**
 * Removes the chunks that only stream tool-call arguments, for tests about the merged
 * chunks around them.
 */
final class ToolCallDeltas {

	private ToolCallDeltas() {
	}

	static Flux<ChatResponse> skip(Flux<ChatResponse> responses) {
		return responses.filter(response -> !isDelta(response));
	}

	static boolean isDelta(ChatResponse response) {
		return !response.getResults().isEmpty() && response.getResults()
			.stream()
			.allMatch(generation -> generation.getOutput()
				.getParts()
				.stream()
				.allMatch(part -> part instanceof ToolCallPart && StreamingParts.isPartial(part)));
	}

}
