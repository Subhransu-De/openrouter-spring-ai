package de.subhransu.openrouter.springai.api.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class RequestBuilderTests {

	private static final List<ChatMessage> MESSAGES = List.of(new ChatMessage("user", "hi", null, null, null));

	private static final ProviderPreferences PROVIDER = new ProviderPreferences(true, false, "deny", List.of("openai"),
			List.of("anthropic"), List.of("fp16"), "throughput");

	private static final ReasoningOptions REASONING = new ReasoningOptions("high", 1024, false, true);

	private static final Map<String, Object> EXTRA_BODY = Map.of("prompt_cache_key", "key");

	private static final String CHAT_WIRE = """
			{"model":"model","models":["fallback"],"messages":[{"role":"user","content":"hi"}],
			 "temperature":0.1,"top_p":0.2,"top_k":3,"frequency_penalty":0.4,"presence_penalty":0.5,
			 "repetition_penalty":0.6,"min_p":0.7,"top_a":0.8,"max_tokens":9,"max_completion_tokens":10,
			 "stop":["stop"],"seed":11,"user":"user","stream":true,"response_format":{"type":"json_object"},
			 "tools":[{"type":"function","function":{"name":"lookup"}}],"tool_choice":"auto",
			 "parallel_tool_calls":false,"provider":{"allow_fallbacks":true,"require_parameters":false,"data_collection":"deny",
			 "order":["openai"],"ignore":["anthropic"],"quantizations":["fp16"],"sort":"throughput"},
			 "reasoning":{"effort":"high","max_tokens":1024,"exclude":false,"enabled":true},
			 "service_tier":"flex","metadata":{"trace":"t"},"route":"fallback","usage":{"include":true},
			 "modalities":["text"],"image_config":{"aspect_ratio":"1:1"},
			 "audio":{"voice":"alloy","format":"wav"},"prompt_cache_key":"key"}
			""";

	private static final String RESPONSES_WIRE = """
			{"model":"model","models":["fallback"],"input":"input","instructions":"instructions",
			 "max_output_tokens":5,"stream":true,"temperature":0.1,"top_p":0.2,"top_k":3,
			 "frequency_penalty":0.4,"presence_penalty":0.5,"metadata":{"trace":"t"},
			 "provider":{"allow_fallbacks":true,"require_parameters":false,"data_collection":"deny",
			 "order":["openai"],"ignore":["anthropic"],"quantizations":["fp16"],"sort":"throughput"},
			 "reasoning":{"effort":"high","max_tokens":1024,"exclude":false,"enabled":true},"route":"fallback",
			 "service_tier":"flex","user":"user","parallel_tool_calls":false,"tool_choice":"auto",
			 "tools":[{"type":"function","name":"lookup"}],"modalities":["text"],
			 "image_config":{"aspect_ratio":"1:1"},"text":{"verbosity":"low"},"prompt_cache_key":"key"}
			""";

	private final ObjectMapper mapper = new ObjectMapper();

	// Every value is distinct, so a builder method wired to the wrong component puts a
	// value under the wrong wire name. Same-typed neighbours such as the Double sampling
	// fields are the ones a positional call can swap silently. The expected document is
	// written independently of the record constructors.
	@Test
	void chatBuilderSetsEveryComponent() {
		List<Tool> tools = List.of(new Tool("function", new Function("lookup", null, null)));
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

		JsonNode wire = this.mapper.valueToTree(built);
		assertThat(wire).isEqualTo(this.mapper.readTree(CHAT_WIRE));
	}

	@Test
	void responsesBuilderSetsEveryComponent() {
		List<ResponsesTool> tools = List.of(new ResponsesTool("function", "lookup", null, null, null));
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

		JsonNode wire = this.mapper.valueToTree(built);
		assertThat(wire).isEqualTo(this.mapper.readTree(RESPONSES_WIRE));
	}

	@ParameterizedTest
	@MethodSource("missingRequiredFields")
	void buildersRejectMissingRequiredField(Runnable build, String message) {
		assertThatNullPointerException().isThrownBy(build::run).withMessage(message);
	}

	static Stream<Arguments> missingRequiredFields() {
		return Stream.of(
				Arguments.of(Named.<Runnable>of("chat completion without messages",
						() -> ChatCompletionRequest.builder().model("m").build()), "messages must be set"),
				Arguments.of(Named.<Runnable>of("responses without input",
						() -> ResponsesRequest.builder().model("m").build()), "input must be set"));
	}

}
