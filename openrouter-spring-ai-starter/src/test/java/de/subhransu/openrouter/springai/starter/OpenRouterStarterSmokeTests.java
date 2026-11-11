package de.subhransu.openrouter.springai.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.ResponsesContent;
import de.subhransu.openrouter.springai.api.dto.ResponsesOutputItem;
import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterApiAutoConfiguration;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterChatAutoConfiguration;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterEmbeddingAutoConfiguration;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterImageAutoConfiguration;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterToolCallingAutoConfiguration;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingModel;
import de.subhransu.openrouter.springai.image.OpenRouterImageModel;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.util.backoff.BackOffExecution;

/**
 * Smoke test for the starter module. The starter declares no code of its own; its job is
 * to drag in the autoconfigure module so the OpenRouter beans appear on the classpath and
 * auto-configure. This test proves that wiring without any real OpenRouter network call.
 */
class OpenRouterStarterSmokeTests {

	private static final String API_KEY_PROPERTY = "spring.ai.openrouter.api-key=test-key";

	private static final String[] MODEL_PROPERTIES = { "spring.ai.openrouter.embedding.model=synthetic/embedding",
			"spring.ai.openrouter.image.model=synthetic/image" };

	private static final String APPLICATION_TOOL = "application_tool";

	private static final String MANAGER_TOOL = "manager_resolved_tool";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withConfiguration(
			AutoConfigurations.of(SpringAiRetryAutoConfiguration.class, ToolCallingAutoConfiguration.class,
					ChatMemoryAutoConfiguration.class, OpenRouterApiAutoConfiguration.class,
					OpenRouterChatAutoConfiguration.class, OpenRouterEmbeddingAutoConfiguration.class,
					OpenRouterImageAutoConfiguration.class, ChatClientAutoConfiguration.class));

	@Test
	void ownImportsFileListsExactlyTheOpenRouterAutoConfigurationsAndTheStarterBringsSpringAiRetry()
			throws IOException {
		Enumeration<URL> imports = getClass().getClassLoader()
			.getResources("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
		List<String> openRouterEntries = new ArrayList<>();
		while (imports.hasMoreElements()) {
			try (InputStream in = imports.nextElement().openStream()) {
				List<String> entries = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
					.map(String::trim)
					.filter(line -> !line.isEmpty() && !line.startsWith("#"))
					.toList();
				if (entries.contains(OpenRouterApiAutoConfiguration.class.getName())) {
					openRouterEntries.addAll(entries);
				}
			}
		}
		assertThat(openRouterEntries).containsExactlyInAnyOrder(OpenRouterApiAutoConfiguration.class.getName(),
				OpenRouterToolCallingAutoConfiguration.class.getName(), OpenRouterChatAutoConfiguration.class.getName(),
				OpenRouterEmbeddingAutoConfiguration.class.getName(), OpenRouterImageAutoConfiguration.class.getName());
		// The starter's own dependencies bring the Spring AI modules that the models rely
		// on.
		assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
			.contains(SpringAiRetryAutoConfiguration.class.getName(), ToolCallingAutoConfiguration.class.getName());
	}

	@Test
	void theStarterShipsNoCodeOfItsOwn() throws IOException {
		// A published library artifact stays thin: the starter only bundles dependencies.
		// Its main source set holds no Java type beyond package documentation.
		try (Stream<Path> sources = Files.walk(moduleRoot().resolve("src").resolve("main"))) {
			assertThat(sources.map(path -> path.getFileName().toString())
				.filter(name -> name.endsWith(".java") && !name.equals("package-info.java"))).isEmpty();
		}
	}

	@Test
	void responsesModeSendsTheRequestToTheResponsesEndpointThroughTheStarterWiring() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		ResponsesOutputItem message = new ResponsesOutputItem("msg", "message", "completed", "assistant",
				List.of(new ResponsesContent("output_text", "ok")));
		when(api.responses(any()))
			.thenReturn(new ResponsesResult("resp", "response", 1L, "m", "completed", List.of(message), null, null));
		this.contextRunner.withBean(OpenRouterApi.class, () -> api)
			.withPropertyValues(API_KEY_PROPERTY, "spring.ai.openrouter.chat.request-mode=OPENAI_RESPONSES")
			.run(context -> {
				assertThat(context).hasNotFailed();

				ChatResponse response = context.getBean(OpenRouterChatModel.class).call(new Prompt("hi"));

				assertThat(response.getResult().getOutput().getText()).isEqualTo("ok");
				ArgumentCaptor<ResponsesRequest> request = ArgumentCaptor.forClass(ResponsesRequest.class);
				verify(api).responses(request.capture());
				verify(api, never()).chatCompletion(any());
				assertThat(String.valueOf(request.getValue().input())).contains("hi");
			});
	}

