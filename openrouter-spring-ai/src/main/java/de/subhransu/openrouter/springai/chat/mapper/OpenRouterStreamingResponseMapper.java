package de.subhransu.openrouter.springai.chat.mapper;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.ToolCall;
import de.subhransu.openrouter.springai.api.errors.OpenRouterApiExceptionFactory;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import de.subhransu.openrouter.springai.support.OptionSnapshots;
import de.subhransu.openrouter.springai.chat.OpenRouterAudioOptions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.content.Media;
import org.springframework.util.CollectionUtils;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;

public final class OpenRouterStreamingResponseMapper {

	private final OpenRouterChoiceErrorExceptionFactory choiceErrorExceptionFactory = new OpenRouterChoiceErrorExceptionFactory();

	public ChatResponse map(ChatCompletionChunk chunk) {
		return map(chunk, new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(),
				new AudioOutputMapper(null));
	}

	/**
	 * Maps a complete stream with bounded, per-choice partial-output diagnostics. State
	 * is allocated per subscription so concurrent callers and repeat subscriptions remain
	 * isolated.
	 * @param chunks streamed OpenRouter chat-completion chunks
	 * @return mapped Spring AI responses
	 */
	public Flux<ChatResponse> map(Flux<ChatCompletionChunk> chunks) {
		return map(chunks, null);
	}

	public Flux<ChatResponse> map(Flux<ChatCompletionChunk> chunks, @Nullable OpenRouterAudioOptions audioOptions) {
		return Flux.defer(() -> {
			Map<Integer, PartialOutputAccumulator> partialOutputs = new LinkedHashMap<>();
			Map<Integer, ReasoningMetadata.Accumulator> reasoning = new LinkedHashMap<>();
			Map<String, Object> extensions = new LinkedHashMap<>();
			AudioOutputMapper audio = new AudioOutputMapper(audioOptions);
			return chunks.map(chunk -> map(chunk, partialOutputs, reasoning, extensions, audio))
				.doOnComplete(audio::complete)
				.doFinally(signal -> audio.clear());
		});
	}

	private ChatResponse map(ChatCompletionChunk chunk, Map<Integer, PartialOutputAccumulator> partialOutputs,
			Map<Integer, ReasoningMetadata.Accumulator> reasoning, Map<String, Object> extensions,
			AudioOutputMapper audio) {
		if (chunk.error() != null) {
			// Mid-stream failures arrive as a normal chunk with a top-level error object
			// over HTTP 200; without this the truncated stream would look like a clean
			// completion.
			throw OpenRouterApiExceptionFactory.create("OpenRouter chat completion stream failed",
					chunk.error().toString(), chunk.error(), null);
		}
		accumulatePartialOutput(chunk, partialOutputs);
		throwIfChoiceFailed(chunk, partialOutputs);
		List<Generation> generations = CollectionUtils.isEmpty(chunk.choices()) ? List.of()
				: chunk.choices()
					.stream()
					.filter(choice -> !audio.finished(choice))
					.map(choice -> mapGeneration(choice, chunk.model(), audio,
							reasoning.computeIfAbsent(choiceIndex(choice), key -> new ReasoningMetadata.Accumulator())))
					.toList();
		clearFinishedChoices(chunk, partialOutputs);
		if (chunk.choices() != null) {
			chunk.choices()
				.stream()
				.filter(choice -> choice.finishReason() != null)
				.forEach(choice -> reasoning.remove(choiceIndex(choice)));
		}
		extensions.putAll(chunk.extensions());
		return new ChatResponse(generations, mapMetadata(chunk, extensions));
	}

	private void accumulatePartialOutput(ChatCompletionChunk chunk,
			Map<Integer, PartialOutputAccumulator> partialOutputs) {
		if (CollectionUtils.isEmpty(chunk.choices())) {
			return;
		}
		for (Choice choice : chunk.choices()) {
			if (choice != null && choice.delta() != null && choice.delta().content() != null) {
				partialOutputs.computeIfAbsent(choiceIndex(choice), key -> new PartialOutputAccumulator())
					.append(choice.delta().content());
			}
		}
	}

	private void throwIfChoiceFailed(ChatCompletionChunk chunk, Map<Integer, PartialOutputAccumulator> partialOutputs) {
		if (CollectionUtils.isEmpty(chunk.choices())) {
			return;
		}
		for (Choice choice : chunk.choices()) {
			if (choice != null && OpenRouterChoiceErrorExceptionFactory.isFailure(choice)) {
				PartialOutputAccumulator partialOutput = partialOutputs.get(choiceIndex(choice));
				String diagnostic = partialOutput != null ? partialOutput.diagnosticValue() : null;
				throw diagnostic != null ? this.choiceErrorExceptionFactory.create(chunk, choice, diagnostic)
						: this.choiceErrorExceptionFactory.create(chunk, choice);
			}
		}
	}

