package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Named;
import java.util.stream.Stream;
import java.util.function.BiConsumer;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.test.StepVerifierOptions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class OpenRouterExtensionTests {

	private static final String CACHE_KEY = "prompt_cache_key";

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private final ObjectMapper json = new ObjectMapper();

	private Flux<ChatResponse> cumulativeExtensionStream() {
		Map<String, Object> nested = new LinkedHashMap<>();
		nested.put("flag", false);
		nested.put("zero", 0);
		nested.put("absent", null);
		List<Object> items = new ArrayList<>(List.of(nested));
		Map<String, Object> extensions = new LinkedHashMap<>();
		extensions.put("opaque", items);
		var first = new ChatCompletionChunk(null, null, null, null, null, List.of(), null, null, extensions);
		var empty = this.json.readValue("{\"choices\":[]}", ChatCompletionChunk.class);
		var replacement = this.json.readValue("{\"choices\":[],\"opaque\":null,\"later\":true}",
				ChatCompletionChunk.class);
		nested.clear();
		items.clear();
		extensions.clear();
		return ResponseMappers.chatStream(Flux.just(empty, first, empty, replacement, empty));
	}

	private Map<String, Object> responseExtensions(ChatResponse response) {
		return response.getMetadata().get(ExtensionMetadata.RESPONSE);
	}

	private List<String> responseExtensionsAsJson(List<ChatResponse> responses) {
		return responses.stream().map(response -> this.json.writeValueAsString(responseExtensions(response))).toList();
	}

	@Test
	void responseExtensionsAreDetachedFromTheChunkTheyWereReadFrom() {
		List<ChatResponse> responses = cumulativeExtensionStream().collectList().block(TIMEOUT);

		assertThat(responses).hasSize(5);
		JsonNode snapshot = this.json.valueToTree(responseExtensions(responses.get(1)));
		assertThat(snapshot)
			.isEqualTo(this.json.readTree("{\"opaque\":[{\"flag\":false,\"zero\":0,\"absent\":null}]}"));
	}

	@Test
	void responseExtensionSnapshotsAreImmutableAtEveryNestingLevel() {
		List<ChatResponse> responses = cumulativeExtensionStream().collectList().block(TIMEOUT);

		assertThat(responses).hasSize(5)
			.allSatisfy(response -> assertThatThrownBy(() -> responseExtensions(response).put("changed", true))
				.as("top-level snapshot")
				.isInstanceOf(UnsupportedOperationException.class));
		assertThat(responseExtensions(responses.get(1)).get("opaque")).isInstanceOfSatisfying(List.class, list -> {
			assertThatThrownBy(list::clear).as("nested list").isInstanceOf(UnsupportedOperationException.class);
			assertThat(list.get(0)).isInstanceOfSatisfying(Map.class,
					map -> assertThatThrownBy(map::clear).as("nested map")
						.isInstanceOf(UnsupportedOperationException.class));
		});
	}

	@Test
	void responseExtensionSnapshotsAreCumulativeAndNullReplacementsSurvive() {
		List<String> emittedWhenReceived = new ArrayList<>();

		List<ChatResponse> responses = cumulativeExtensionStream()
			.doOnNext(response -> emittedWhenReceived.add(this.json.writeValueAsString(responseExtensions(response))))
			.collectList()
			.block(TIMEOUT);

		assertThat(responses).hasSize(5);
		assertThat(responseExtensionsAsJson(responses)).as("later chunks never rewrite an emitted snapshot")
			.containsExactlyElementsOf(emittedWhenReceived);
		assertThat(responseExtensions(responses.get(4))).containsEntry("opaque", null).containsEntry("later", true);
	}

	@Test
	void repeatSubscriptionsEmitTheSameCumulativeSnapshots() {
		var stream = cumulativeExtensionStream();

		List<String> first = responseExtensionsAsJson(stream.collectList().block(TIMEOUT));
		List<String> second = responseExtensionsAsJson(stream.collectList().block(TIMEOUT));

		assertThat(first).hasSize(5);
		assertThat(second).containsExactlyElementsOf(first);
	}

	private ChatCompletionChunk logprobChunk(String delta, String logprobs, String finishReason) {
		return this.json.readValue("{\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"logprobs\":" + logprobs
				+ ",\"finish_reason\":" + finishReason + "}]}", ChatCompletionChunk.class);
	}

	private static String tokenLogprobs(int index) {
		return "{\"content\":[{\"token\":\"t" + index + "\",\"logprob\":-1}],\"refusal\":[{\"token\":\"r" + index
				+ "\",\"logprob\":-2}]}";
	}

	private void assertLogprobsAccumulated(Flux<ChatResponse> stream, int expectedResponses) {
		List<ChatResponse> responses = stream.collectList().block(TIMEOUT);

		assertThat(responses).hasSize(expectedResponses);
		var output = responses.get(responses.size() - 1).getResult().getOutput();
		var logprobs = this.json.valueToTree(output.getMetadata().get(ExtensionMetadata.CHOICE)).get("logprobs");
		assertThat(logprobs.get("content").size()).isEqualTo(2);
		assertThat(logprobs.get("content").get(0).get("token").asString()).isEqualTo("t0");
		assertThat(logprobs.get("content").get(1).get("token").asString()).isEqualTo("t1");
		assertThat(logprobs.get("refusal").size()).isEqualTo(2);
	}

	@Test
	void streamedLogprobsAccumulateAcrossContentChunksAndResubscription() {
		var chunks = List.of(logprobChunk("{\"content\":\"synthetic\"}", tokenLogprobs(0), "null"),
				logprobChunk("{\"content\":\"synthetic\"}", tokenLogprobs(1), "null"),
				logprobChunk("{\"content\":\"synthetic\"}", "null", "\"stop\""));
		var stream = ResponseMappers.chatStream(ResponseMappers.aggregator().aggregate(Flux.fromIterable(chunks)));

		assertLogprobsAccumulated(stream, 3);
		assertLogprobsAccumulated(stream, 3);
	}

	@Test
	void streamedLogprobsAccumulateThroughToolBufferingAndResubscription() {
		var chunks = List.of(logprobChunk(
				"{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{\"}}]}",
				tokenLogprobs(0), "null"),
				logprobChunk("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"}\"}}]}", tokenLogprobs(1),
						"null"),
				logprobChunk("{}", "null", "\"tool_calls\""));
		var stream = ResponseMappers.chatStream(ResponseMappers.aggregator().aggregate(Flux.fromIterable(chunks)));

		assertLogprobsAccumulated(stream, 1);
		assertLogprobsAccumulated(stream, 1);
	}

	@ParameterizedTest(name = "stream={0}")
	@ValueSource(booleans = { false, true })
	void requestExtensionsAreFlattenedWithFalseZeroAndNullPreserved(boolean stream) {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("logprobs", true);
		fields.put("top_logprobs", 0);
		fields.put(CACHE_KEY, null);
		fields.put("verbosity", "low");
		fields.put("logit_bias", Map.of("42", 0));
		fields.put("transforms", List.of());
		var options = OpenRouterChatOptions.builder().model("synthetic/model").extraBody(fields).build();
		var request = new OpenRouterChatRequestMapper(this.json).map(List.of(new UserMessage("synthetic")), options,
				stream, List.of());
		var wire = this.json.readTree(this.json.writeValueAsString(request));
		assertThat(wire.get("logprobs").asBoolean()).isTrue();
		assertThat(wire.get("top_logprobs").asInt()).isZero();
		assertThat(wire.has(CACHE_KEY)).isTrue();
		assertThat(wire.get(CACHE_KEY).isNull()).isTrue();
		assertThat(wire.has("extraBody")).isFalse();
		assertThat(wire.get("logit_bias").get("42").asInt()).isZero();
		assertThat(wire.get("transforms").isArray()).isTrue();
		assertThat(wire.get("transforms")).isEmpty();
	}

	@Test
	void transformsRejectNonStringEntries() {
		assertThatThrownBy(() -> OpenRouterChatOptions.builder().extraBody(Map.of("transforms", List.of(1))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("transforms");
	}

	@ParameterizedTest(name = "reserved key {0}")
	@ValueSource(
			strings = { "model", "messages", "input", "provider", "stream", "tools", "n", "store", "unknown", "text" })
	void reservedKeysCannotBeSuppliedAsExtraBody(String key) {
		assertThatThrownBy(() -> OpenRouterChatOptions.builder().extraBody(Map.of(key, false)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining(key);
	}

	static Stream<Arguments> chatOnlyExtensions() {
		return Stream.of(Arguments.of("logprobs", false), Arguments.of("logit_bias", Map.of("42", 0)),
				Arguments.of("verbosity", "low"), Arguments.of("transforms", List.of()));
	}

	@ParameterizedTest(name = "{0} is chat-only")
	@MethodSource("chatOnlyExtensions")
	void chatOnlyExtensionsAreRejectedOnTheResponsesEndpoint(String key, Object value) {
		var options = OpenRouterChatOptions.builder().extraBody(Map.of(key, value)).build();
		assertThatThrownBy(
				() -> new OpenRouterResponsesRequestMapper(this.json).map(List.of(), options, false, List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining(key);
	}

	@ParameterizedTest(name = "stream={0}")
	@ValueSource(booleans = { false, true })
	void sharedExtensionsReachTheResponsesWire(boolean stream) {
		var options = OpenRouterChatOptions.builder().extraBody(Map.of("top_logprobs", 0, CACHE_KEY, "test")).build();
		var wire = this.json
			.valueToTree(new OpenRouterResponsesRequestMapper(this.json).map(List.of(), options, stream, List.of()));
		assertThat(wire.get("top_logprobs").asInt()).isZero();
		assertThat(wire.get(CACHE_KEY).asString()).isEqualTo("test");
	}

	static Stream<Arguments> routingEndpoints() {
		return Stream.of(false, true)
			.flatMap(stream -> Stream.of(Arguments.of(Named.of("chat completions, stream=" + stream, "chat"), stream),
					Arguments.of(Named.of("responses, stream=" + stream, "responses"), stream)));
	}

	@ParameterizedTest
	@MethodSource("routingEndpoints")
	void routingExtensionsReachBothEndpoints(String endpoint, boolean stream) {
		List<String> only = new ArrayList<>(List.of("synthetic-provider"));
		var fields = Map.<String, Object>of("only", only, "zdr", false, "max_price", Map.of("prompt", 0), "sort",
				Map.of("by", "latency", "partition", "none"), "preferred_min_throughput", Map.of("p50", 10),
				"preferred_max_latency", 2);
		var options = OpenRouterChatOptions.builder().providerExtraBody(fields).build();
		only.clear();

		Object request = "chat".equals(endpoint)
				? new OpenRouterChatRequestMapper(this.json).map(List.of(), options, stream, List.of())
				: new OpenRouterResponsesRequestMapper(this.json).map(List.of(), options, stream, List.of());
		var wire = this.json.valueToTree(request).get("provider");
		assertThat(wire.get("only").get(0).asString()).isEqualTo("synthetic-provider");
		assertThat(wire.get("sort").get("by").asString()).isEqualTo("latency");
		assertThat(wire.get("zdr").asBoolean()).isFalse();
	}

	@Test
	void routingSortCollisionIsRejected() {
		var options = OpenRouterChatOptions.builder()
			.providerExtraBody(Map.of("sort", Map.of("by", "latency")))
			.provider(new OpenRouterProviderPreferences(null, null, null, null, null, null, "price"))
			.build();
		assertThatThrownBy(() -> new OpenRouterChatRequestMapper(this.json).map(List.of(), options, false, List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("conflicts");
	}

	static Stream<Arguments> invalidNumericLeaves() {
		Map<String, Object> invalid = new LinkedHashMap<>();
		invalid.put("boolean", true);
		invalid.put("list", List.of(1));
		invalid.put("object", Map.of("nested", 1));
		invalid.put("NaN", Double.NaN);
		invalid.put("infinity", Double.POSITIVE_INFINITY);
		invalid.put("text", "not-a-number");
		Map<String, BiConsumer<OpenRouterChatOptions.Builder, Map<String, Object>>> setters = new LinkedHashMap<>();
		setters.put("logit_bias", (builder, values) -> builder.extraBody(Map.of("logit_bias", values)));
		for (String key : List.of("max_price", "preferred_min_throughput", "preferred_max_latency")) {
			setters.put(key, (builder, values) -> builder.providerExtraBody(Map.of(key, values)));
		}
		return setters.entrySet()
			.stream()
			.flatMap(setter -> invalid.entrySet()
				.stream()
				.map(leaf -> Arguments.of(Named.of(setter.getKey() + " with " + leaf.getKey(), setter.getValue()),
						leaf.getValue())));
	}

	@ParameterizedTest
	@MethodSource("invalidNumericLeaves")
	void numericExtensionMapsRejectNonNumericLeavesAndNonFiniteValues(
			BiConsumer<OpenRouterChatOptions.Builder, Map<String, Object>> setter, Object invalid) {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("42", invalid);
		assertThatThrownBy(() -> setter.accept(OpenRouterChatOptions.builder(), values))
			.isInstanceOf(IllegalArgumentException.class);
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
		var assistant = ResponseMappers.chat().map(response).getResult().getOutput();
		assertThat(assistant.getMetadata()).containsKeys(ExtensionMetadata.MESSAGE, ExtensionMetadata.CHOICE,
				ExtensionMetadata.TOOLS);
		var replay = new OpenRouterChatRequestMapper(this.json).map(List.of(assistant),
				OpenRouterChatOptions.builder().build(), false, List.of());
		assertThat(replay.messages().get(0).extensions()).isEmpty();
		assertThat(replay.messages().get(0).toolCalls().get(0).extensions()).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "sync", "streaming" })
	void responsesAnnotationsAndOpaqueToolFieldsRemainInOutputSnapshots(String path) {
		String fixture = """
				{"status":"completed","output":[{"type":"message","role":"assistant",
				"content":[{"type":"output_text","text":"synthetic","annotations":[{"future":false}]}]},
				{"type":"function_call","call_id":"call-1","name":"lookup","arguments":"{}","future":0}]}
				""";
		var response = this.json.readValue(fixture, ResponsesResult.class);
		var event = this.json.readValue("{\"type\":\"response.completed\",\"response\":" + fixture + "}",
				ResponsesStreamEvent.class);
		var mapped = path.equals("sync") ? ResponseMappers.responses().map(response)
				: ResponseMappers.responsesEvent(event);

		var snapshot = this.json
			.valueToTree(mapped.getResult().getOutput().getMetadata().get(ReasoningMetadata.RESPONSES_OUTPUT_ITEMS));
		assertThat(snapshot.get(0).get("content").get(0).get("annotations").get(0).get("future").asBoolean()).isFalse();
		assertThat(snapshot.get(1).get("future").asInt()).isZero();
	}

	@Test
	void plainTextStreamsRetainEarlierAnnotationsAtCompletion() {
		var stream = ResponseMappers
			.chatStream(Flux.just(chunk("{\"content\":\"a\",\"annotations\":[{\"id\":1}]}", null),
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
		var stream = ResponseMappers.chatStream(ResponseMappers.aggregator().aggregate(Flux.just(first, last)));
		List<ChatResponse> responses = stream.collectList().block(TIMEOUT);

		assertThat(responses).as("aggregated responses").isNotEmpty();
		assertThat(responses).allSatisfy(response -> {
			var root = this.json.valueToTree(response.getMetadata().get(ExtensionMetadata.RESPONSE));
			assertThat(root.get("annotations").size()).isEqualTo(1);
			assertThat(response.getResults()).hasSize(2);
			var choiceAnnotations = response.getResults()
				.stream()
				.map(result -> this.json.valueToTree(result.getOutput().getMetadata())
					.get(ExtensionMetadata.MESSAGE)
					.get("annotations"))
				.toList();
			assertThat(choiceAnnotations).hasSize(2).allSatisfy(annotations -> assertThat(annotations.size()).isOne());
			assertThat(choiceAnnotations.get(0).get(0).get("id").asInt()).isEqualTo(1);
			assertThat(choiceAnnotations.get(1).get(0).get("id").asInt()).isEqualTo(2);
		});
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
		var stream = ResponseMappers.chatStream(ResponseMappers.aggregator().aggregate(Flux.just(first, last)));
		verifyAggregatedToolStream(stream, "first subscription");
		verifyAggregatedToolStream(stream, "second subscription");
	}

	private void verifyAggregatedToolStream(Flux<ChatResponse> stream, String subscription) {
		StepVerifier.create(stream, StepVerifierOptions.create().scenarioName(subscription)).assertNext(response -> {
			var output = response.getResult().getOutput();
			assertThat(output.getToolCalls().get(0).arguments()).isEqualTo("{}");
			var metadata = this.json.valueToTree(output.getMetadata());
			assertThat(metadata.get(ExtensionMetadata.MESSAGE).get("annotations").size()).isEqualTo(2);
			assertThat(metadata.get(ExtensionMetadata.TOOLS).get("call-1").get("signature").asString())
				.isEqualTo("synthetic");
			assertThat(metadata.get(ExtensionMetadata.TOOLS).get("call-1").get("future").asInt()).isZero();
		}).verifyComplete();
	}

	private ChatCompletionChunk chunk(String delta, String finishReason) {
		return this.json.readValue("{\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":"
				+ this.json.writeValueAsString(finishReason) + "}]}", ChatCompletionChunk.class);
	}

}
