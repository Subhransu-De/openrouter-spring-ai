package de.subhransu.openrouter.springai.chat.mapper;

import de.subhransu.openrouter.springai.chat.OpenRouterMetadata;
import java.util.Objects;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.Delta;
import de.subhransu.openrouter.springai.api.dto.FunctionCall;
import de.subhransu.openrouter.springai.api.dto.ToolCall;
import de.subhransu.openrouter.springai.api.errors.OpenRouterInBandErrorExceptionFactory;
import de.subhransu.openrouter.springai.errors.OpenRouterDiagnosticSanitizer;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import de.subhransu.openrouter.springai.chat.OpenRouterAudioOptions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.part.MediaPart;
import org.springframework.ai.chat.messages.part.MessagePart;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.StreamingParts;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.content.Media;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import reactor.core.publisher.Flux;

public final class OpenRouterStreamingResponseMapper {

	public static final long DEFAULT_MAX_STATE_BYTES = 1024 * 1024;

	public static final int DEFAULT_MAX_STATE_CHOICES = 128;

	private final long maxStateBytes;

	private final int maxStateChoices;

	private final OpenRouterDiagnosticSanitizer sanitizer;

	private final OpenRouterChoiceErrorExceptionFactory choiceErrorExceptionFactory;

	public OpenRouterStreamingResponseMapper() {
		this(DEFAULT_MAX_STATE_BYTES, DEFAULT_MAX_STATE_CHOICES);
	}

	public OpenRouterStreamingResponseMapper(long maxStateBytes, int maxStateChoices) {
		this(maxStateBytes, maxStateChoices, OpenRouterDiagnosticSanitizer.keyless());
	}

	public OpenRouterStreamingResponseMapper(long maxStateBytes, int maxStateChoices,
			OpenRouterDiagnosticSanitizer sanitizer) {
		org.springframework.util.Assert.isTrue(maxStateBytes > 0,
				"Maximum Chat Completions state size must be greater than zero");
		org.springframework.util.Assert.isTrue(maxStateChoices > 0,
				"Maximum Chat Completions active choices must be greater than zero");
		this.maxStateBytes = maxStateBytes;
		this.maxStateChoices = maxStateChoices;
		this.sanitizer = sanitizer;
		this.choiceErrorExceptionFactory = new OpenRouterChoiceErrorExceptionFactory(sanitizer);
	}

	public ChatResponse map(ChatCompletionChunk chunk) {
		return map(chunk, new LinkedHashMap<>(), new LinkedHashMap<>(), new ResponseExtensions(),
				new AudioOutputMapper(null), new ChatStreamBudget(this.maxStateBytes, this.maxStateChoices),
				new Parts());
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
		return mapOutputs(chunks.map(chunk -> new OpenRouterStreamingToolCallAggregator.Output(chunk, false)),
				audioOptions);
	}

	/**
	 * Maps a stream through the tool-call aggregator, streaming the tool-call arguments
	 * as partial {@code ToolCallPart}s while the aggregator buffers them. Each complete
	 * call follows once, at the index of its deltas.
	 * @param chunks streamed OpenRouter chat-completion chunks
	 * @param aggregator the aggregator that merges tool-call fragments
	 * @param audioOptions the requested audio output, or {@code null}
	 * @return mapped Spring AI responses
	 */
	public Flux<ChatResponse> map(Flux<ChatCompletionChunk> chunks, OpenRouterStreamingToolCallAggregator aggregator,
			@Nullable OpenRouterAudioOptions audioOptions) {
		return mapOutputs(aggregator.aggregate(chunks, true), audioOptions);
	}

	private Flux<ChatResponse> mapOutputs(Flux<OpenRouterStreamingToolCallAggregator.Output> outputs,
			@Nullable OpenRouterAudioOptions audioOptions) {
		return Flux.defer(() -> {
			Map<Integer, PartialOutputAccumulator> partialOutputs = new LinkedHashMap<>();
			Map<Integer, ReasoningMetadata.Accumulator> reasoning = new LinkedHashMap<>();
			ResponseExtensions extensions = new ResponseExtensions();
			AudioOutputMapper audio = new AudioOutputMapper(audioOptions);
			ChatStreamBudget budget = new ChatStreamBudget(this.maxStateBytes, this.maxStateChoices);
			Parts parts = new Parts();
			AtomicReference<ChatCompletionChunk> last = new AtomicReference<>();
			return outputs
				.mapNotNull(output -> output.toolCallDelta() ? parts.toolCallDeltas(output.chunk())
						: mapChunk(output.chunk(), partialOutputs, reasoning, extensions, audio, budget, parts, last))
				// Runs only after normal completion; errors and cancellation skip it.
				.concatWith(Flux.defer(() -> completeAudio(audio, reasoning, extensions, last.get())))
				.doFinally(signal -> audio.clear());
		});
	}

