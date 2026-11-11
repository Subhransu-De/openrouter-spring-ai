package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterServiceTier;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Property-binding matrix for {@code spring.ai.openrouter.*}. Each documented property is
 * set, then asserted on the resolved default {@link OpenRouterChatOptions} (or the bound
 * properties bean where it shapes the API client). A relaxed-binding typo or a missed
 * {@code toOptions()} mapping step fails a specific assertion here. Where a property
 * shapes the HTTP client or the request, the test asserts what reached it. JSON wire-name
 * coverage lives in {@code OpenRouterChatRequestSerializationTests}.
 */
class OpenRouterAutoConfigurationPropertyMatrixTests {

	private static final String API_KEY = "spring.ai.openrouter.api-key=test-key";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withConfiguration(
			AutoConfigurations.of(OpenRouterApiAutoConfiguration.class, OpenRouterChatAutoConfiguration.class));

	private OpenRouterChatOptions resolvedOptions(
			org.springframework.boot.test.context.assertj.AssertableApplicationContext context) {
		return (OpenRouterChatOptions) context.getBean(OpenRouterChatModel.class).getOptions();
	}

	// ---------------------------------------------------------------------
	// Connection / common properties
	// ---------------------------------------------------------------------

	@Test
	void bindsApiKeyBaseUrlAndModel() {
		// The properties bean is an intermediate: what matters is that base-url and
		// api-key reach the actual HTTP client. Capture the outgoing request and assert
		// the URL and Authorization header, not just the bound POJO.
		RecordingTransport transport = new RecordingTransport();
		transport.installOn(this.contextRunner)
			.withPropertyValues(API_KEY, "spring.ai.openrouter.base-url=https://proxy.example/api/v1",
					"spring.ai.openrouter.chat.model=openai/gpt-5.4")
			.run(context -> {
				assertThat(resolvedOptions(context).getModel()).isEqualTo("openai/gpt-5.4");

				context.getBean(OpenRouterApi.class)
					.chatCompletion(ChatCompletionRequest.builder()
						.model("openai/gpt-5.4")
						.messages(List.of(new ChatMessage("user", "hi", null, null, null)))
						.stream(false)
						.build());

				RecordingTransport.Exchange exchange = transport.onlyExchange();
				assertThat(exchange.uri().toString()).isEqualTo("https://proxy.example/api/v1/chat/completions");
				assertThat(exchange.headers().getFirst("Authorization")).isEqualTo("Bearer test-key");
			});
	}

	// ---------------------------------------------------------------------
	// Sampling and generation scalars -> resolved default options
	// ---------------------------------------------------------------------

	@Test
	void bindsSamplingAndGenerationScalars() {
		contextRunner
			.withPropertyValues(API_KEY, "spring.ai.openrouter.chat.temperature=0.7",
					"spring.ai.openrouter.chat.top-p=0.9", "spring.ai.openrouter.chat.top-k=40",
					"spring.ai.openrouter.chat.max-tokens=256", "spring.ai.openrouter.chat.max-completion-tokens=512",
					"spring.ai.openrouter.chat.seed=42", "spring.ai.openrouter.chat.presence-penalty=0.1",
					"spring.ai.openrouter.chat.frequency-penalty=0.2",
					"spring.ai.openrouter.chat.repetition-penalty=1.1", "spring.ai.openrouter.chat.min-p=0.05",
					"spring.ai.openrouter.chat.top-a=0.8", "spring.ai.openrouter.chat.user=user-7")
			.run(context -> {
				OpenRouterChatOptions options = resolvedOptions(context);
				assertThat(options.getTemperature()).isEqualTo(0.7);
				assertThat(options.getTopP()).isEqualTo(0.9);
				assertThat(options.getTopK()).isEqualTo(40);
				assertThat(options.getMaxTokens()).isEqualTo(256);
				assertThat(options.getMaxCompletionTokens()).isEqualTo(512);
				assertThat(options.getSeed()).isEqualTo(42);
				assertThat(options.getPresencePenalty()).isEqualTo(0.1);
				assertThat(options.getFrequencyPenalty()).isEqualTo(0.2);
				assertThat(options.getRepetitionPenalty()).isEqualTo(1.1);
				assertThat(options.getMinP()).isEqualTo(0.05);
				assertThat(options.getTopA()).isEqualTo(0.8);
				assertThat(options.getUser()).isEqualTo("user-7");
			});
	}

