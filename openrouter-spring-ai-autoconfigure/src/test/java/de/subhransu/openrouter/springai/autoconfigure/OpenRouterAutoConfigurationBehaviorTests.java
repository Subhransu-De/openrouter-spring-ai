package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.errors.OpenRouterTransientApiException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpStatus;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Behavioral auto-configuration coverage beyond the property matrix: which collaborators
 * the auto-configured API and models use, and what they send. Every assertion is made on
 * an observable outcome: a request that reached the wire or a stubbed API, a collaborator
 * that was consulted, or a startup warning. The property matrix lives in
 * {@code OpenRouterAutoConfigurationPropertyMatrixTests}.
 */
@ExtendWith(OutputCaptureExtension.class)
class OpenRouterAutoConfigurationBehaviorTests {

	private static final String SYNTHETIC_FAILURE = "synthetic failure";

	private static final String DEFAULT_MAPPER_WARNING = "OpenRouter uses a default JsonMapper";

	private static final String API_KEY = SyntheticModels.API_KEY;

	// A tool schema that only a lenient mapper accepts: single quotes, unquoted names.
	private static final String LENIENT_SCHEMA = "{type: 'object'}";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withConfiguration(
			AutoConfigurations.of(OpenRouterApiAutoConfiguration.class, OpenRouterChatAutoConfiguration.class));

	@Test
	void anApplicationApiReplacesTheAutoConfiguredOneAndServesTheChatModel() {
		OpenRouterApi applicationApi = SyntheticModels.succeedingApi();
		this.contextRunner.withBean(OpenRouterApi.class, () -> applicationApi)
			.withPropertyValues(API_KEY)
			.run(context -> {
				assertThat(context).hasNotFailed().hasSingleBean(OpenRouterApi.class);
				context.getBean(OpenRouterChatModel.class).call(new Prompt("hi"));
				verify(applicationApi).chatCompletion(any());
			});
	}

	@Test
	void chatModelResolvesToolsThroughTheApplicationToolCallingManager() {
		OpenRouterApi api = SyntheticModels.succeedingApi();
		ToolCallback callback = FunctionToolCallback.builder("application_tool", (Map<String, Object> input) -> "ok")
			.description("Resolved by the application manager")
			.inputType(Map.class)
			.build();
		this.contextRunner.withUserConfiguration(MarkerToolCallingManagerConfiguration.class)
			.withBean(OpenRouterApi.class, () -> api)
			.withPropertyValues(API_KEY)
			.run(context -> {
				context.getBean(OpenRouterChatModel.class)
					.call(new Prompt("hi", OpenRouterChatOptions.builder().toolCallbacks(callback).build()));
				ArgumentCaptor<ChatCompletionRequest> request = ArgumentCaptor.forClass(ChatCompletionRequest.class);
				verify(api).chatCompletion(request.capture());
				assertThat(request.getValue().tools()).singleElement()
					.satisfies((tool) -> assertThat(tool.function().name()).isEqualTo("manager_resolved_tool"));
			});
	}

	@ParameterizedTest
	@MethodSource("de.subhransu.openrouter.springai.autoconfigure.SyntheticModels#everyModel")
	void modelsRetryAccordingToTheApplicationRetryTemplate(SyntheticModels.ModelCall call) {
		OpenRouterApi api = SyntheticModels.failingApi(new IllegalStateException(SYNTHETIC_FAILURE));
		SyntheticModels.runner()
			.withBean(OpenRouterApi.class, () -> api)
			.withUserConfiguration(RetryTwiceConfiguration.class)
			.run(context -> {
				assertThatThrownBy(() -> call.accept(context)).hasMessageContaining(SYNTHETIC_FAILURE);
				// One attempt plus the two retries that the application template allows.
				// The
				// default policy would not retry this failure.
				assertThat(mockingDetails(api).getInvocations()).hasSize(3);
			});
	}