	private ChatResponse mapChunk(ChatCompletionChunk chunk, Map<Integer, PartialOutputAccumulator> partialOutputs,
			Map<Integer, ReasoningMetadata.Accumulator> reasoning, ResponseExtensions extensions,
			AudioOutputMapper audio, ChatStreamBudget budget, Parts parts, AtomicReference<ChatCompletionChunk> last) {
		last.set(chunk);
		return map(chunk, partialOutputs, reasoning, extensions, audio, budget, parts);
	}

	private Flux<ChatResponse> completeAudio(AudioOutputMapper audio,
			Map<Integer, ReasoningMetadata.Accumulator> reasoning, ResponseExtensions extensions,
			@Nullable ChatCompletionChunk last) {
		List<AudioOutputMapper.Completion> completions = audio.complete();
		if (completions.isEmpty() || last == null) {
			return Flux.empty();
		}
		List<Generation> generations = completions.stream().map(completion -> {
			ReasoningMetadata.Accumulator accumulator = reasoning.computeIfAbsent(completion.index(),
					key -> new ReasoningMetadata.Accumulator());
			AssistantMessage message = AssistantMessage.builder()
				.parts(MessageParts.assistant(null, "", List.of(), completion.media()))
				.properties(accumulator.append(completion.metadata()))
				.build();
			// No finish reason is fabricated: the provider did not send one.
			ChatGenerationMetadata.Builder metadata = ChatGenerationMetadata.builder()
				.metadata(OpenRouterMetadata.CHOICE_INDEX, completion.index());
			ResponseValues.ifPresent(last.model(), value -> metadata.metadata(OpenRouterMetadata.MODEL, value));
			return new Generation(message, metadata.build());
		}).toList();
		// Usage was already reported by the stream; repeating it here would duplicate it.
		return Flux.just(new ChatResponse(generations, mapMetadata(last, extensions.values, false)));
	}

	private ChatResponse map(ChatCompletionChunk chunk, Map<Integer, PartialOutputAccumulator> partialOutputs,
			Map<Integer, ReasoningMetadata.Accumulator> reasoning, ResponseExtensions extensions,
			AudioOutputMapper audio, ChatStreamBudget budget, Parts parts) {
		var error = chunk.error();
		if (error != null) {
			// Mid-stream failures arrive as a normal chunk with a top-level error object
			// over HTTP 200; without this the truncated stream would look like a clean
			// completion.
			throw OpenRouterInBandErrorExceptionFactory.create("OpenRouter chat completion stream failed",
					error.toString(), error, null, "/chat/completions", this.sanitizer);
		}
		throwIfChoiceFailed(chunk, partialOutputs);
		List<Choice> choices = ResponseValues.CHAT_COMPLETIONS.items(chunk.choices(), "choice");
		for (Choice choice : choices) {
			if (!audio.finished(choice)) {
				budget.open(choiceIndex(choice));
			}
		}
		budget.appendResponse(chunk.extensions());
		accumulatePartialOutput(chunk, partialOutputs, audio);
		List<Generation> generations = new ArrayList<>(choices.size());
		for (Choice choice : choices) {
			if (!audio.finished(choice)) {
				generations.add(mapGeneration(choice, chunk.model(), audio, budget,
						reasoning.computeIfAbsent(choiceIndex(choice), key -> new ReasoningMetadata.Accumulator()),
						parts));
			}
		}
		clearFinishedChoices(chunk, partialOutputs);
		for (Choice choice : choices) {
			if (choice.finishReason() != null) {
				reasoning.remove(choiceIndex(choice));
				budget.finish(choiceIndex(choice));
			}
		}
		extensions.append(chunk.extensions());
		return new ChatResponse(generations, mapMetadata(chunk, extensions.values, true));
	}

	private void accumulatePartialOutput(ChatCompletionChunk chunk,
			Map<Integer, PartialOutputAccumulator> partialOutputs, AudioOutputMapper audio) {
		var choices = chunk.choices();
		if (CollectionUtils.isEmpty(choices)) {
			return;
		}
		for (Choice choice : choices) {
			var delta = choice != null ? choice.delta() : null;
			var content = delta != null ? delta.content() : null;
			if (choice != null && !audio.finished(choice) && content != null) {
				partialOutputs.computeIfAbsent(choiceIndex(choice), key -> new PartialOutputAccumulator())
					.append(content);
			}
		}
	}