	@ParameterizedTest
	@EnumSource(OpenRouterRequestMode.class)
	void boundSamplingRoutingAndAttributionPropertiesReachTheWireInBothModes(OpenRouterRequestMode mode) {
		RecordingTransport transport = new RecordingTransport().blockingResponse(HttpStatus.OK, """
				{"id":"synthetic","status":"completed","output":[],
				"choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}]}
				""");
		transport.installOn(this.contextRunner)
			.withPropertyValues(API_KEY, "spring.ai.openrouter.chat.request-mode=" + mode,
					"spring.ai.openrouter.chat.model=synthetic/model", "spring.ai.openrouter.chat.temperature=0.7",
					"spring.ai.openrouter.chat.top-p=0.9", "spring.ai.openrouter.chat.service-tier=FLEX",
					"spring.ai.openrouter.chat.route=fallback", "spring.ai.openrouter.chat.user=user-7",
					"spring.ai.openrouter.chat.models[0]=synthetic/first",
					"spring.ai.openrouter.chat.models[1]=synthetic/second")
			.run(context -> {
				assertThat(context).hasNotFailed();

				context.getBean(OpenRouterChatModel.class).call(new Prompt("hi"));

				JsonNode body = new ObjectMapper().readTree(transport.onlyExchange().body());
				assertThat(body.path("model").asString()).isEqualTo("synthetic/model");
				assertThat(body.path("temperature").asDouble()).isEqualTo(0.7);
				assertThat(body.path("top_p").asDouble()).isEqualTo(0.9);
				assertThat(body.path("service_tier").asString()).isEqualToIgnoringCase("flex");
				assertThat(body.path("route").asString()).isEqualTo("fallback");
				assertThat(body.path("user").asString()).isEqualTo("user-7");
				assertThat(body.path("models")).extracting(JsonNode::asString)
					.containsExactly("synthetic/first", "synthetic/second");
			});
	}

	@Test
	void bindsModelsFallbackListAndStopSequences() {
		contextRunner
			.withPropertyValues(API_KEY, "spring.ai.openrouter.chat.models[0]=anthropic/claude-3.5-sonnet",
					"spring.ai.openrouter.chat.models[1]=openai/gpt-5.4", "spring.ai.openrouter.chat.stop[0]=STOP",
					"spring.ai.openrouter.chat.stop[1]=END")
			.run(context -> {
				OpenRouterChatOptions options = resolvedOptions(context);
				assertThat(options.getModels()).containsExactly("anthropic/claude-3.5-sonnet", "openai/gpt-5.4");
				assertThat(options.getStopSequences()).containsExactly("STOP", "END");
			});
	}

	@Test
	void bindsRequestModeServiceTierRouteAndIncludeUsage() {
		contextRunner
			.withPropertyValues(API_KEY, "spring.ai.openrouter.chat.request-mode=OPENAI_RESPONSES",
					"spring.ai.openrouter.chat.service-tier=FLEX", "spring.ai.openrouter.chat.route=fallback",
					"spring.ai.openrouter.chat.include-usage=true")
			.run(context -> {
				OpenRouterChatOptions options = resolvedOptions(context);
				assertThat(options.getRequestMode()).isEqualTo(OpenRouterRequestMode.OPENAI_RESPONSES);
				assertThat(options.getServiceTier()).isEqualTo(OpenRouterServiceTier.FLEX);
				assertThat(options.getRoute()).isEqualTo("fallback");
				assertThat(options.getIncludeUsage()).isTrue();
			});
	}

	@Test
	void defaultsToChatCompletionsRequestMode() {
		contextRunner.withPropertyValues(API_KEY)
			.run(context -> assertThat(resolvedOptions(context).getRequestMode())
				.isEqualTo(OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS));
	}

	@Test
	void bindsToolControlsAndMetadata() {
		contextRunner
			.withPropertyValues(API_KEY, "spring.ai.openrouter.chat.parallel-tool-calls=false",
					"spring.ai.openrouter.chat.tool-choice=auto", "spring.ai.openrouter.chat.metadata.trace=abc",
					"spring.ai.openrouter.chat.metadata.tenant=acme")
			.run(context -> {
				OpenRouterChatOptions options = resolvedOptions(context);
				assertThat(options.getParallelToolCalls()).isFalse();
				assertThat(options.getToolChoice()).isEqualTo("auto");
				assertThat(options.getMetadata()).containsEntry("trace", "abc").containsEntry("tenant", "acme");
			});
	}

	// ---------------------------------------------------------------------
	// Nested provider routing object
	// ---------------------------------------------------------------------

	@Test
	void bindsFullProviderRoutingObject() {
		contextRunner
			.withPropertyValues(API_KEY, "spring.ai.openrouter.chat.provider.allow-fallbacks=true",
					"spring.ai.openrouter.chat.provider.require-parameters=false",
					"spring.ai.openrouter.chat.provider.data-collection=deny",
					"spring.ai.openrouter.chat.provider.order[0]=openai",
					"spring.ai.openrouter.chat.provider.order[1]=anthropic",
					"spring.ai.openrouter.chat.provider.ignore[0]=azure",
					"spring.ai.openrouter.chat.provider.quantizations[0]=fp16",
					"spring.ai.openrouter.chat.provider.sort=throughput")
			.run(context -> {
				var provider = resolvedOptions(context).getProvider();
				assertThat(provider.allowFallbacks()).isTrue();
				assertThat(provider.requireParameters()).isFalse();
				assertThat(provider.dataCollection()).isEqualTo("deny");
				assertThat(provider.order()).containsExactly("openai", "anthropic");
				assertThat(provider.ignore()).containsExactly("azure");
				assertThat(provider.quantizations()).containsExactly("fp16");
				assertThat(provider.sort()).isEqualTo("throughput");
			});
	}

