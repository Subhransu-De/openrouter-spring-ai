package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

class OpenRouterExtensionTests {

	private static final String CACHE_KEY = "prompt_cache_key";

	private final ObjectMapper json = new ObjectMapper();

	@Test
	void requestExtensionsAreFlattenedWithFalseZeroAndNullPreserved() {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("logprobs", true);
		fields.put("top_logprobs", 0);
		fields.put(CACHE_KEY, null);
		fields.put("verbosity", "low");
		fields.put("logit_bias", Map.of("42", 0));
		var options = OpenRouterChatOptions.builder().model("synthetic/model").extraBody(fields).build();
		for (boolean stream : List.of(false, true)) {
			var request = new OpenRouterChatRequestMapper(this.json).map(List.of(new UserMessage("synthetic")), options,
					stream, List.of());
			var wire = this.json.readTree(this.json.writeValueAsString(request));
			assertThat(wire.get("logprobs").asBoolean()).isTrue();
			assertThat(wire.get("top_logprobs").asInt()).isZero();
			assertThat(wire.has(CACHE_KEY)).isTrue();
			assertThat(wire.get(CACHE_KEY).isNull()).isTrue();
			assertThat(wire.has("extraBody")).isFalse();
			assertThat(wire.get("logit_bias").get("42").asInt()).isZero();
		}
	}