	private void throwIfChoiceFailed(ChatCompletionChunk chunk, Map<Integer, PartialOutputAccumulator> partialOutputs) {
		var choices = chunk.choices();
		if (CollectionUtils.isEmpty(choices)) {
			return;
		}
		for (Choice choice : choices) {
			if (choice != null && OpenRouterChoiceErrorExceptionFactory.isFailure(choice)) {
				PartialOutputAccumulator partialOutput = partialOutputs.get(choiceIndex(choice));
				var delta = choice.delta();
				var content = delta != null ? delta.content() : null;
				if (content != null) {
					if (partialOutput == null) {
						partialOutput = new PartialOutputAccumulator();
					}
					partialOutput.append(content);
				}
				String diagnostic = partialOutput != null ? partialOutput.diagnosticValue() : null;
				if (partialOutput != null && diagnostic != null) {
					throw this.choiceErrorExceptionFactory.create(chunk, choice, diagnostic, partialOutput.truncated);
				}
				throw this.choiceErrorExceptionFactory.create(chunk, choice);
			}
		}
	}

	private void clearFinishedChoices(ChatCompletionChunk chunk,
			Map<Integer, PartialOutputAccumulator> partialOutputs) {
		var choices = chunk.choices();
		if (CollectionUtils.isEmpty(choices)) {
			return;
		}
		for (Choice choice : choices) {
			if (choice != null && choice.finishReason() != null) {
				partialOutputs.remove(choiceIndex(choice));
			}
		}
	}

	private int choiceIndex(Choice choice) {
		return Objects.requireNonNullElse(choice.index(), 0);
	}

	private Generation mapGeneration(Choice choice, @Nullable String model, AudioOutputMapper audio,
			ChatStreamBudget budget, ReasoningMetadata.Accumulator reasoning, Parts parts) {
		var delta = choice.delta();
		if (delta != null && !CollectionUtils.isEmpty(delta.toolCalls())
				&& !FinishReasonMapper.isToolCallCompletion(choice.finishReason())) {
			throw new OpenRouterTruncatedResponseException(
					"Tool call choice ended without a tool-call completion reason");
		}

		Map<String, Object> properties = properties(choice);
		budget.append(choiceIndex(choice), properties);
		List<Media> media = new ArrayList<>(GeneratedImageMapper.media(delta != null ? delta.images() : null));
		audio.append(choice, properties, media);
		Map<String, Object> snapshot = reasoning.append(properties);
		AssistantMessage assistantMessage = AssistantMessage.builder()
			.parts(parts.chunk(choiceIndex(choice), choice.finishReason() != null, delta, snapshot, media))
			.properties(snapshot)
			.build();
		ChatGenerationMetadata.Builder metadataBuilder = ChatGenerationMetadata.builder();
		metadataBuilder.finishReason(FinishReasonMapper.map(choice.finishReason()));
		if (choice.finishReason() != null) {
			metadataBuilder.finishReasonCategory(FinishReasonMapper.category(snapshot.get(RefusalMetadata.REFUSAL)));
		}
		ResponseValues.ifPresent(model, value -> metadataBuilder.metadata(OpenRouterMetadata.MODEL, value));
		ResponseValues.ifPresent(choice.index(),
				value -> metadataBuilder.metadata(OpenRouterMetadata.CHOICE_INDEX, value));
		ResponseValues.ifPresent(choice.nativeFinishReason(),
				value -> metadataBuilder.metadata(OpenRouterMetadata.NATIVE_FINISH_REASON, value));
		ResponseValues.ifPresent(snapshot.get(RefusalMetadata.REFUSAL),
				value -> metadataBuilder.metadata(RefusalMetadata.REFUSAL, value));
		ResponseValues.ifPresent(delta != null ? delta.reasoning() : null,
				value -> metadataBuilder.metadata(ReasoningMetadata.REASONING, value));
		ChatGenerationMetadata metadata = metadataBuilder.build();
		return new Generation(assistantMessage, metadata);
	}

	private Map<String, Object> properties(Choice choice) {
		var delta = choice.delta();
		Map<String, Object> properties = ReasoningMetadata.chat(delta != null ? delta.reasoning() : null,
				delta != null ? delta.reasoningDetails() : null);
		RefusalMetadata.put(properties, delta != null ? delta.refusal() : null);
		ExtensionMetadata.put(properties, delta != null ? delta.extensions() : null, choice.extensions(),
				delta != null ? delta.toolCalls() : null);
		return properties;
	}