	@Test
	void retryPropertiesReachTheOneTemplateSharedByEveryBlockingModel() {
		OpenRouterApi api = failingApi();
		// Millisecond backoff keeps the real waits of the retried calls negligible.
		this.contextRunner.withBean(OpenRouterApi.class, () -> api)
			.withPropertyValues(API_KEY_PROPERTY, MODEL_PROPERTIES[0], MODEL_PROPERTIES[1],
					"spring.ai.retry.max-attempts=2", "spring.ai.retry.backoff.initial-interval=1ms",
					"spring.ai.retry.backoff.multiplier=2", "spring.ai.retry.backoff.max-interval=2ms")
			.run(context -> {
				assertThat(context).hasSingleBean(RetryTemplate.class);
				BackOffExecution backOff = context.getBean(RetryTemplate.class).getRetryPolicy().getBackOff().start();
				assertThat(List.of(backOff.nextBackOff(), backOff.nextBackOff(), backOff.nextBackOff()))
					.containsExactly(1L, 2L, BackOffExecution.STOP);

				// Every model retries the transient failure as that template allows: the
				// first attempt plus two retries.
				for (ModelCall call : modelCalls()) {
					clearInvocations(api);
					assertThatThrownBy(() -> call.accept(context)).isInstanceOf(TransientAiException.class);
					assertThat(mockingDetails(api).getInvocations()).as(call.name()).hasSize(3);
				}
			});
	}

	@Test
	void userRetryTemplateOverridesTheStandardAutoConfiguredBean() {
		OpenRouterApi api = failingApi();
		this.contextRunner.withUserConfiguration(UserRetryConfiguration.class)
			.withBean(OpenRouterApi.class, () -> api)
			.withPropertyValues(API_KEY_PROPERTY, MODEL_PROPERTIES[0], MODEL_PROPERTIES[1],
					"spring.ai.retry.max-attempts=9")
			.run(context -> {
				assertThat(context).hasSingleBean(RetryTemplate.class);
				assertThat(context.getBean(RetryTemplate.class)).isSameAs(context.getBean("userRetryTemplate"));
				// The user template never retries; the property template would retry.
				for (ModelCall call : modelCalls()) {
					clearInvocations(api);
					assertThatThrownBy(() -> call.accept(context)).isInstanceOf(TransientAiException.class);
					assertThat(mockingDetails(api).getInvocations()).as(call.name()).hasSize(1);
				}
			});
	}

