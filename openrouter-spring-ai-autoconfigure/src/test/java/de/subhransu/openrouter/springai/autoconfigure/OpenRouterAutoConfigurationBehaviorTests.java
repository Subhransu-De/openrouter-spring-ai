package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterToolCallingManagers;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
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
 * custom collaborator integration ({@link ObjectMapper}, {@link RetryTemplate},
 * {@link ToolCallingManager}), and the attribution-categories comma-join contract
 * observed on a real outgoing request. The full property matrix lives in
 * {@code OpenRouterAutoConfigurationPropertyMatrixTests}.
 */
class OpenRouterAutoConfigurationBehaviorTests {

	private static final String API_KEY = "spring.ai.openrouter.api-key=test-key";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withConfiguration(
			AutoConfigurations.of(OpenRouterApiAutoConfiguration.class, OpenRouterChatAutoConfiguration.class));

	// ---------------------------------------------------------------------
	// Bean back-off and custom collaborators
	// ---------------------------------------------------------------------

	@Test
	void backsOffWhenUserDefinesOwnApiBean() {
		contextRunner.withUserConfiguration(CustomApiConfiguration.class).withPropertyValues(API_KEY).run(context -> {
			assertThat(context).hasSingleBean(OpenRouterApi.class);
			assertThat(context.getBean(OpenRouterApi.class))
				.isSameAs(context.getBean(CustomApiConfiguration.class).customApi);
		});
	}

	@Test
	void usesCustomObjectMapperRetryTemplateAndToolCallingManager() {
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
					.isSameAs(context.getBean(ObjectMapper.class));
			});
	}

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

	@Test
	void multipleCategoriesAreJoinedWithCommasOnTheWire() {
		assertCategoriesHeader(new String[] { "spring.ai.openrouter.app.categories[0]=translation",
				"spring.ai.openrouter.app.categories[1]=programming" }, "translation,programming");
	}

	@Test
	void singleCategoryIsSentVerbatim() {
		assertCategoriesHeader(new String[] { "spring.ai.openrouter.app.categories[0]=samples" }, "samples");
	}

	@Test
	void absentCategoriesOmitTheHeader() {
		assertCategoriesHeader(new String[0], null);
	}

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
		return new ChatCompletionRequest("m", null, List.of(new ChatMessage("user", "hi", null, null, null)), null,
				null, null, null, null, null, null, null, null, null, null, null, null, false, null, null, null, null,
				null, null, null, null, null, null, null, null);
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
		ObjectMapper objectMapper() {
			return new ObjectMapper();
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
