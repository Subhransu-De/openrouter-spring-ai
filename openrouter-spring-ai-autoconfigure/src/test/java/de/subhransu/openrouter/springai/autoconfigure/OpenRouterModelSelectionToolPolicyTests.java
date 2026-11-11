package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.Delta;
import de.subhransu.openrouter.springai.api.dto.FunctionCall;
import de.subhransu.openrouter.springai.api.dto.ResponsesContent;
import de.subhransu.openrouter.springai.api.dto.ResponsesFunctionCallOutput;
import de.subhransu.openrouter.springai.api.dto.ResponsesOutputItem;
import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.api.dto.ToolCall;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterToolCallingManagers;
import de.subhransu.openrouter.springai.chat.OpenRouterToolExecutionExceptionProcessor;
import de.subhransu.openrouter.springai.chat.OpenRouterToolFailurePolicy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class OpenRouterModelSelectionToolPolicyTests {

	private static final String AUTO_CONFIGURED_MODEL = "openRouterChatModel";

	private static final String OUTPUT_ITEM_DONE = "response.output_item.done";

	private static final String RESPONSE_COMPLETED = "response.completed";

	static Stream<Arguments> scenarios() {
		return Stream.of(OpenRouterRequestMode.values())
			.flatMap(mode -> Stream.of(
					scenario("default policy", Setup.NOTHING, mode, AUTO_CONFIGURED_MODEL,
							new Expectation(1, true, 1, 1)),
					scenario("declared policy", Setup.PROCESSOR_AND_POLICY_MANAGER, mode, AUTO_CONFIGURED_MODEL,
							new Expectation(1, true, 1, 0)),
					scenario("custom policy manager", Setup.PROCESSOR_AND_CUSTOM_MANAGER, mode, AUTO_CONFIGURED_MODEL,
							new Expectation(1, true, 1, 0)),
					scenario("application models, OpenRouter one", Setup.APPLICATION_MODELS, mode,
							"applicationOpenRouter", new Expectation(2, false, 0, 0)),
					scenario("application models, other one", Setup.APPLICATION_MODELS, mode, "otherChatModel",
							new Expectation(2, false, 0, 0))));
	}

	private static Arguments scenario(String name, Setup setup, OpenRouterRequestMode mode, String modelName,
			Expectation expectation) {
		return Arguments
			.of(Named.of(name + ", " + mode + ", " + modelName, new Scenario(setup, mode, modelName, expectation)));
	}

	@ParameterizedTest
	@MethodSource("scenarios")
	void selectedPolicySurvivesSyncAndStreamingAdvisorCalls(Scenario scenario) {
		OpenRouterApi api = syntheticApi();
		Setup setup = scenario.setup();
		ToolExecutionExceptionProcessor processor = exception -> setup.failurePayload();
		ToolCallingManager manager = setup.manager(processor);
		ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(
					AutoConfigurations.of(OpenRouterApiAutoConfiguration.class, OpenRouterChatAutoConfiguration.class,
							OpenRouterToolCallingAutoConfiguration.class, ToolCallingAutoConfiguration.class))
			.withPropertyValues("spring.ai.model.chat=openrouter", "spring.ai.model.embedding=none",
					"spring.ai.model.image=none", "spring.ai.openrouter.chat.request-mode=" + scenario.mode())
			.withBean(OpenRouterApi.class, () -> api);
		Expectation expectation = scenario.expectation();
		setup.declare(runner, new Application(api, scenario.mode(), processor, manager)).run(context -> {
			assertThat(context).hasNotFailed()
				.hasSingleBean(OpenRouterApi.class)
				.hasSingleBean(ToolCallingManager.class)
				.hasSingleBean(ToolExecutionExceptionProcessor.class)
				.hasSingleBean(OpenRouterChatModel.class);
			assertThat(context.getBeansOfType(ChatModel.class)).as("chat models").hasSize(expectation.chatModelCount());
			assertThat(context.containsBean(AUTO_CONFIGURED_MODEL)).as("auto-configured model")
				.isEqualTo(expectation.autoConfiguredModel());
			assertThat(context.getBeanNamesForType(OpenRouterToolCallingManagerGuard.class)).as("guards")
				.hasSize(expectation.guardCount());
			assertThat(context.getBeanNamesForType(OpenRouterToolExecutionExceptionProcessor.class))
				.as("default failure processors")
				.hasSize(expectation.defaultProcessorCount());
			setup.assertBeans(context, processor, manager);
			String modelName = scenario.modelName();
			ChatModel model = context.getBean(modelName, ChatModel.class);
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
			assertThat(client.prompt(prompt).call().content()).as(modelName).isEqualTo(setup.failurePayload());
			// The stream is finite and in memory, so the bound only keeps a hung stream
			// from hanging the build.
			StepVerifier.create(client.prompt(prompt).stream().content())
				.expectNext(setup.failurePayload())
				.expectComplete()
				.verify(Duration.ofSeconds(5));
			assertThat(executions).hasValue(2);
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
		when(api.responses(any())).thenAnswer(invocation -> responsesResult(invocation.getArgument(0)));
		when(api.responsesStream(any())).thenAnswer(invocation -> {
			ResponsesResult result = responsesResult(invocation.getArgument(0));
			ResponsesOutputItem item = result.output().get(0);
			Flux<ResponsesStreamEvent> completed = Flux
				.just(new ResponsesStreamEvent(RESPONSE_COMPLETED, null, null, result, null));
			return "function_call".equals(item.type())
					? Flux.just(new ResponsesStreamEvent(OUTPUT_ITEM_DONE, null, item, null, null))
						.concatWith(completed)
					: completed;
		});
		return api;
	}

	// A Responses round: the tool call until the request carries a tool result, then a
	// message that echoes that result.
	private static ResponsesResult responsesResult(ResponsesRequest request) {
		ResponsesOutputItem item = ((List<?>) request.input()).stream()
			.filter(ResponsesFunctionCallOutput.class::isInstance)
			.map(ResponsesFunctionCallOutput.class::cast)
			.findFirst()
			.map(toolResult -> new ResponsesOutputItem("msg", "message", "completed", "assistant",
					List.of(new ResponsesContent("output_text", toolResult.output()))))
			.orElseGet(() -> new ResponsesOutputItem("fc-1", "function_call", "completed", null, null, "call-1", "fail",
					"{}", null));
		return new ResponsesResult("resp", "response", 1L, "test-model", "completed", List.of(item), null, null);
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

	/** What the application declares besides the auto-configuration. */
	enum Setup {

		NOTHING {
			@Override
			String failurePayload() {
				return OpenRouterToolExecutionExceptionProcessor.DEFAULT_FAILURE_PAYLOAD;
			}

			@Override
			ApplicationContextRunner declare(ApplicationContextRunner runner, Application application) {
				return runner;
			}

			@Override
			void assertBeans(AssertableApplicationContext context, ToolExecutionExceptionProcessor processor,
					ToolCallingManager manager) {
				// The application declared nothing, so its unregistered instances stay
				// unused.
				assertThat(context.getBean(ToolCallingManager.class)).isNotSameAs(manager);
				assertThat(context.getBean(ToolExecutionExceptionProcessor.class)).isNotSameAs(processor);
			}
		},
		PROCESSOR_AND_POLICY_MANAGER {
			@Override
			ToolCallingManager manager(ToolExecutionExceptionProcessor processor) {
				return OpenRouterToolCallingManagers.withFailurePolicy(processor, builder -> {
				});
			}
		},
		PROCESSOR_AND_CUSTOM_MANAGER {
			@Override
			ToolCallingManager manager(ToolExecutionExceptionProcessor processor) {
				return new PolicyManager(processor);
			}
		},
		APPLICATION_MODELS {
			@Override
			ApplicationContextRunner declare(ApplicationContextRunner runner, Application application) {
				return super.declare(runner, application)
					.withBean("otherChatModel", ChatModel.class, EchoToolModel::new)
					.withBean("applicationOpenRouter", OpenRouterChatModel.class,
							() -> OpenRouterChatModel.builder()
								.openRouterApi(application.api())
								.toolCallingManager(application.manager())
								.defaultOptions(OpenRouterChatOptions.builder().requestMode(application.mode()).build())
								.build());
			}
		};

		/** What the application's processor returns for a failed tool. */
		String failurePayload() {
			return "application failure";
		}

		ToolCallingManager manager(ToolExecutionExceptionProcessor processor) {
			return OpenRouterToolCallingManagers.withFailurePolicy(processor, builder -> {
			});
		}

		/** Registers the application's beans next to the auto-configuration. */
		ApplicationContextRunner declare(ApplicationContextRunner runner, Application application) {
			return runner.withBean(ToolExecutionExceptionProcessor.class, application::processor)
				.withBean(ToolCallingManager.class, application::manager);
		}

		/** The application's own manager and processor stay in place. */
		void assertBeans(AssertableApplicationContext context, ToolExecutionExceptionProcessor processor,
				ToolCallingManager manager) {
			assertThat(context.getBean(ToolCallingManager.class)).isSameAs(manager);
			assertThat(context.getBean(ToolExecutionExceptionProcessor.class)).isSameAs(processor);
		}

	}

	/** The application's collaborators that a setup registers. */
	record Application(OpenRouterApi api, OpenRouterRequestMode mode, ToolExecutionExceptionProcessor processor,
			ToolCallingManager manager) {

	}

	/**
	 * The facts that must hold for a setup: the auto-configured model and the guard exist
	 * unless the application declares its own OpenRouter model; the default processor
	 * exists only when the application declares no processor.
	 */
	record Expectation(int chatModelCount, boolean autoConfiguredModel, int guardCount, int defaultProcessorCount) {

	}

	record Scenario(Setup setup, OpenRouterRequestMode mode, String modelName, Expectation expectation) {

	}

}
