package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterServiceTier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

/**
 * Property-binding matrix for {@code spring.ai.openrouter.*}. Each documented property is
 * set, then asserted on the resolved default {@link OpenRouterChatOptions} (or the bound
 * properties bean where it shapes the API client). A relaxed-binding typo or a missed
 * {@code toOptions()} mapping step fails a specific assertion here. JSON wire-name
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
		AtomicReference<URI> requestUri = new AtomicReference<>();
		AtomicReference<String> authorization = new AtomicReference<>();
		contextRunner
			.withBean("recordingRequestFactoryBuilder", ClientHttpRequestFactoryBuilder.class,
					() -> settings -> new RecordingRequestFactory(requestUri, authorization))
			.withPropertyValues(API_KEY, "spring.ai.openrouter.base-url=https://proxy.example/api/v1",
					"spring.ai.openrouter.chat.model=openai/gpt-5.4")
			.run(context -> {
				assertThat(resolvedOptions(context).getModel()).isEqualTo("openai/gpt-5.4");

				context.getBean(de.subhransu.openrouter.springai.api.OpenRouterApi.class)
					.chatCompletion(new ChatCompletionRequest("openai/gpt-5.4", null,
							List.of(new ChatMessage("user", "hi", null, null, null)), null, null, null, null, null,
							null, null, null, null, null, null, null, null, false, null, null, null, null, null, null,
							null, null, null, null, null, null));

				assertThat(requestUri.get().toString()).startsWith("https://proxy.example/api/v1");
				assertThat(authorization.get()).isEqualTo("Bearer test-key");
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
	void rejectsMutuallyExclusiveReasoningProperties() {
		contextRunner
			.withPropertyValues(API_KEY, "spring.ai.openrouter.chat.reasoning.effort=high",
					"spring.ai.openrouter.chat.reasoning.max-tokens=1024")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("effort", "max-tokens");
			});
	}

	// ---------------------------------------------------------------------
	// Attribution app properties
	// ---------------------------------------------------------------------

	@Test
	void bindsAttributionAppProperties() {
		contextRunner
			.withPropertyValues(API_KEY, "spring.ai.openrouter.app.http-referer=https://app.example",
					"spring.ai.openrouter.app.title=My App", "spring.ai.openrouter.app.categories[0]=translation",
					"spring.ai.openrouter.app.categories[1]=programming")
			.run(context -> {
				OpenRouterCommonProperties.App app = context.getBean(OpenRouterCommonProperties.class).getApp();
				assertThat(app.getHttpReferer()).isEqualTo("https://app.example");
				assertThat(app.getTitle()).isEqualTo("My App");
				assertThat(app.getCategories()).containsExactly("translation", "programming");
			});
	}

	// ---------------------------------------------------------------------
	// Negative / guard behavior
	// ---------------------------------------------------------------------

	@Test
	void aTypoInAPropertyNameDoesNotBindAndLeavesDefault() {
		// A misspelled property must not silently bind. temperature stays unset (null)
		// and
		// the misspelled key is ignored, so a regression that renamed the setter would be
		// caught by the matching positive test, while this proves no accidental coercion.
		contextRunner.withPropertyValues(API_KEY, "spring.ai.openrouter.chat.temprature=0.7").run(context -> {
			assertThat(resolvedOptions(context).getTemperature()).isNull();
		});
	}

	@Test
	void blankApiKeyFailsContextStartup() {
		contextRunner.withPropertyValues("spring.ai.openrouter.api-key=").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("OpenRouter API key");
		});
	}

	static final class RecordingRequestFactory implements ClientHttpRequestFactory {

		private final AtomicReference<URI> requestUri;

		private final AtomicReference<String> authorization;

		RecordingRequestFactory(AtomicReference<URI> requestUri, AtomicReference<String> authorization) {
			this.requestUri = requestUri;
			this.authorization = authorization;
		}

		@Override
		public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) {
			return new MockClientHttpRequest(httpMethod, uri) {
				@Override
				protected ClientHttpResponse executeInternal() {
					RecordingRequestFactory.this.requestUri.set(getURI());
					RecordingRequestFactory.this.authorization.set(getHeaders().getFirst("Authorization"));
					MockClientHttpResponse response = new MockClientHttpResponse(
							"{\"id\":\"x\",\"object\":\"chat.completion\",\"model\":\"m\",\"choices\":[]}"
								.getBytes(StandardCharsets.UTF_8),
							HttpStatus.OK);
					response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
					return response;
				}
			};
		}

	}

}