	@Test
	void extensionsSnapshotNestedContainersAndRuntimeKeysOverrideDefaults() {
		Map<String, Object> bias = new LinkedHashMap<>(Map.of("42", 1));
		Map<String, Object> fields = new LinkedHashMap<>(Map.of("logit_bias", bias, "logprobs", true));
		var defaults = OpenRouterChatOptions.builder().extraBody(fields).build();
		bias.put("42", 99);
		fields.clear();
		Map<String, Object> overrides = new LinkedHashMap<>();
		overrides.put("logprobs", false);
		overrides.put(CACHE_KEY, null);
		var merged = defaults.merge(OpenRouterChatOptions.builder().extraBody(overrides).build());
		assertThat(merged.getExtraBody()).containsEntry("logprobs", false)
			.containsEntry(CACHE_KEY, null)
			.containsEntry("logit_bias", Map.of("42", 1));
		assertThat(defaults.getExtraBody()).containsEntry("logprobs", true);
		assertThatThrownBy(() -> merged.getExtraBody().put("logprobs", true))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void reservedAndUnsupportedKeysCannotBypassEitherEndpoint() {
		for (String key : List.of("model", "messages", "input", "provider", "stream", "tools", "n", "store", "unknown",
				"text")) {
			assertThatThrownBy(() -> OpenRouterChatOptions.builder().extraBody(Map.of(key, false)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining(key);
		}
		for (String key : List.of("logprobs", "logit_bias", "verbosity")) {
			Object value = "logprobs".equals(key) ? false : "verbosity".equals(key) ? "low" : Map.of("42", 0);
			var options = OpenRouterChatOptions.builder().extraBody(Map.of(key, value)).build();
			assertThatThrownBy(
					() -> new OpenRouterResponsesRequestMapper(this.json).map(List.of(), options, false, List.of()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining(key);
		}
		var options = OpenRouterChatOptions.builder().extraBody(Map.of("top_logprobs", 0, CACHE_KEY, "test")).build();
		for (boolean stream : List.of(false, true)) {
			var request = new OpenRouterResponsesRequestMapper(this.json).map(List.of(), options, stream, List.of());
			assertThat(this.json.valueToTree(request).get("top_logprobs").asInt()).isZero();
		}
	}

	@Test
	void routingExtensionsReachBothEndpointsAndRejectSortCollisions() {
		List<String> only = new ArrayList<>(List.of("synthetic-provider"));
		var fields = Map.<String, Object>of("only", only, "zdr", false, "max_price", Map.of("prompt", 0), "sort",
				Map.of("by", "latency", "partition", "none"), "preferred_min_throughput", Map.of("p50", 10),
				"preferred_max_latency", 2);
		var options = OpenRouterChatOptions.builder().providerExtraBody(fields).build();
		only.clear();

		for (boolean stream : List.of(false, true)) {
			Object chat = new OpenRouterChatRequestMapper(this.json).map(List.of(), options, stream, List.of());
			Object responses = new OpenRouterResponsesRequestMapper(this.json).map(List.of(), options, stream,
					List.of());
			for (Object request : List.of(chat, responses)) {
				var wire = this.json.valueToTree(request).get("provider");
				assertThat(wire.get("only").get(0).asString()).isEqualTo("synthetic-provider");
				assertThat(wire.get("sort").get("by").asString()).isEqualTo("latency");
				assertThat(wire.get("zdr").asBoolean()).isFalse();
			}
		}
		assertThatThrownBy(() -> new OpenRouterChatRequestMapper(this.json).map(List.of(),
				options.mutate()
					.provider(new OpenRouterProviderPreferences(null, null, null, null, null, null, "price"))
					.build(),
				false, List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("conflicts");
	}

	@Test
	void unknownResponseFieldsRoundtripAndRemainInspectableWithoutReplay() {
		String fixture = """
				{"model":"synthetic/model","opaque_root":{"enabled":false},"choices":[{"index":0,
				"finish_reason":"tool_calls","logprobs":{"content":[]},"message":{"role":"assistant",
				"content":"synthetic","annotations":[{"type":"synthetic_citation","opaque":0}],
				"future":null,"tool_calls":[{"id":"call-1","type":"function",
				"function":{"name":"lookup","arguments":"{}"},"opaque_tool":{"signature":"synthetic"}}]}}]}
				""";
		var response = this.json.readValue(fixture, ChatCompletionResponse.class);
		var wire = this.json.valueToTree(response);
		assertThat(wire.get("choices").get(0).get("message").get("future").isNull()).isTrue();
		assertThat(wire.get("opaque_root").get("enabled").asBoolean()).isFalse();
		var assistant = new OpenRouterChatResponseMapper().map(response).getResult().getOutput();
		assertThat(assistant.getMetadata()).containsKeys(ExtensionMetadata.MESSAGE, ExtensionMetadata.CHOICE,
				ExtensionMetadata.TOOLS);
		var replay = new OpenRouterChatRequestMapper(this.json).map(List.of(assistant),
				OpenRouterChatOptions.builder().build(), false, List.of());
		assertThat(replay.messages().get(0).extensions()).isEmpty();
		assertThat(replay.messages().get(0).toolCalls().get(0).extensions()).isEmpty();
	}

	@Test
	void responsesAnnotationsAndOpaqueToolFieldsRemainInOutputSnapshots() {
		String fixture = """
				{"status":"completed","output":[{"type":"message","role":"assistant",
				"content":[{"type":"output_text","text":"synthetic","annotations":[{"future":false}]}]},
				{"type":"function_call","call_id":"call-1","name":"lookup","arguments":"{}","future":0}]}
				""";
		var response = this.json.readValue(fixture, ResponsesResult.class);
		var sync = new OpenRouterResponsesResponseMapper().map(response);
		var event = this.json.readValue("{\"type\":\"response.completed\",\"response\":" + fixture + "}",
				ResponsesStreamEvent.class);
		var streaming = new OpenRouterResponsesStreamingResponseMapper().map(event);
		for (var mapped : List.of(sync, streaming)) {
			var snapshot = this.json.valueToTree(
					mapped.getResult().getOutput().getMetadata().get(ReasoningMetadata.RESPONSES_OUTPUT_ITEMS));
			assertThat(snapshot.get(0).get("content").get(0).get("annotations").get(0).get("future").asBoolean())
				.isFalse();
			assertThat(snapshot.get(1).get("future").asInt()).isZero();
		}
	}

	@Test
	void plainTextStreamsRetainEarlierAnnotationsAtCompletion() {
		var stream = new OpenRouterStreamingResponseMapper()
			.map(Flux.just(chunk("{\"content\":\"a\",\"annotations\":[{\"id\":1}]}", null),
					chunk("{\"content\":\"b\",\"annotations\":[{\"id\":2}]}", null), chunk("{}", "stop")));
		StepVerifier.create(stream).expectNextCount(2).assertNext(response -> {
			var metadata = this.json.valueToTree(response.getResult().getOutput().getMetadata());
			assertThat(metadata.get(ExtensionMetadata.MESSAGE).get("annotations").size()).isEqualTo(2);
		}).verifyComplete();
	}

	@Test
	void multipleChoicesKeepMessageAnnotationsSeparateAndRootFieldsOpaque() {
		var first = this.json.readValue("""
				{"annotations":[{"opaque":true}],"choices":[
				{"index":0,"delta":{"content":"a","annotations":[{"id":1}]}},
				{"index":1,"delta":{"content":"b","annotations":[{"id":2}]}}]}
				""", ChatCompletionChunk.class);
		var last = this.json.readValue("""
				{"choices":[{"index":0,"delta":{},"finish_reason":"stop"},
				{"index":1,"delta":{},"finish_reason":"stop"}]}
				""", ChatCompletionChunk.class);
		var stream = new OpenRouterStreamingResponseMapper()
			.map(new OpenRouterStreamingToolCallAggregator().aggregate(Flux.just(first, last)));
		StepVerifier.create(stream).thenConsumeWhile(response -> {
			var root = this.json.valueToTree(response.getMetadata().get(ExtensionMetadata.RESPONSE));
			assertThat(root.get("annotations").size()).isEqualTo(1);
			assertThat(response.getResults()).hasSize(2);
			for (int index = 0; index < 2; index++) {
				var metadata = this.json.valueToTree(response.getResults().get(index).getOutput().getMetadata());
				var annotations = metadata.get(ExtensionMetadata.MESSAGE).get("annotations");
				assertThat(annotations.size()).isEqualTo(1);
				assertThat(annotations.get(0).get("id").asInt()).isEqualTo(index + 1);
			}
			return true;
		}).verifyComplete();
	}

	@Test
	void streamedAnnotationsAndToolExtensionsSurviveAggregationAndResubscription() {
		var first = chunk("""
				{"annotations":[{"id":1}],"tool_calls":[{"index":0,"id":"call-1","type":"function",
				"function":{"name":"lookup","arguments":"{"},"signature":"synthetic"}]}
				""", null);
		var last = chunk("""
				{"annotations":[{"id":2}],"tool_calls":[{"index":0,"function":{"arguments":"}"},"future":0}]}
				""", "tool_calls");
		var stream = new OpenRouterStreamingResponseMapper()
			.map(new OpenRouterStreamingToolCallAggregator().aggregate(Flux.just(first, last)));
		for (int attempt = 0; attempt < 2; attempt++) {
			StepVerifier.create(stream).assertNext(response -> {
				var output = response.getResult().getOutput();
				assertThat(output.getToolCalls().get(0).arguments()).isEqualTo("{}");
				var metadata = this.json.valueToTree(output.getMetadata());
				assertThat(metadata.get(ExtensionMetadata.MESSAGE).get("annotations").size()).isEqualTo(2);
				assertThat(metadata.get(ExtensionMetadata.TOOLS).get("call-1").get("signature").asString())
					.isEqualTo("synthetic");
				assertThat(metadata.get(ExtensionMetadata.TOOLS).get("call-1").get("future").asInt()).isZero();
			}).verifyComplete();
		}
	}

	private ChatCompletionChunk chunk(String delta, String finishReason) {
		return this.json.readValue("{\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":"
				+ this.json.writeValueAsString(finishReason) + "}]}", ChatCompletionChunk.class);
	}

}