	private void clearFinishedChoices(ChatCompletionChunk chunk,
			Map<Integer, PartialOutputAccumulator> partialOutputs) {
		if (CollectionUtils.isEmpty(chunk.choices())) {
			return;
		}
		for (Choice choice : chunk.choices()) {
			if (choice != null && choice.finishReason() != null) {
				partialOutputs.remove(choiceIndex(choice));
			}
		}
	}

	private int choiceIndex(Choice choice) {
		return choice.index() != null ? choice.index() : 0;
	}

	private Generation mapGeneration(Choice choice, String model, AudioOutputMapper audio,
			ReasoningMetadata.Accumulator reasoning) {
		if (choice.delta() != null && !CollectionUtils.isEmpty(choice.delta().toolCalls())
				&& !FinishReasonMapper.isToolCallCompletion(choice.finishReason())) {
			throw new OpenRouterTruncatedResponseException(
					"Tool call choice ended without a tool-call completion reason");
		}

		Map<String, Object> properties = ReasoningMetadata.chat(
				choice.delta() != null ? choice.delta().reasoning() : null,
				choice.delta() != null ? choice.delta().reasoningDetails() : null);
		RefusalMetadata.put(properties, choice.delta() != null ? choice.delta().refusal() : null);
		ExtensionMetadata.put(properties, choice.delta() != null ? choice.delta().extensions() : null,
				choice.extensions(), choice.delta() != null ? choice.delta().toolCalls() : null);
		List<Media> media = new ArrayList<>(
				GeneratedImageMapper.media(choice.delta() != null ? choice.delta().images() : null));
		audio.append(choice, properties, media);
		Map<String, Object> snapshot = reasoning.append(properties);
		AssistantMessage assistantMessage = AssistantMessage.builder()
			.content(choice.delta() != null && choice.delta().content() != null ? choice.delta().content() : "")
			.properties(snapshot)
			.toolCalls(mapToolCalls(choice.delta() != null ? choice.delta().toolCalls() : null))
			.media(media)
			.build();
		ChatGenerationMetadata metadata = ChatGenerationMetadata.builder()
			.finishReason(FinishReasonMapper.map(choice.finishReason()))
			.metadata("openrouter.model", model)
			.metadata("openrouter.choice_index", choice.index())
			.metadata("openrouter.native_finish_reason", choice.nativeFinishReason())
			.metadata(RefusalMetadata.REFUSAL, snapshot.get(RefusalMetadata.REFUSAL))
			.metadata("openrouter.reasoning", choice.delta() != null ? choice.delta().reasoning() : null)
			.build();
		return new Generation(assistantMessage, metadata);
	}

	private List<AssistantMessage.ToolCall> mapToolCalls(List<ToolCall> toolCalls) {
		if (toolCalls == null || toolCalls.isEmpty()) {
			return List.of();
		}
		return toolCalls.stream()
			.filter(toolCall -> toolCall != null)
			.map(toolCall -> new AssistantMessage.ToolCall(toolCall.id(), toolCall.type(),
					toolCall.function() != null ? toolCall.function().name() : null,
					toolCall.function() != null ? toolCall.function().arguments() : null))
			.toList();
	}

	private ChatResponseMetadata mapMetadata(ChatCompletionChunk chunk, Map<String, Object> extensions) {
		return ChatResponseMetadata.builder()
			.id(chunk.id())
			.model(chunk.model())
			.usage(UsageMapper.map(chunk.usage()))
			.keyValue(ExtensionMetadata.RESPONSE, OptionSnapshots.map(extensions))
			.keyValue("openrouter.provider", chunk.provider())
			.keyValue("openrouter.object", chunk.object())
			.keyValue("openrouter.created", chunk.created())
			.build();
	}

	private static final class PartialOutputAccumulator {

		private final StringBuilder value = new StringBuilder();

		private boolean pendingWhitespace;

		private boolean truncated;

		void append(String content) {
			for (int i = 0; i < content.length(); i++) {
				char character = content.charAt(i);
				if (isWhitespace(character)) {
					this.pendingWhitespace = this.value.length() > 0;
					continue;
				}
				if (this.pendingWhitespace) {
					appendNormalized(' ');
					this.pendingWhitespace = false;
				}
				appendNormalized(character);
			}
		}

		String diagnosticValue() {
			if (this.value.length() == 0) {
				return null;
			}
			// One extra character tells the shared factory that content exceeded its
			// bound; it is replaced by the factory's ellipsis and never exposed.
			return this.truncated ? this.value.toString() + 'x' : this.value.toString();
		}

		private void appendNormalized(char character) {
			if (this.value.length() < OpenRouterChoiceErrorExceptionFactory.MAX_DIAGNOSTIC_LENGTH) {
				this.value.append(character);
			}
			else {
				this.truncated = true;
			}
		}

		private static boolean isWhitespace(char character) {
			return character == ' ' || character == '\t' || character == '\n' || character == '\u000B'
					|| character == '\f' || character == '\r';
		}

	}

}
