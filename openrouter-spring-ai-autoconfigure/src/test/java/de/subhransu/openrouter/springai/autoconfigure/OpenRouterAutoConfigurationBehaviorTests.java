package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterToolCallingManagers;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingModel;
import de.subhransu.openrouter.springai.image.OpenRouterImageModel;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.core.retry.RetryTemplate;

/**
 * Behavioral auto-configuration coverage beyond the property matrix: bean back-off,
 * custom collaborator integration ({@link JsonMapper}, {@link RetryTemplate},
 * {@link ToolCallingManager}), and the attribution-categories comma-join contract
 * observed on a real outgoing request. The full property matrix lives in
 * {@code OpenRouterAutoConfigurationPropertyMatrixTests}.
 */
@ExtendWith(OutputCaptureExtension.class)
@Tag("configuration")
class OpenRouterAutoConfigurationBehaviorTests {

	private static final String DEFAULT_MAPPER_WARNING = "OpenRouter uses a default JsonMapper";

	private static final String API_KEY = "spring.ai.openrouter.api-key=test-key";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withConfiguration(
			AutoConfigurations.of(OpenRouterApiAutoConfiguration.class, OpenRouterChatAutoConfiguration.class));

	// ---------------------------------------------------------------------
	// Bean back-off and custom collaborators
	// ---------------------------------------------------------------------

	@Tag("configuration.beans")
	@Test
	void backsOffWhenUserDefinesOwnApiBean() {
		contextRunner.withUserConfiguration(CustomApiConfiguration.class).withPropertyValues(API_KEY).run(context -> {
			assertThat(context).hasSingleBean(OpenRouterApi.class);
			assertThat(context.getBean(OpenRouterApi.class))
				.isSameAs(context.getBean(CustomApiConfiguration.class).customApi);
		});
	}

	@Tag("configuration.beans")
	@Test
	void usesCustomJsonMapperRetryTemplateAndToolCallingManager() {
		contextRunner.withUserConfiguration(CustomCollaboratorsConfiguration.class)
			.withPropertyValues(API_KEY)
			.run(context -> {
				// The user-supplied collaborators must actually be injected into the
				// auto-configured chat model, not merely coexist in the context.
				assertThat(context).hasSingleBean(OpenRouterChatModel.class);
				OpenRouterChatModel chatModel = context.getBean(OpenRouterChatModel.class);
				assertThat(chatModel).extracting("toolCallingManager")
					.isSameAs(context.getBean(ToolCallingManager.class));
				assertThat(chatModel).extracting("retryTemplate").isSameAs(context.getBean(RetryTemplate.class));
				assertThat(chatModel).extracting("requestMapper")
					.extracting("objectMapper")
					.isSameAs(context.getBean(JsonMapper.class));
				assertThat(context.getBean(OpenRouterApi.class)).extracting("objectMapper")
					.isSameAs(context.getBean(JsonMapper.class));
			});
	}

	@Tag("configuration.beans")
	@Test
	void otherFormatMappersDoNotMakeTheJsonMapperLookupAmbiguous(CapturedOutput output) {
		// Boot 4 defines an XmlMapper or CBORMapper next to the JsonMapper when those
		// formats are on the classpath. When the application replaces Boot's JsonMapper,
		// none of them is primary, so a lookup by ObjectMapper would find two beans.
		contextRunner.withUserConfiguration(JsonAndOtherFormatMapperConfiguration.class)
			.withPropertyValues(API_KEY)
			.run(context -> {
				assertThat(context).hasNotFailed();
				JsonMapper jsonMapper = context.getBean(JsonMapper.class);
				assertThat(context.getBean(OpenRouterApi.class)).extracting("objectMapper").isSameAs(jsonMapper);
				assertThat(context.getBean(OpenRouterChatModel.class)).extracting("requestMapper")
					.extracting("objectMapper")
					.isSameAs(jsonMapper);
				assertThat(output).doesNotContain(DEFAULT_MAPPER_WARNING);
			});
	}

