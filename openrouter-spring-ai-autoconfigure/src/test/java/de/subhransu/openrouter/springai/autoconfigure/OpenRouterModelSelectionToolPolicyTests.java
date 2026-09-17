package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.Delta;
import de.subhransu.openrouter.springai.api.dto.FunctionCall;
import de.subhransu.openrouter.springai.api.dto.ToolCall;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterToolExecutionExceptionProcessor;
import de.subhransu.openrouter.springai.chat.OpenRouterToolFailurePolicy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.BeanFactoryAnnotationUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class OpenRouterModelSelectionToolPolicyTests {

	@ParameterizedTest
	@ValueSource(strings = { "default", "declared", "custom", "mixed" })
	void selectedPolicySurvivesSyncAndStreamingAdvisorCalls(String policy) {
		OpenRouterApi api = syntheticApi();
		ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(
					AutoConfigurations.of(OpenRouterApiAutoConfiguration.class, OpenRouterChatAutoConfiguration.class,
							OpenRouterToolCallingAutoConfiguration.class, ToolCallingAutoConfiguration.class))
			.withPropertyValues("spring.ai.model.chat=openrouter", "spring.ai.model.embedding=none",
					"spring.ai.model.image=none")
			.withBean(OpenRouterApi.class, () -> api);
		boolean defaultPolicy = "default".equals(policy);
		boolean mixed = "mixed".equals(policy);
		String expected = defaultPolicy ? OpenRouterToolExecutionExceptionProcessor.DEFAULT_FAILURE_PAYLOAD
				: "application failure";
		ToolExecutionExceptionProcessor processor = exception -> expected;
		ToolCallingManager manager = "custom".equals(policy) ? new PolicyManager(processor)
				: ToolCallingManager.builder().toolExecutionExceptionProcessor(processor).build();
		if (!defaultPolicy) {
			runner = runner.withBean(ToolExecutionExceptionProcessor.class, () -> processor)
				.withBean(ToolCallingManager.class, () -> manager);
		}
		if (mixed) {
			runner = runner.withBean("otherChatModel", ChatModel.class, EchoToolModel::new)
				.withBean("applicationOpenRouter", OpenRouterChatModel.class,
						() -> OpenRouterChatModel.builder().openRouterApi(api).toolCallingManager(manager).build());
		}
		runner.run(context -> {
			assertThat(context).hasNotFailed()
				.hasSingleBean(OpenRouterApi.class)
				.hasSingleBean(ToolCallingManager.class)
				.hasSingleBean(ToolExecutionExceptionProcessor.class)
				.hasSingleBean(OpenRouterChatModel.class);
			assertThat(context.getBeansOfType(ChatModel.class)).hasSize(mixed ? 2 : 1);
			if (mixed) {
				assertThat(context).doesNotHaveBean("openRouterChatModel")
					.doesNotHaveBean(OpenRouterToolCallingManagerGuard.class);
			}
			else {
				assertThat(context).hasSingleBean(OpenRouterToolCallingManagerGuard.class);
			}
			if (defaultPolicy) {
				assertThat(context).hasSingleBean(OpenRouterToolExecutionExceptionProcessor.class);
			}
			else {
				assertThat(context).doesNotHaveBean(OpenRouterToolExecutionExceptionProcessor.class);
				assertThat(context.getBean(ToolCallingManager.class)).isSameAs(manager);
				assertThat(context.getBean(ToolExecutionExceptionProcessor.class)).isSameAs(processor);
			}
			List<String> modelNames = mixed ? List.of("applicationOpenRouter", "otherChatModel")
					: List.of("openRouterChatModel");
			for (String modelName : modelNames) {
				ChatModel model = BeanFactoryAnnotationUtils.qualifiedBeanOfType(context.getBeanFactory(),
						ChatModel.class, modelName);
				assertThat(model).isSameAs(context.getBean(modelName));
				AtomicInteger executions = new AtomicInteger();
				ToolCallback callback = FunctionToolCallback.builder("fail", (Map<String, Object> input) -> {
					executions.incrementAndGet();
					throw new IllegalStateException("synthetic private detail");
				}).description("Synthetic failure").inputType(Map.class).build();
				ChatClient client = ChatClient.builder(model)
					.defaultAdvisors(ToolCallingAdvisor.builder()
						.toolCallingManager(context.getBean(ToolCallingManager.class))
						.build())
					.build();
				Prompt prompt = new Prompt("test",
						ToolCallingChatOptions.builder().toolCallbacks(List.of(callback)).build());
				assertThat(client.prompt(prompt).call().content()).as(modelName).isEqualTo(expected);
				StepVerifier.create(client.prompt(prompt).stream().content())
					.expectNext(expected)
					.expectComplete()
					.verify(Duration.ofSeconds(5));
				assertThat(executions).hasValue(2);
			}
		});
	}

	private static OpenRouterApi syntheticApi() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletion(any())).thenAnswer(invocation -> {
			ChatMessage message = reply(invocation.getArgument(0));
			return new ChatCompletionResponse("test", "chat.completion", 1L, "test-model", "test-provider",
					List.of(new Choice(0, message, null, message.toolCalls() == null ? "stop" : "tool_calls", null)),
					null);
		});
		when(api.chatCompletionStream(any())).thenAnswer(invocation -> {
			ChatMessage message = reply(invocation.getArgument(0));
			return Flux.just(new ChatCompletionChunk("test", "chat.completion.chunk", 1L, "test-model", "test-provider",
					List.of(new Choice(0, null,
							new Delta("assistant", (String) message.content(), null, message.toolCalls()),
							message.toolCalls() == null ? "stop" : "tool_calls", null)),
					null, null));
		});
		return api;
	}

	private static ChatMessage reply(ChatCompletionRequest request) {
		return request.messages()
			.stream()
			.filter(message -> "tool".equals(message.role()))
			.findFirst()
			.map(message -> new ChatMessage("assistant", message.content(), null, null, null))
			.orElseGet(() -> new ChatMessage("assistant", "", null, null,
					List.of(new ToolCall("call-1", "function", new FunctionCall("fail", "{}")))));
	}

	private static final class EchoToolModel implements ChatModel {

		@Override
		public ToolCallingChatOptions getOptions() {
			return ToolCallingChatOptions.builder().build();
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			AssistantMessage response = prompt.getInstructions()
				.stream()
				.filter(ToolResponseMessage.class::isInstance)
				.map(ToolResponseMessage.class::cast)
				.findFirst()
				.map(message -> new AssistantMessage(message.getResponses().get(0).responseData()))
				.orElseGet(() -> AssistantMessage.builder()
					.content("")
					.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "fail", "{}")))
					.build());
			return new ChatResponse(List.of(new Generation(response)));
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			return Flux.defer(() -> Flux.just(call(prompt)));
		}

	}

	private static final class PolicyManager implements ToolCallingManager, OpenRouterToolFailurePolicy {

		private final ToolExecutionExceptionProcessor processor;

		private final ToolCallingManager delegate;

		PolicyManager(ToolExecutionExceptionProcessor processor) {
			this.processor = processor;
			this.delegate = ToolCallingManager.builder().toolExecutionExceptionProcessor(processor).build();
		}

		@Override
		public ToolExecutionExceptionProcessor toolExecutionExceptionProcessor() {
			return this.processor;
		}

		@Override
		public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
			return this.delegate.resolveToolDefinitions(options);
		}

		@Override
		public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
			return this.delegate.executeToolCalls(prompt, response);
		}

	}

}
