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
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.content.Media;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Flux;

public final class OpenRouterStreamingResponseMapper {

	public static final long DEFAULT_MAX_STATE_BYTES = 1024 * 1024;

	public static final int DEFAULT_MAX_STATE_CHOICES = 128;

	private final long maxStateBytes;

	private final int maxStateChoices;

	public OpenRouterStreamingResponseMapper() {
		this(DEFAULT_MAX_STATE_BYTES, DEFAULT_MAX_STATE_CHOICES);
	}

	public OpenRouterStreamingResponseMapper(long maxStateBytes, int maxStateChoices) {
		org.springframework.util.Assert.isTrue(maxStateBytes > 0,
				"Maximum Chat Completions state size must be greater than zero");
		org.springframework.util.Assert.isTrue(maxStateChoices > 0,
				"Maximum Chat Completions active choices must be greater than zero");
		this.maxStateBytes = maxStateBytes;
		this.maxStateChoices = maxStateChoices;
	}

	private final OpenRouterChoiceErrorExceptionFactory choiceErrorExceptionFactory = new OpenRouterChoiceErrorExceptionFactory();

	public ChatResponse map(ChatCompletionChunk chunk) {
		return map(chunk, new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(),
				new AudioOutputMapper(null), new ChatStreamBudget(this.maxStateBytes, this.maxStateChoices));
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
			Map<String, @Nullable Object> extensions = new LinkedHashMap<>();
			AudioOutputMapper audio = new AudioOutputMapper(audioOptions);
			ChatStreamBudget budget = new ChatStreamBudget(this.maxStateBytes, this.maxStateChoices);
			return chunks.map(chunk -> map(chunk, partialOutputs, reasoning, extensions, audio, budget))
				.doOnComplete(audio::complete)
				.doFinally(signal -> audio.clear());
		});
	}

	private ChatResponse map(ChatCompletionChunk chunk, Map<Integer, PartialOutputAccumulator> partialOutputs,
			Map<Integer, ReasoningMetadata.Accumulator> reasoning, Map<String, @Nullable Object> extensions,
			AudioOutputMapper audio, ChatStreamBudget budget) {
		if (chunk.error() != null) {
			// Mid-stream failures arrive as a normal chunk with a top-level error object
			// over HTTP 200; without this the truncated stream would look like a clean
			// completion.
			throw OpenRouterApiExceptionFactory.create("OpenRouter chat completion stream failed",
					chunk.error().toString(), chunk.error(), null);
		}
		throwIfChoiceFailed(chunk, partialOutputs);
		for (Choice choice : ResponseValues.items(chunk.choices(), "choice")) {
			if (!audio.finished(choice)) {
				budget.open(choiceIndex(choice));
			}
		}
		budget.appendResponse(chunk.extensions());
		accumulatePartialOutput(chunk, partialOutputs, audio);
		List<Generation> generations = CollectionUtils.isEmpty(chunk.choices()) ? List.of()
				: ResponseValues.items(chunk.choices(), "choice")
					.stream()
					.filter(choice -> !audio.finished(choice))
					.map(choice -> mapGeneration(choice, chunk.model(), audio, budget,
							reasoning.computeIfAbsent(choiceIndex(choice), key -> new ReasoningMetadata.Accumulator())))
					.toList();
		clearFinishedChoices(chunk, partialOutputs);
		if (chunk.choices() != null) {
			ResponseValues.items(chunk.choices(), "choice")
				.stream()
				.filter(choice -> choice.finishReason() != null)
				.forEach(choice -> {
					reasoning.remove(choiceIndex(choice));
					budget.finish(choiceIndex(choice));
				});
		}
		extensions.putAll(chunk.extensions());
		return new ChatResponse(generations, mapMetadata(chunk, extensions));
	}

	private void accumulatePartialOutput(ChatCompletionChunk chunk,
			Map<Integer, PartialOutputAccumulator> partialOutputs, AudioOutputMapper audio) {
		if (CollectionUtils.isEmpty(chunk.choices())) {
			return;
		}
		for (Choice choice : chunk.choices()) {
			if (choice != null && !audio.finished(choice) && choice.delta() != null
					&& choice.delta().content() != null) {
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
				if (choice.delta() != null && choice.delta().content() != null) {
					if (partialOutput == null) {
						partialOutput = new PartialOutputAccumulator();
					}
					partialOutput.append(choice.delta().content());
				}
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

	private Generation mapGeneration(Choice choice, @Nullable String model, AudioOutputMapper audio,
			ChatStreamBudget budget, ReasoningMetadata.Accumulator reasoning) {
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
		budget.append(choiceIndex(choice), properties);
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
		ChatGenerationMetadata.Builder metadataBuilder = ChatGenerationMetadata.builder();
		metadataBuilder.finishReason(FinishReasonMapper.map(choice.finishReason()));
		ResponseValues.ifPresent(model, value -> metadataBuilder.metadata("openrouter.model", value));
		ResponseValues.ifPresent(choice.index(), value -> metadataBuilder.metadata("openrouter.choice_index", value));
		ResponseValues.ifPresent(choice.nativeFinishReason(),
				value -> metadataBuilder.metadata("openrouter.native_finish_reason", value));
		ResponseValues.ifPresent(snapshot.get(RefusalMetadata.REFUSAL),
				value -> metadataBuilder.metadata(RefusalMetadata.REFUSAL, value));
		ResponseValues.ifPresent(choice.delta() != null ? choice.delta().reasoning() : null,
				value -> metadataBuilder.metadata("openrouter.reasoning", value));
		ChatGenerationMetadata metadata = metadataBuilder.build();
		return new Generation(assistantMessage, metadata);
	}

	private List<AssistantMessage.ToolCall> mapToolCalls(@Nullable List<? extends @Nullable ToolCall> toolCalls) {
		if (toolCalls == null || toolCalls.isEmpty()) {
			return List.of();
		}
		return ResponseValues.items(toolCalls, "tool call")
			.stream()
			.map(toolCall -> new AssistantMessage.ToolCall(toolCall.id() != null ? toolCall.id() : "",
					ResponseValues.required(toolCall.type(), "tool call type"),
					ResponseValues.required(ResponseValues.required(toolCall.function(), "tool call function").name(),
							"tool call name"),
					ResponseValues.required(toolCall.function().arguments(), "tool call arguments")))
			.toList();
	}

	private ChatResponseMetadata mapMetadata(ChatCompletionChunk chunk, Map<String, @Nullable Object> extensions) {
		ChatResponseMetadata.Builder metadataBuilder = ChatResponseMetadata.builder();
		ResponseValues.ifPresent(chunk.id(), metadataBuilder::id);
		ResponseValues.ifPresent(chunk.model(), metadataBuilder::model);
		ResponseValues.ifPresent(UsageMapper.map(chunk.usage()), metadataBuilder::usage);
		metadataBuilder.keyValue(ExtensionMetadata.RESPONSE, OptionSnapshots.map(extensions));
		metadataBuilder.keyValue("openrouter.provider", chunk.provider());
		metadataBuilder.keyValue("openrouter.object", chunk.object());
		metadataBuilder.keyValue("openrouter.created", chunk.created());
		return metadataBuilder.build();
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

		@Nullable String diagnosticValue() {
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