	@Test
	void bindsReasoningOptions() {
		contextRunner.withPropertyValues(API_KEY, "spring.ai.openrouter.chat.reasoning.effort=high",
				"spring.ai.openrouter.chat.reasoning.exclude=false", "spring.ai.openrouter.chat.reasoning.enabled=true")
			.run(context -> {
				var reasoning = resolvedOptions(context).getReasoning();
				assertThat(reasoning.effort()).isEqualTo("high");
				assertThat(reasoning.maxTokens()).isNull();
				assertThat(reasoning.exclude()).isFalse();
				assertThat(reasoning.enabled()).isTrue();
			});
	}

	@Test
	void bindsReasoningMaxTokensWithoutEffort() {
		contextRunner
			.withPropertyValues(API_KEY, "spring.ai.openrouter.chat.reasoning.max-tokens=1024",
					"spring.ai.openrouter.chat.reasoning.exclude=true")
			.run(context -> {
				var reasoning = resolvedOptions(context).getReasoning();
				assertThat(reasoning.maxTokens()).isEqualTo(1024);
				assertThat(reasoning.effort()).isNull();
				assertThat(reasoning.exclude()).isTrue();
			});
	}

	@Test
	void rejectsMutuallyExclusiveReasoningProperties() {
		contextRunner
			.withPropertyValues(API_KEY, "spring.ai.openrouter.chat.reasoning.effort=high",
					"spring.ai.openrouter.chat.reasoning.max-tokens=1024")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause().hasMessageContainingAll("effort", "max-tokens");
			});
	}

	// ---------------------------------------------------------------------
	// Image generation settings sent as image_config
	// ---------------------------------------------------------------------

	@ParameterizedTest
	@EnumSource(OpenRouterRequestMode.class)
	void bindsImageConfigAndSendsItInTheRequest(OpenRouterRequestMode mode) {
		OpenRouterApi api = SyntheticModels.succeedingApi();
		when(api.responses(any()))
			.thenReturn(new ResponsesResult("resp", "response", 1L, "m", "completed", List.of(), null, null));
		contextRunner.withBean(OpenRouterApi.class, () -> api)
			.withPropertyValues(API_KEY, "spring.ai.openrouter.chat.request-mode=" + mode,
					"spring.ai.openrouter.chat.modalities=text,image",
					"spring.ai.openrouter.chat.image-config.[aspect_ratio]=16:9",
					"spring.ai.openrouter.chat.image-config.[image_size]=2K")
			.run(context -> {
				Map<String, Object> expected = Map.of("aspect_ratio", "16:9", "image_size", "2K");
				OpenRouterChatModel model = context.getBean(OpenRouterChatModel.class);
				assertThat(resolvedOptions(context).getImageConfig()).isEqualTo(expected);

				model.call(new Prompt("draw"));
				assertThat(sentImageConfig(api, mode)).isEqualTo(expected);
			});
	}

	@ParameterizedTest
	@EnumSource(OpenRouterRequestMode.class)
	void withoutImageConfigNoImageConfigIsSent(OpenRouterRequestMode mode) {
		OpenRouterApi api = SyntheticModels.succeedingApi();
		when(api.responses(any()))
			.thenReturn(new ResponsesResult("resp", "response", 1L, "m", "completed", List.of(), null, null));
		contextRunner.withBean(OpenRouterApi.class, () -> api)
			.withPropertyValues(API_KEY, "spring.ai.openrouter.chat.request-mode=" + mode)
			.run(context -> {
				context.getBean(OpenRouterChatModel.class).call(new Prompt("draw"));
				assertThat(sentImageConfig(api, mode)).isNull();
			});
	}

	private static @Nullable Map<String, @Nullable Object> sentImageConfig(OpenRouterApi api,
			OpenRouterRequestMode mode) {
		if (mode == OpenRouterRequestMode.OPENAI_RESPONSES) {
			ArgumentCaptor<ResponsesRequest> request = ArgumentCaptor.forClass(ResponsesRequest.class);
			verify(api).responses(request.capture());
			return request.getValue().imageConfig();
		}
		ArgumentCaptor<ChatCompletionRequest> request = ArgumentCaptor.forClass(ChatCompletionRequest.class);
		verify(api).chatCompletion(request.capture());
		return request.getValue().imageConfig();
	}

	// ---------------------------------------------------------------------
	// Negative / guard behavior
	// ---------------------------------------------------------------------

	@Test
	void blankApiKeyFailsContextStartup() {
		contextRunner.withPropertyValues("spring.ai.openrouter.api-key=").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("OpenRouter API key");
		});
	}

}