	@Tag("configuration.beans")
	@Test
	void ambiguousRetryTemplatesAndRegistriesFallBackLikeTheOfficialProviders() {
		// Framework 7 core retry makes application RetryTemplate beans common. Two of
		// them without @Primary must not fail startup; the models use Spring AI's
		// defaults, as Mistral, Ollama and DeepSeek do with getIfUnique(...).
		new ApplicationContextRunner()
			.withConfiguration(
					AutoConfigurations.of(OpenRouterApiAutoConfiguration.class, OpenRouterChatAutoConfiguration.class,
							OpenRouterEmbeddingAutoConfiguration.class, OpenRouterImageAutoConfiguration.class))
			.withUserConfiguration(AmbiguousInfrastructureConfiguration.class)
			.withPropertyValues(API_KEY)
			.run(context -> {
				assertThat(context).hasNotFailed();
				for (Object model : List.of(context.getBean(OpenRouterChatModel.class),
						context.getBean(OpenRouterEmbeddingModel.class), context.getBean(OpenRouterImageModel.class))) {
					assertThat(model).extracting("retryTemplate").isSameAs(RetryUtils.DEFAULT_RETRY_TEMPLATE);
					assertThat(model).extracting("observationRegistry").isSameAs(ObservationRegistry.NOOP);
				}
			});
	}

