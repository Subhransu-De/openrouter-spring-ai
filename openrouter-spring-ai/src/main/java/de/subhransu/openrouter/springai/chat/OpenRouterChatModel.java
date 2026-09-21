package de.subhransu.openrouter.springai.chat;

import org.jspecify.annotations.Nullable;
import org.springframework.util.Assert;
import tools.jackson.databind.ObjectMapper;
import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterChatRequestMapper;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterChatResponseMapper;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterResponsesRequestMapper;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterResponsesResponseMapper;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterResponsesStreamingResponseMapper;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterStreamingResponseMapper;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterStreamingToolCallAggregator;
import de.subhransu.openrouter.springai.internal.Retries;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.chat.observation.ChatModelObservationDocumentation;
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Flux;

/**
 * OpenRouter {@link ChatModel}.
 *
 * <p>
 * Following Spring AI 2.0, this model does not execute tools itself: tool definitions
 * from the request options are advertised to the provider, and responses carrying tool
 * calls are returned to the caller as-is. The tool-execution loop lives in
 * {@code ToolCallingAdvisor}, registered on a {@code ChatClient}, which drives both
 * {@link #call(Prompt)} and {@link #stream(Prompt)}.
 *
 * @author Subhransu De
 */
public class OpenRouterChatModel implements ChatModel {

	private static final ChatModelObservationConvention DEFAULT_OBSERVATION_CONVENTION = new DefaultChatModelObservationConvention();

	private static final String PROVIDER_NAME = "openrouter";

	private final OpenRouterApi openRouterApi;

	private final OpenRouterChatOptions defaultOptions;

	private final ToolCallingManager toolCallingManager;

	private final RetryTemplate retryTemplate;

	private final ObservationRegistry observationRegistry;

	private ChatModelObservationConvention observationConvention = DEFAULT_OBSERVATION_CONVENTION;

	private final OpenRouterChatRequestMapper requestMapper;

	private final OpenRouterChatResponseMapper responseMapper;

	private final OpenRouterStreamingResponseMapper streamingResponseMapper;

	private final OpenRouterStreamingToolCallAggregator streamingToolCallAggregator;

	private final OpenRouterResponsesRequestMapper responsesRequestMapper;

	private final OpenRouterResponsesResponseMapper responsesResponseMapper;

	private final OpenRouterResponsesStreamingResponseMapper responsesStreamingResponseMapper;

