package de.subhransu.openrouter.springai.api.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestBuilderTests {

	private static final List<ChatMessage> MESSAGES = List.of(new ChatMessage("user", "hi", null, null, null));

	private static final ProviderPreferences PROVIDER = new ProviderPreferences(true, null, null, null, null, null,
			null);

	private static final ReasoningOptions REASONING = new ReasoningOptions("high", null, null, null);

	private static final Map<String, Object> EXTRA_BODY = Map.of("prompt_cache_key", "key");

	// Every value is distinct, so a builder method wired to the wrong component makes the
	// records unequal. Same-typed neighbours such as the Double sampling fields are the
	// ones a positional call can swap silently.
	@Test
	void chatBuilderSetsEveryComponent() {
		List<Tool> tools = List.of(new Tool("function", new Function("lookup", null, null)));
		ChatCompletionRequest expected = new ChatCompletionRequest("model", List.of("fallback"), MESSAGES, 0.1, 0.2, 3,
				0.4, 0.5, 0.6, 0.7, 0.8, 9, 10, List.of("stop"), 11, "user", true, Map.of("type", "json_object"), tools,
				"auto", false, PROVIDER, REASONING, "flex", Map.of("trace", "t"), "fallback", new UsageConfig(true),
				List.of("text"), Map.of("aspect_ratio", "1:1"), new AudioConfig("alloy", "wav"), EXTRA_BODY);

		ChatCompletionRequest built = ChatCompletionRequest.builder()
			.model("model")
			.models(List.of("fallback"))
			.messages(MESSAGES)
			.temperature(0.1)
			.topP(0.2)
			.topK(3)
			.frequencyPenalty(0.4)
			.presencePenalty(0.5)
			.repetitionPenalty(0.6)
			.minP(0.7)
			.topA(0.8)
			.maxTokens(9)
			.maxCompletionTokens(10)
			.stop(List.of("stop"))
			.seed(11)
			.user("user")
			.stream(true)
			.responseFormat(Map.of("type", "json_object"))
			.tools(tools)
			.toolChoice("auto")
			.parallelToolCalls(false)
			.provider(PROVIDER)
			.reasoning(REASONING)
			.serviceTier("flex")
			.metadata(Map.of("trace", "t"))
			.route("fallback")
			.usage(new UsageConfig(true))
			.modalities(List.of("text"))
			.imageConfig(Map.of("aspect_ratio", "1:1"))
			.audio(new AudioConfig("alloy", "wav"))
			.extraBody(EXTRA_BODY)
			.build();

		assertThat(built).isEqualTo(expected);
	}

	@Test
	void responsesBuilderSetsEveryComponent() {
		List<ResponsesTool> tools = List.of(new ResponsesTool("function", "lookup", null, null, null));
		ResponsesRequest expected = new ResponsesRequest("model", List.of("fallback"), "input", "instructions", 5, true,
				0.1, 0.2, 3, 0.4, 0.5, Map.of("trace", "t"), PROVIDER, REASONING, "fallback", "flex", "user", false,
				"auto", tools, List.of("text"), Map.of("aspect_ratio", "1:1"), Map.of("verbosity", "low"), EXTRA_BODY);

		ResponsesRequest built = ResponsesRequest.builder()
			.model("model")
			.models(List.of("fallback"))
			.input("input")
			.instructions("instructions")
			.maxOutputTokens(5)
			.stream(true)
			.temperature(0.1)
			.topP(0.2)
			.topK(3)
			.frequencyPenalty(0.4)
			.presencePenalty(0.5)
			.metadata(Map.of("trace", "t"))
			.provider(PROVIDER)
			.reasoning(REASONING)
			.route("fallback")
			.serviceTier("flex")
			.user("user")
			.parallelToolCalls(false)
			.toolChoice("auto")
			.tools(tools)
			.modalities(List.of("text"))
			.imageConfig(Map.of("aspect_ratio", "1:1"))
			.text(Map.of("verbosity", "low"))
			.extraBody(EXTRA_BODY)
			.build();

		assertThat(built).isEqualTo(expected);
	}

	@Test
	void buildersRejectMissingRequiredField() {
		assertThatNullPointerException().isThrownBy(() -> ChatCompletionRequest.builder().model("m").build())
			.withMessage("messages must be set");
		assertThatNullPointerException().isThrownBy(() -> ResponsesRequest.builder().model("m").build())
			.withMessage("input must be set");
	}

}