	private ChatResponseMetadata mapMetadata(ChatCompletionChunk chunk, Map<String, @Nullable Object> extensions,
			boolean usage) {
		ChatResponseMetadata.Builder metadataBuilder = ChatResponseMetadata.builder();
		ResponseValues.ifPresent(chunk.id(), metadataBuilder::id);
		ResponseValues.ifPresent(chunk.model(), metadataBuilder::model);
		if (usage) {
			ResponseValues.ifPresent(UsageMapper.map(chunk.usage()), metadataBuilder::usage);
		}
		metadataBuilder.keyValue(ExtensionMetadata.RESPONSE, extensions);
		metadataBuilder.keyValue(OpenRouterMetadata.PROVIDER, chunk.provider());
		metadataBuilder.keyValue(OpenRouterMetadata.OBJECT, chunk.object());
		metadataBuilder.keyValue(OpenRouterMetadata.CREATED, chunk.created());
		return metadataBuilder.build();
	}

	/**
	 * The parts state of one subscription. A blocking reply has one reasoning part, one
	 * text part and then the tool calls, so each choice owns a block of indexes when it
	 * first streams anything: its reasoning, its text, and then one index per tool call,
	 * offset by the key the tool-call aggregator orders the call by. All reasoning deltas
	 * of a choice then aggregate into one part, before its text, before its calls in the
	 * order of the merged reply, whatever order the chunks interleave them in. The
	 * {@code reasoning_details} of a choice are attached once, on its finish chunk, so
	 * that the aggregated part holds them all, as a blocking call does. Indexes are
	 * unique across choices, because the aggregator groups parts by response id alone.
	 */
	private final class Parts {

		private int nextChoice;

		private final Map<Integer, ChoiceParts> choices = new LinkedHashMap<>();

		List<MessagePart> chunk(int choice, boolean finished, @Nullable Delta delta, Map<String, Object> snapshot,
				List<Media> media) {
			ChoiceParts indexes = choice(choice);
			List<MessagePart> parts = new ArrayList<>();
			String reasoning = delta != null ? delta.reasoning() : null;
			List<JsonNode> details = finished
					? ResponseValues.CHAT_COMPLETIONS.items(ReasoningMetadata.details(snapshot), "reasoning detail")
					: List.of();
			if (StringUtils.hasLength(reasoning) || !details.isEmpty()) {
				parts.add(StreamingParts.partial(
						new ReasoningPart(StringUtils.hasLength(reasoning) ? reasoning : null, null,
								details.isEmpty() ? null : MessageParts.chatPayload(details), Map.of()),
						indexes.part(0)));
			}
			String content = delta != null ? delta.content() : null;
			if (StringUtils.hasLength(content)) {
				parts.add(StreamingParts.partial(TextPart.of(content), indexes.part(1)));
			}
			List<ToolCall> wire = ResponseValues.CHAT_COMPLETIONS.items(delta != null ? delta.toolCalls() : null,
					"tool call");
			List<AssistantMessage.ToolCall> toolCalls = CompletedToolCalls.map(wire);
			// The merged calls are in key order; a call without a wire index follows the
			// previous one, as in the aggregator.
			int key = -1;
			for (int position = 0; position < toolCalls.size(); position++) {
				Integer wireIndex = wire.get(position).index();
				key = wireIndex != null && wireIndex > key ? wireIndex : key + 1;
				parts.add(StreamingParts.complete(ToolCallPart.of(toolCalls.get(position)), indexes.call(key)));
			}
			if (finished) {
				this.choices.remove(choice);
			}
			// Media is not indexed, as in Spring AI: the aggregator does not carry it.
			media.forEach(medium -> parts.add(MediaPart.of(medium)));
			return MessageParts.orEmptyText(parts);
		}

		private ChoiceParts choice(int choice) {
			ChoiceParts parts = this.choices.get(choice);
			if (parts == null) {
				parts = new ChoiceParts((long) this.nextChoice++ * ChoiceParts.SIZE);
				this.choices.put(choice, parts);
			}
			return parts;
		}