	@ParameterizedTest
	@MethodSource("de.subhransu.openrouter.springai.autoconfigure.SyntheticModels#everyModel")
	void modelsAreObservedThroughTheApplicationObservationRegistry(SyntheticModels.ModelCall call) {
		OpenRouterApi api = SyntheticModels.succeedingApi();
		AtomicInteger started = new AtomicInteger();
		SyntheticModels.runner()
			.withBean(OpenRouterApi.class, () -> api)
			.withBean(ObservationRegistry.class, () -> registryCounting(started))
			.run(context -> {
				call.accept(context);
				assertThat(started).hasValue(1);
			});
	}

	@Test
	void ambiguousRetryTemplatesAndRegistriesFallBackLikeTheOfficialProviders() {
		// Framework 7 core retry makes application RetryTemplate beans common. Two of
		// them without @Primary must not fail startup; the models use Spring AI's
		// defaults, as Mistral, Ollama and DeepSeek do with getIfUnique(...).
		AtomicInteger started = new AtomicInteger();
		OpenRouterApi api = SyntheticModels.failingApi(new IllegalStateException(SYNTHETIC_FAILURE));
		SyntheticModels.runner()
			.withBean(OpenRouterApi.class, () -> api)
			.withUserConfiguration(RetryTwiceConfiguration.class)
			.withBean("secondRetryTemplate", RetryTemplate.class,
					() -> new RetryTemplate(RetryPolicy.builder().maxRetries(5).delay(Duration.ZERO).build()))
			.withBean("firstObservationRegistry", ObservationRegistry.class, () -> registryCounting(started))
			.withBean("secondObservationRegistry", ObservationRegistry.class, () -> registryCounting(started))
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThatThrownBy(() -> context.getBean(OpenRouterChatModel.class).call(new Prompt("hi")))
					.hasMessageContaining(SYNTHETIC_FAILURE);
				// Neither template was used (the default policy does not retry this
				// failure)
				// and neither registry saw the call.
				verify(api, times(1)).chatCompletion(any());
				assertThat(started).hasValue(0);
			});
	}

	@ParameterizedTest
	@MethodSource("applicationMapperConfigurations")
	void theApplicationJsonMapperServesTheApiAndTheChatModel(Class<?> configuration, CapturedOutput output) {
		// The application mapper pretty-prints, which shows on the wire, and accepts
		// non-standard JSON, which shows when the chat model reads a tool schema.
		RecordingTransport transport = new RecordingTransport();
		transport.installOn(this.contextRunner)
			.withUserConfiguration(configuration)
			.withPropertyValues(API_KEY)
			.run(context -> {
				assertThat(context).hasNotFailed();
				context.getBean(OpenRouterApi.class).chatCompletion(minimalRequest());
				assertThat(transport.onlyExchange().body()).contains("\n");
				assertThat(chatRequestWithLenientSchema(configuration).tools()).singleElement()
					.satisfies((tool) -> assertThat(tool.function().parameters().path("type").asString())
						.isEqualTo("object"));
				assertThat(output).doesNotContain(DEFAULT_MAPPER_WARNING);
			});
	}

	@ParameterizedTest
	@MethodSource("unusableMapperConfigurations")
	void withoutOneUsableJsonMapperADefaultMapperIsUsedAndTheWarningSaysWhy(Class<?> configuration,
			String ignoredMappers, CapturedOutput output) {
		RecordingTransport transport = new RecordingTransport();
		transport.installOn(this.contextRunner)
			.withUserConfiguration(configuration)
			.withPropertyValues(API_KEY)
			.run(context -> {
				assertThat(context).hasNotFailed();
				context.getBean(OpenRouterApi.class).chatCompletion(minimalRequest());
				assertThat(transport.onlyExchange().body()).doesNotContain("\n");
				assertThatThrownBy(() -> chatRequestWithLenientSchema(configuration))
					.hasMessageContaining("Invalid JSON schema");
				assertThat(output).contains(DEFAULT_MAPPER_WARNING, ignoredMappers);
			});
	}

	@ParameterizedTest
	@MethodSource("categoryCases")
	void attributionCategoriesAreJoinedWithCommasOnTheWire(String[] categoryProperties, String expectedHeader) {
		RecordingTransport transport = new RecordingTransport();
		transport.installOn(this.contextRunner)
			.withPropertyValues(API_KEY)
			.withPropertyValues(categoryProperties)
			.run(context -> {
				context.getBean(OpenRouterApi.class).chatCompletion(minimalRequest());
				assertThat(transport.onlyExchange().headers().getFirst("X-OpenRouter-Categories"))
					.isEqualTo(expectedHeader);
			});
	}

	@Test
	void attributionPropertiesReachTheWireAsHeaders() {
		RecordingTransport transport = new RecordingTransport();
		transport.installOn(this.contextRunner)
			.withPropertyValues(API_KEY, "spring.ai.openrouter.app.http-referer=https://app.example",
					"spring.ai.openrouter.app.title=My App", "spring.ai.openrouter.app.categories[0]=translation")
			.run(context -> {
				context.getBean(OpenRouterApi.class).chatCompletion(minimalRequest());
				assertThat(transport.onlyExchange().headers().toSingleValueMap())
					.containsEntry("HTTP-Referer", "https://app.example")
					.containsEntry("X-OpenRouter-Title", "My App")
					.containsEntry("X-OpenRouter-Categories", "translation");
			});
	}

	@Test
	void anOpenRouterErrorReachesTheCallerThroughTheBootBuiltApi() {
		RecordingTransport transport = new RecordingTransport().blockingResponse(HttpStatus.TOO_MANY_REQUESTS,
				"{\"error\":{\"message\":\"slow down\",\"code\":429}}");
		transport.installOn(this.contextRunner).withPropertyValues(API_KEY).run(context -> {
			assertThatThrownBy(() -> context.getBean(OpenRouterApi.class).chatCompletion(minimalRequest()))
				.isInstanceOfSatisfying(OpenRouterTransientApiException.class, failure -> {
					assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
					assertThat(failure.getEndpoint()).isEqualTo("/chat/completions");
					assertThat(failure.getResponseBody()).contains("slow down");
				});
		});
	}

	static List<Arguments> applicationMapperConfigurations() {
		return List
			.of(Arguments.of(Named.of("one JsonMapper bean", ApplicationJsonMapperConfiguration.class)), Arguments
				.of(Named.of("JsonMapper next to another format mapper", JsonAndOtherFormatMapperConfiguration.class)));
	}

	static List<Arguments> unusableMapperConfigurations() {
		return List.of(
				Arguments.of(Named.of("two JsonMapper beans", TwoJsonMappersConfiguration.class),
						"2 JSON ObjectMapper bean(s) are not used"),
				Arguments.of(Named.of("only a plain ObjectMapper bean", PlainObjectMapperConfiguration.class),
						"1 JSON ObjectMapper bean(s) are not used"));
	}

	static List<Arguments> categoryCases() {
		return List.of(
				Arguments.of(
						Named.of("several",
								new String[] { "spring.ai.openrouter.app.categories[0]=translation",
										"spring.ai.openrouter.app.categories[1]=programming" }),
						"translation,programming"),
				Arguments.of(Named.of("one", new String[] { "spring.ai.openrouter.app.categories[0]=samples" }),
						"samples"),
				Arguments.of(Named.of("absent", new String[0]), null),
				// An explicitly empty list must not emit a blank header.
				Arguments.of(Named.of("explicitly empty", new String[] { "spring.ai.openrouter.app.categories=" }),
						null));
	}

	private static ObservationRegistry registryCounting(AtomicInteger started) {
		ObservationRegistry registry = ObservationRegistry.create();
		registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
			@Override
			public boolean supportsContext(Observation.Context context) {
				return true;
			}

			@Override
			public void onStart(Observation.Context context) {
				started.incrementAndGet();
			}
		});
		return registry;
	}

	private static ChatCompletionRequest minimalRequest() {
		return ChatCompletionRequest.builder()
			.model("m")
			.messages(List.of(new ChatMessage("user", "hi", null, null, null)))
			.stream(false)
			.build();
	}

	// Runs the configured chat model, wired to a stubbed API, with a tool whose schema is
	// not strict JSON, and returns the request the model produced for it.
	private ChatCompletionRequest chatRequestWithLenientSchema(Class<?> mapperConfiguration) {
		OpenRouterApi api = SyntheticModels.succeedingApi();
		ToolCallback lenientTool = new ToolCallback() {
			@Override
			public ToolDefinition getToolDefinition() {
				return ToolDefinition.builder()
					.name("lenient_tool")
					.description("Schema that only a lenient mapper reads")
					.inputSchema(LENIENT_SCHEMA)
					.build();
			}

			@Override
			public String call(String toolInput) {
				return "ok";
			}
		};
		ArgumentCaptor<ChatCompletionRequest> captured = ArgumentCaptor.forClass(ChatCompletionRequest.class);
		this.contextRunner.withUserConfiguration(mapperConfiguration)
			.withBean(OpenRouterApi.class, () -> api)
			.withPropertyValues(API_KEY)
			.run(context -> {
				assertThat(context).hasNotFailed();
				context.getBean(OpenRouterChatModel.class)
					.call(new Prompt("hi", OpenRouterChatOptions.builder().toolCallbacks(lenientTool).build()));
				verify(api).chatCompletion(captured.capture());
			});
		return captured.getValue();
	}

	private static JsonMapper lenientIndentingMapper() {
		return JsonMapper.builder()
			.enable(SerializationFeature.INDENT_OUTPUT)
			.enable(JsonReadFeature.ALLOW_SINGLE_QUOTES, JsonReadFeature.ALLOW_UNQUOTED_PROPERTY_NAMES)
			.build();
	}

	@Configuration(proxyBeanMethods = false)
	static class MarkerToolCallingManagerConfiguration {

		@Bean
		ToolCallingManager markerToolCallingManager() {
			return new ToolCallingManager() {
				@Override
				public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
					return List.of(ToolDefinition.builder()
						.name("manager_resolved_tool")
						.description("Defined by the application manager")
						.inputSchema("{\"type\":\"object\"}")
						.build());
				}

				@Override
				public ToolExecutionResult executeToolCalls(Prompt prompt,
						org.springframework.ai.chat.model.ChatResponse chatResponse) {
					throw new UnsupportedOperationException("models do not execute tools");
				}
			};
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class RetryTwiceConfiguration {

		@Bean
		RetryTemplate retryTemplate() {
			return new RetryTemplate(RetryPolicy.builder().maxRetries(2).delay(Duration.ZERO).build());
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class ApplicationJsonMapperConfiguration {

		@Bean
		JsonMapper applicationJsonMapper() {
			return lenientIndentingMapper();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class JsonAndOtherFormatMapperConfiguration {

		@Bean
		JsonMapper applicationJsonMapper() {
			return lenientIndentingMapper();
		}

		// Stands in for Boot's XmlMapper or CBORMapper without the extra dataformat
		// dependency: another ObjectMapper bean that is not a JsonMapper. When the
		// application replaces Boot's JsonMapper none of them is primary, so a lookup by
		// ObjectMapper would find two beans.
		@Bean
		ObjectMapper otherFormatMapper() {
			return new ObjectMapper();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class TwoJsonMappersConfiguration {

		@Bean
		JsonMapper firstJsonMapper() {
			return lenientIndentingMapper();
		}

		@Bean
		JsonMapper secondJsonMapper() {
			return lenientIndentingMapper();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class PlainObjectMapperConfiguration {

		@Bean
		ObjectMapper plainObjectMapper() {
			// A format-agnostic mapper, not a JsonMapper, that would pretty-print if the
			// API wrongly used it.
			ObjectMapper plain = new ObjectMapper().rebuild().enable(SerializationFeature.INDENT_OUTPUT).build();
			assertThat(plain).isNotInstanceOf(JsonMapper.class);
			return plain;
		}

	}

}