	private OpenRouterChatModel(Builder builder) {
		Assert.notNull(builder.openRouterApi, "OpenRouterApi must not be null");
		this.openRouterApi = builder.openRouterApi;
		this.defaultOptions = builder.defaultOptions != null ? builder.defaultOptions.copy()
				: OpenRouterChatOptions.builder().build();
		this.retryTemplate = builder.retryTemplate != null ? builder.retryTemplate : RetryUtils.DEFAULT_RETRY_TEMPLATE;
		this.observationRegistry = builder.observationRegistry != null ? builder.observationRegistry
				: ObservationRegistry.NOOP;
		this.toolCallingManager = builder.toolCallingManager != null ? builder.toolCallingManager
				: ToolCallingManager.builder().observationRegistry(this.observationRegistry).build();
		ObjectMapper objectMapper = builder.objectMapper != null ? builder.objectMapper : new ObjectMapper();
		this.requestMapper = new OpenRouterChatRequestMapper(objectMapper);
		this.responseMapper = new OpenRouterChatResponseMapper();
		this.streamingResponseMapper = new OpenRouterStreamingResponseMapper();
		this.streamingToolCallAggregator = new OpenRouterStreamingToolCallAggregator(objectMapper,
				builder.toolCallAggregationMaxBytes, builder.toolCallAggregationMaxChunks,
				builder.toolCallAggregationMaxDuration);
		this.responsesRequestMapper = new OpenRouterResponsesRequestMapper(objectMapper);
		this.responsesResponseMapper = new OpenRouterResponsesResponseMapper();
		this.responsesStreamingResponseMapper = new OpenRouterResponsesStreamingResponseMapper(objectMapper,
				builder.toolCallAggregationMaxBytes, builder.toolCallAggregationMaxChunks,
				builder.toolCallAggregationMaxDuration);
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		OpenRouterChatOptions options = buildRequestOptions(prompt.getOptions());
		ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
			.prompt(new Prompt(prompt.getInstructions(), options))
			.provider(PROVIDER_NAME)
			.build();
		return ChatModelObservationDocumentation.CHAT_MODEL_OPERATION
			.observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
					this.observationRegistry)
			.observe(() -> {
				ChatResponse response = switch (resolveRequestMode(options)) {
					case OPENAI_CHAT_COMPLETIONS -> callChatCompletions(prompt, options);
					case OPENAI_RESPONSES -> callResponses(prompt, options);
				};
				observationContext.setResponse(response);
				return response;
			});
	}

	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		OpenRouterChatOptions options = buildRequestOptions(prompt.getOptions());
		return Flux.deferContextual(contextView -> {
			ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
				.prompt(new Prompt(prompt.getInstructions(), options))
				.provider(PROVIDER_NAME)
				.streaming(true)
				.build();
			Observation observation = ChatModelObservationDocumentation.CHAT_MODEL_OPERATION.observation(
					this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
					this.observationRegistry);
			Observation parentObservation = contextView.getOrDefault(ObservationThreadLocalAccessor.KEY, null);
			observation.parentObservation(parentObservation);
			// Open the parent's scope while starting so tracing derives the span parent
			// from the reactive context instead of whatever scope is on this thread.
			try (Observation.Scope ignored = parentObservation != null ? parentObservation.openScope()
					: Observation.Scope.NOOP) {
				observation.start();
			}
			Flux<ChatResponse> responses = Flux.defer(() -> switch (resolveRequestMode(options)) {
				case OPENAI_CHAT_COMPLETIONS -> {
					ChatCompletionRequest request = buildChatCompletionsRequest(prompt, options, true);
					yield this.streamingResponseMapper.map(this.streamingToolCallAggregator
						.aggregate(this.openRouterApi.chatCompletionStream(request)), options.getAudio());
				}
				case OPENAI_RESPONSES -> {
					ResponsesRequest request = this.responsesRequestMapper.map(prompt.getInstructions(), options, true,
							resolveToolDefinitions(options));
					yield this.responsesStreamingResponseMapper.map(this.openRouterApi.responsesStream(request));
				}
			});
			AtomicReference<OpenRouterUsage> usage = new AtomicReference<>();
			Flux<ChatResponse> observed = responses.doOnNext(response -> {
				if (response.getMetadata().getUsage() instanceof OpenRouterUsage current) {
					usage.set(current);
				}
			})
				.doOnError(observation::error)
				.doFinally(signal -> observation.stop())
				.contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, observation));
			return new MessageAggregator().aggregate(observed, response -> {
				// Spring AI's aggregator replaces provider usage with token totals only.
				// OpenRouter usage is a cumulative snapshot, not a per-chunk delta.
				if (usage.get() != null) {
					ChatResponseMetadata original = response.getMetadata();
					ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder()
						.id(original.getId())
						.model(original.getModel())
						.rateLimit(original.getRateLimit())
						.promptMetadata(original.getPromptMetadata())
						.usage(usage.get());
					original.entrySet().forEach(entry -> metadata.keyValue(entry.getKey(), entry.getValue()));
					response = new ChatResponse(response.getResults(), metadata.build());
				}
				observationContext.setResponse(response);
			});
		});
	}

	@Override
	public ChatOptions getOptions() {
		return this.defaultOptions.copy();
	}

	public void setObservationConvention(ChatModelObservationConvention observationConvention) {
		Assert.notNull(observationConvention, "observationConvention must not be null");
		this.observationConvention = observationConvention;
	}

	private OpenRouterRequestMode resolveRequestMode(OpenRouterChatOptions options) {
		return options.getRequestMode() != null ? options.getRequestMode()
				: OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS;
	}

	private OpenRouterChatOptions buildRequestOptions(@Nullable ChatOptions runtimeOptions) {
		return runtimeOptions == null ? this.defaultOptions.copy() : OpenRouterChatOptions.fromOptions(runtimeOptions);
	}

	private ChatResponse callChatCompletions(Prompt prompt, OpenRouterChatOptions options) {
		ChatCompletionRequest request = buildChatCompletionsRequest(prompt, options, false);
		return Retries.invoke(this.retryTemplate,
				() -> this.responseMapper.map(this.openRouterApi.chatCompletion(request)));
	}

	private ChatResponse callResponses(Prompt prompt, OpenRouterChatOptions options) {
		ResponsesRequest request = this.responsesRequestMapper.map(prompt.getInstructions(), options, false,
				resolveToolDefinitions(options));
		return Retries.invoke(this.retryTemplate,
				() -> this.responsesResponseMapper.map(this.openRouterApi.responses(request)));
	}

	private ChatCompletionRequest buildChatCompletionsRequest(Prompt prompt, OpenRouterChatOptions options,
			boolean stream) {
		return this.requestMapper.map(prompt.getInstructions(), options, stream, resolveToolDefinitions(options));
	}

	private List<ToolDefinition> resolveToolDefinitions(OpenRouterChatOptions options) {
		return !CollectionUtils.isEmpty(options.getToolCallbacks())
				? this.toolCallingManager.resolveToolDefinitions(options) : List.of();
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private @Nullable OpenRouterApi openRouterApi;

		private @Nullable OpenRouterChatOptions defaultOptions;

		private @Nullable ToolCallingManager toolCallingManager;

		private @Nullable RetryTemplate retryTemplate;

		private @Nullable ObservationRegistry observationRegistry;

		private @Nullable ObjectMapper objectMapper;

		private long toolCallAggregationMaxBytes = OpenRouterStreamingToolCallAggregator.DEFAULT_MAX_BYTES;

		private int toolCallAggregationMaxChunks = OpenRouterStreamingToolCallAggregator.DEFAULT_MAX_CHUNKS;

		private Duration toolCallAggregationMaxDuration = OpenRouterStreamingToolCallAggregator.DEFAULT_MAX_DURATION;

		private Builder() {
		}

		public Builder openRouterApi(OpenRouterApi openRouterApi) {
			this.openRouterApi = openRouterApi;
			return this;
		}

		public Builder defaultOptions(@Nullable OpenRouterChatOptions defaultOptions) {
			this.defaultOptions = defaultOptions;
			return this;
		}

		/**
		 * The manager used to resolve tool definitions from the request options. Tool
		 * <em>execution</em> is not handled by this model; register a
		 * {@code ToolCallingAdvisor} on the {@code ChatClient} instead.
		 */
		public Builder toolCallingManager(@Nullable ToolCallingManager toolCallingManager) {
			this.toolCallingManager = toolCallingManager;
			return this;
		}

		public Builder retryTemplate(@Nullable RetryTemplate retryTemplate) {
			this.retryTemplate = retryTemplate;
			return this;
		}

		public Builder observationRegistry(@Nullable ObservationRegistry observationRegistry) {
			this.observationRegistry = observationRegistry;
			return this;
		}

		public Builder objectMapper(@Nullable ObjectMapper objectMapper) {
			this.objectMapper = objectMapper;
			return this;
		}

		public Builder toolCallAggregationMaxBytes(long maxBytes) {
			this.toolCallAggregationMaxBytes = maxBytes;
			return this;
		}

		public Builder toolCallAggregationMaxChunks(int maxChunks) {
			this.toolCallAggregationMaxChunks = maxChunks;
			return this;
		}

		public Builder toolCallAggregationMaxDuration(Duration maxDuration) {
			this.toolCallAggregationMaxDuration = maxDuration;
			return this;
		}

		public OpenRouterChatModel build() {
			return new OpenRouterChatModel(this);
		}

	}

}