		/**
		 * A response that holds the tool-call fragments of a buffered chunk as partial
		 * parts, at the index of their wire index, which the complete call keeps. A
		 * fragment is shown once the id and name of its call are known; one without a
		 * wire index cannot be correlated and is left to the complete call. Returns
		 * {@code null} when no fragment can be shown yet.
		 */
		@Nullable ChatResponse toolCallDeltas(ChatCompletionChunk chunk) {
			List<Generation> generations = new ArrayList<>();
			for (Choice choice : ResponseValues.CHAT_COMPLETIONS.items(chunk.choices(), "choice")) {
				Delta delta = choice.delta();
				ChoiceParts indexes = choice(choiceIndex(choice));
				List<MessagePart> parts = new ArrayList<>();
				for (ToolCall fragment : ResponseValues.CHAT_COMPLETIONS.items(delta != null ? delta.toolCalls() : null,
						"tool call fragment")) {
					AssistantMessage.ToolCall call = indexes.delta(fragment);
					if (call != null) {
						parts.add(StreamingParts.partial(ToolCallPart.of(call),
								indexes.call(Objects.requireNonNull(fragment.index()))));
					}
				}
				if (!parts.isEmpty()) {
					generations.add(new Generation(AssistantMessage.builder().parts(parts).build(),
							ChatGenerationMetadata.builder()
								.metadata(OpenRouterMetadata.CHOICE_INDEX, choiceIndex(choice))
								.build()));
				}
			}
			ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder();
			ResponseValues.ifPresent(chunk.id(), metadata::id);
			ResponseValues.ifPresent(chunk.model(), metadata::model);
			return generations.isEmpty() ? null : new ChatResponse(generations, metadata.build());
		}

	}

	/**
	 * The stream indexes of one choice, a block of {@link #SIZE} from its base: its
	 * reasoning, its text, and each tool call by key. The id and name of a call arrive on
	 * its first fragment only, so they are kept by wire index.
	 */
	private static final class ChoiceParts {

		private static final int SIZE = 1 << 16;

		private final long base;

		private final Map<Integer, AssistantMessage.ToolCall> callsByWireIndex = new LinkedHashMap<>();

		private ChoiceParts(long base) {
			this.base = base;
		}

		private int call(int key) {
			if (key < 0 || key >= SIZE - 2) {
				throw new OpenRouterProtocolException(OpenRouterProtocolException.Kind.CONTRACT, "/chat/completions",
						"Tool call index out of range");
			}
			return part(2 + key);
		}

		private int part(int offset) {
			long index = this.base + offset;
			if (index > Integer.MAX_VALUE) {
				throw new OpenRouterProtocolException(OpenRouterProtocolException.Kind.CONTRACT, "/chat/completions",
						"Too many choices in one stream");
			}
			return (int) index;
		}

		// A fragment without a wire index cannot be correlated and is left to the
		// complete call.
		private AssistantMessage.@Nullable ToolCall delta(ToolCall fragment) {
			Integer wireIndex = fragment.index();
			if (wireIndex == null) {
				return null;
			}
			FunctionCall function = fragment.function();
			String name = function != null ? function.name() : null;
			String arguments = function != null && function.arguments() != null ? function.arguments() : "";
			if (StringUtils.hasText(fragment.id()) && StringUtils.hasText(name)) {
				this.callsByWireIndex.putIfAbsent(wireIndex, new AssistantMessage.ToolCall(
						Objects.requireNonNull(fragment.id()), "function", Objects.requireNonNull(name), ""));
			}
			else if (arguments.isEmpty()) {
				return null;
			}
			AssistantMessage.ToolCall call = this.callsByWireIndex.get(wireIndex);
			return call != null ? new AssistantMessage.ToolCall(call.id(), call.type(), call.name(), arguments) : null;
		}

	}

	private static final class ResponseExtensions {

		private Map<String, @Nullable Object> values = Collections.unmodifiableMap(new LinkedHashMap<>());

		private void append(Map<String, @Nullable Object> delta) {
			if (!delta.isEmpty()) {
				Map<String, @Nullable Object> merged = new LinkedHashMap<>(this.values);
				merged.putAll(delta);
				this.values = Collections.unmodifiableMap(merged);
			}
		}

	}

	private static final class PartialOutputAccumulator {

		private final StringBuilder value = new StringBuilder();

		private boolean pendingWhitespace;

		private boolean truncated;

		void append(String content) {
			for (int i = 0; i < content.length() && !this.truncated; i++) {
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
			return this.value.toString();
		}

		private void appendNormalized(char character) {
			if (this.value.length() < OpenRouterChoiceErrorExceptionFactory.MAX_DIAGNOSTIC_LENGTH) {
				this.value.append(character);
			}
			else {
				this.truncated = true;
			}
		}

		// Matches the diagnostic sanitizer, so both paths cut the same normalized text.
		private static boolean isWhitespace(char character) {
			return Character.isWhitespace(character) || Character.isISOControl(character);
		}

	}

}