	@Tag("configuration.beans")
	@Test
	void ambiguousJsonMappersFallBackToADefaultMapperAndSayWhy(CapturedOutput output) {
		contextRunner.withUserConfiguration(TwoJsonMappersConfiguration.class)
			.withPropertyValues(API_KEY)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(OpenRouterApi.class)).extracting("objectMapper")
					.isInstanceOf(JsonMapper.class)
					.isNotIn(context.getBeansOfType(JsonMapper.class).values().toArray());
				assertThat(output).contains(DEFAULT_MAPPER_WARNING, "2 JSON ObjectMapper bean(s) are not used");
			});
	}

	@Tag("configuration.beans")
	@Test
	void plainObjectMapperBeanIsNotUsedForTheJsonWireProtocolAndSayWhy(CapturedOutput output) {
		contextRunner.withUserConfiguration(PlainObjectMapperConfiguration.class)
			.withPropertyValues(API_KEY)
			.run(context -> {
				assertThat(context).hasNotFailed();
				ObjectMapper plain = context.getBean(ObjectMapper.class);
				assertThat(context.getBean(OpenRouterApi.class)).extracting("objectMapper")
					.isInstanceOf(JsonMapper.class)
					.isNotSameAs(plain);
				assertThat(output).contains(DEFAULT_MAPPER_WARNING, "1 JSON ObjectMapper bean(s) are not used");
			});
	}

	@Tag("configuration.model-selection")
	@Test
	void anotherChatProviderLeavesApiForOtherDefaultOpenRouterModels() {
		contextRunner.withPropertyValues(API_KEY, "spring.ai.model.chat=other").run(context -> {
			assertThat(context).hasSingleBean(OpenRouterApi.class);
			assertThat(context).doesNotHaveBean(OpenRouterChatModel.class);
		});
	}

	// ---------------------------------------------------------------------
	// Attribution categories join behavior (observed on the wire)
	// ---------------------------------------------------------------------

	@Tag("configuration.properties")
	@Test
	void multipleCategoriesAreJoinedWithCommasOnTheWire() {
		assertCategoriesHeader(new String[] { "spring.ai.openrouter.app.categories[0]=translation",
				"spring.ai.openrouter.app.categories[1]=programming" }, "translation,programming");
	}

	@Tag("configuration.properties")
	@Test
	void singleCategoryIsSentVerbatim() {
		assertCategoriesHeader(new String[] { "spring.ai.openrouter.app.categories[0]=samples" }, "samples");
	}

	@Tag("configuration.properties")
	@Test
	void absentCategoriesOmitTheHeader() {
		assertCategoriesHeader(new String[0], null);
	}

	@Tag("configuration.properties")
	@Test
	void emptyCategoriesListOmitsTheHeader() {
		// An explicitly empty list must not emit a blank header.
		assertCategoriesHeader(new String[] { "spring.ai.openrouter.app.categories=" }, null);
	}

	private void assertCategoriesHeader(String[] categoryProperties, String expectedHeader) {
		String[] properties = new String[categoryProperties.length + 1];
		properties[0] = API_KEY;
		System.arraycopy(categoryProperties, 0, properties, 1, categoryProperties.length);

		// Per-test capture: no shared static state, so these tests stay safe under
		// parallel execution.
		AtomicReference<String> categoriesHeader = new AtomicReference<>();
		contextRunner
			.withBean("recordingRequestFactoryBuilder", ClientHttpRequestFactoryBuilder.class,
					() -> settings -> new RecordingRequestFactory(categoriesHeader))
			.withPropertyValues(properties)
			.run(context -> {
				OpenRouterApi api = context.getBean(OpenRouterApi.class);
				// The recording factory sees the fully built request -- after the API's
				// attribution interceptor has added (or omitted) the categories header.
				api.chatCompletion(minimalRequest());
				assertThat(categoriesHeader.get()).isEqualTo(expectedHeader);
			});
	}

	private ChatCompletionRequest minimalRequest() {
		return ChatCompletionRequest.builder()
			.model("m")
			.messages(List.of(new ChatMessage("user", "hi", null, null, null)))
			.stream(false)
			.build();
	}

	@Configuration(proxyBeanMethods = false)
	static class CustomApiConfiguration {

		private final OpenRouterApi customApi = OpenRouterApi.builder().apiKey("user-key").build();

		@Bean
		OpenRouterApi openRouterApi() {
			return this.customApi;
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class CustomCollaboratorsConfiguration {

		@Bean
		ToolExecutionExceptionProcessor toolExecutionExceptionProcessor() {
			return exception -> "custom failure";
		}

		@Bean
		JsonMapper jsonMapper() {
			return new JsonMapper();
		}

		@Bean
		RetryTemplate retryTemplate() {
			return new RetryTemplate();
		}

		@Bean
		ToolCallingManager toolCallingManager(ToolExecutionExceptionProcessor processor) {
			return OpenRouterToolCallingManagers.withFailurePolicy(processor, builder -> {
			});
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class JsonAndOtherFormatMapperConfiguration {

		@Bean
		JsonMapper applicationJsonMapper() {
			return new JsonMapper();
		}

		// Stands in for Boot's XmlMapper or CBORMapper without the extra dataformat
		// dependency: another ObjectMapper bean that is not a JsonMapper.
		@Bean
		ObjectMapper otherFormatMapper() {
			return new ObjectMapper();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class AmbiguousInfrastructureConfiguration {

		@Bean
		RetryTemplate firstRetryTemplate() {
			return new RetryTemplate();
		}

		@Bean
		RetryTemplate secondRetryTemplate() {
			return new RetryTemplate();
		}

		@Bean
		ObservationRegistry firstObservationRegistry() {
			return ObservationRegistry.create();
		}

		@Bean
		ObservationRegistry secondObservationRegistry() {
			return ObservationRegistry.create();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class TwoJsonMappersConfiguration {

		@Bean
		JsonMapper firstJsonMapper() {
			return new JsonMapper();
		}

		@Bean
		JsonMapper secondJsonMapper() {
			return new JsonMapper();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class PlainObjectMapperConfiguration {

		@Bean
		ObjectMapper plainObjectMapper() {
			return new ObjectMapper();
		}

	}

	/**
	 * Installed via a {@link ClientHttpRequestFactoryBuilder} bean so the
	 * auto-configuration's timeout path picks it up. The factory captures the attribution
	 * header off the fully built request into the per-test reference and returns a canned
	 * 200, with no network call.
	 */
	static final class RecordingRequestFactory implements ClientHttpRequestFactory {

		private final AtomicReference<String> categoriesHeader;

		RecordingRequestFactory(AtomicReference<String> categoriesHeader) {
			this.categoriesHeader = categoriesHeader;
		}

		@Override
		public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) {
			return new MockClientHttpRequest(httpMethod, uri) {
				@Override
				protected ClientHttpResponse executeInternal() {
					RecordingRequestFactory.this.categoriesHeader.set(getHeaders().getFirst("X-OpenRouter-Categories"));
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