	@Test
	void minimalContextStartsWithApiKeyAndNoNetworkCall() {
		OpenRouterApi api = succeedingChatApi();
		this.contextRunner.withBean(OpenRouterApi.class, () -> api)
			.withPropertyValues(API_KEY_PROPERTY)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(OpenRouterApi.class);
				assertThat(context).hasSingleBean(OpenRouterChatModel.class);
				assertThat(context).hasSingleBean(ToolCallingManager.class);
				assertThat(context).hasSingleBean(ChatMemoryRepository.class);
				assertThat(context).hasSingleBean(ChatMemory.class);
				assertThat(context.getBeanNamesForType(ChatClient.Builder.class)).hasSize(1);
				assertThat(context.getBean(ChatClient.Builder.class))
					.isNotSameAs(context.getBean(ChatClient.Builder.class));

				// The chat model resolves tool definitions through the auto-configured
				// manager.
				context.getBean(OpenRouterChatModel.class)
					.call(new Prompt("hi", OpenRouterChatOptions.builder().toolCallbacks(applicationTool()).build()));
				ArgumentCaptor<ChatCompletionRequest> request = ArgumentCaptor.forClass(ChatCompletionRequest.class);
				verify(api).chatCompletion(request.capture());
				assertThat(request.getValue().tools()).singleElement()
					.satisfies(tool -> assertThat(tool.function().name()).isEqualTo(APPLICATION_TOOL));
			});
	}

	@Test
	void userOwnedToolCallingManagerIsTheOneTheChatModelUses() {
		OpenRouterApi api = succeedingChatApi();
		this.contextRunner.withUserConfiguration(UserInfrastructureConfiguration.class)
			.withBean(OpenRouterApi.class, () -> api)
			.withPropertyValues(API_KEY_PROPERTY)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(ToolCallingManager.class);
				context.getBean(OpenRouterChatModel.class)
					.call(new Prompt("hi", OpenRouterChatOptions.builder().toolCallbacks(applicationTool()).build()));
				ArgumentCaptor<ChatCompletionRequest> request = ArgumentCaptor.forClass(ChatCompletionRequest.class);
				verify(api).chatCompletion(request.capture());
				assertThat(request.getValue().tools()).singleElement()
					.satisfies(tool -> assertThat(tool.function().name()).isEqualTo(MANAGER_TOOL));
			});
	}

	private static Path moduleRoot() {
		try {
			// Resolved from the test class, not the working directory, so the check does
			// not
			// depend on how Maven or Gradle launches the tests.
			Path directory = Path
				.of(OpenRouterStarterSmokeTests.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			while (!Files.isDirectory(directory.resolve("src").resolve("main"))) {
				directory = directory.getParent();
				assertThat(directory).as("module root above the test classes").isNotNull();
			}
			return directory;
		}
		catch (URISyntaxException ex) {
			throw new IllegalStateException("The test class location is not a file path", ex);
		}
	}

	private static OpenRouterApi succeedingChatApi() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		ChatMessage reply = new ChatMessage("assistant", "ok", null, null, null);
		when(api.chatCompletion(any())).thenReturn(new ChatCompletionResponse("id", "chat.completion", 1L, "m", "p",
				List.of(new Choice(0, reply, null, "stop", null)), null));
		return api;
	}

	private static OpenRouterApi failingApi() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		TransientAiException failure = new TransientAiException("synthetic transient failure");
		when(api.chatCompletion(any())).thenThrow(failure);
		when(api.embeddings(any())).thenThrow(failure);
		when(api.images(any())).thenThrow(failure);
		return api;
	}

	private static ToolCallback applicationTool() {
		return FunctionToolCallback.builder(APPLICATION_TOOL, (Map<String, Object> input) -> "ok")
			.description("Resolved by the application manager")
			.inputType(Map.class)
			.build();
	}

	private static List<ModelCall> modelCalls() {
		return List.of(
				new ModelCall("chat model",
						context -> context.getBean(OpenRouterChatModel.class).call(new Prompt("hi"))),
				new ModelCall("embedding model",
						context -> context.getBean(OpenRouterEmbeddingModel.class).embed("hi")),
				new ModelCall("image model",
						context -> context.getBean(OpenRouterImageModel.class).call(new ImagePrompt("hi"))));
	}

	private record ModelCall(String name, Consumer<AssertableApplicationContext> action) {

		void accept(AssertableApplicationContext context) {
			this.action.accept(context);
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class UserInfrastructureConfiguration {

		@Bean
		ToolCallingManager userToolCallingManager() {
			return new ToolCallingManager() {
				@Override
				public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
					return List.of(ToolDefinition.builder()
						.name(MANAGER_TOOL)
						.description("Defined by the application manager")
						.inputSchema("{\"type\":\"object\"}")
						.build());
				}

				@Override
				public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
					throw new UnsupportedOperationException("models do not execute tools");
				}
			};
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class UserRetryConfiguration {

		@Bean
		RetryTemplate userRetryTemplate() {
			return new RetryTemplate(RetryPolicy.withMaxRetries(0));
		}

	}

}
