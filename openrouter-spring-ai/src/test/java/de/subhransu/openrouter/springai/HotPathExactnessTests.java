package de.subhransu.openrouter.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterResponsesStreamingResponseMapper;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterStreamingResponseMapper;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterStreamingToolCallAggregator;
import de.subhransu.openrouter.springai.errors.OpenRouterLimitExceededException;
import de.subhransu.openrouter.springai.errors.OpenRouterLimitExceededException.Limit;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.deser.jackson.JsonNodeDeserializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.node.ObjectNode;

class HotPathExactnessTests {

	private static final Duration WAIT = Duration.ofSeconds(5);

	@Test
	void statefulExtensionSerializerStartsFreshForEachSubscription() {
		var chunk = new ChatCompletionChunk(null, null, null, null, null, List.of(), null, null,
				Map.of("opaque", new Marker()));
		var stream = new OpenRouterStreamingResponseMapper(14, 1).map(Flux.just(chunk));

		assertThat(stream.collectList().block(WAIT)).hasSize(1);
		assertThat(stream.collectList().block(WAIT)).hasSize(1);
	}

	@Test
	void toolChunkBudgetMeasuresEachChunkWithAFreshGenerator() {
		List<Integer> rootEntries = new ArrayList<>();
		SimpleModule module = new SimpleModule();
		module.addSerializer(ChatCompletionChunk.class, new ValueSerializer<ChatCompletionChunk>() {
			@Override
			public void serialize(ChatCompletionChunk value, JsonGenerator generator, SerializationContext context) {
				int entries = generator.streamWriteContext().getEntryCount();
				rootEntries.add(entries);
				generator.writeString("x".repeat(entries + 1));
			}
		});
		ObjectMapper mapper = JsonMapper.builder().addModule(module).build();
		var chunks = Flux.just(
				chunk("{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"id\":\"call\",\"index\":0,"
						+ "\"type\":\"function\",\"function\":{\"name\":\"f\",\"arguments\":\"{\"}}]}}]}"),
				chunk("{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":"
						+ "{\"arguments\":\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}"));

		var results = new OpenRouterStreamingToolCallAggregator(mapper, 6, 10, WAIT).aggregate(chunks)
			.collectList()
			.block(WAIT);

		assertThat(results).hasSize(1);
		assertThat(rootEntries).containsExactly(0, 0);
	}

	@Test
	void suppliedMapperDecodesBlockingBodiesFromBytes() {
		SimpleModule module = new SimpleModule();
		module.addDeserializer(ChatCompletionResponse.class, new ValueDeserializer<ChatCompletionResponse>() {
			@Override
			public ChatCompletionResponse deserialize(JsonParser parser, DeserializationContext context) {
				Object source = parser.streamReadInputSource();
				parser.skipChildren();
				return new ChatCompletionResponse(source instanceof InputStream ? "stream" : "bytes", null, null, null,
						null, null, null);
			}
		});

		var response = blockingApi(JsonMapper.builder().addModule(module).build(), "{}", 1024)
			.chatCompletion(request());

		assertThat(response.id()).isEqualTo("bytes");
	}

	@Test
	void suppliedMapperIsNotInvokedForAnOversizedBlockingBody() {
		AtomicInteger calls = new AtomicInteger();
		SimpleModule module = new SimpleModule();
		module.addDeserializer(ChatCompletionResponse.class, new ValueDeserializer<ChatCompletionResponse>() {
			@Override
			public ChatCompletionResponse deserialize(JsonParser parser, DeserializationContext context) {
				calls.incrementAndGet();
				parser.skipChildren();
				return new ChatCompletionResponse("value", null, null, null, null, null, null);
			}
		});
		var api = blockingApi(JsonMapper.builder().addModule(module).build(), "{}" + " ".repeat(64), 64);

		assertThatThrownBy(() -> api.chatCompletion(request())).isInstanceOfSatisfying(
				OpenRouterLimitExceededException.class,
				error -> assertThat(error.getLimit()).isEqualTo(Limit.BLOCKING_RESPONSE_BODY_BYTES));
		assertThat(calls).hasValue(0);
	}

	@Test
	void suppliedMapperProbesTheFirstLineOfAMultilineEvent() {
		AtomicInteger calls = new AtomicInteger();
		SimpleModule module = new SimpleModule();
		module.addDeserializer(JsonNode.class, new ValueDeserializer<JsonNode>() {
			@Override
			public JsonNode deserialize(JsonParser parser, DeserializationContext context) {
				calls.incrementAndGet();
				JsonNode node = JsonNodeDeserializer.getDeserializer(JsonNode.class).deserialize(parser, context);
				if (node.at("/choices/0/delta") instanceof ObjectNode delta) {
					delta.put("content", "hello-" + calls.get());
				}
				return node;
			}
		});
		var api = streamApi(JsonMapper.builder().addModule(module).build(),
				"data: {\ndata: \"choices\": [{\"index\":0,\"delta\":{\"content\":\"hello\"}}]\ndata: }\n\ndata: [DONE]\n\n");

		var chunks = api.chatCompletionStream(request()).collectList().block(WAIT);

		assertThat(chunks).singleElement()
			.satisfies(chunk -> assertThat(chunk.choices().get(0).delta().content()).isEqualTo("hello-2"));
	}

	@Test
	void responsesTextDeltaReplacesEarlierGenerationMetadataWhenAggregated() {
		var event = new ObjectMapper().readValue("{\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}",
				ResponsesStreamEvent.class);
		ChatResponse delta = new OpenRouterResponsesStreamingResponseMapper().map(Flux.just(event)).blockFirst(WAIT);
		ChatResponse previous = new ChatResponse(
				List.of(new Generation(AssistantMessage.builder().content("previous").build(),
						ChatGenerationMetadata.builder().metadata("synthetic", "previous").build())));
		AtomicReference<ChatResponse> aggregate = new AtomicReference<>();

		new MessageAggregator().aggregate(Flux.just(previous, delta), aggregate::set).blockLast(WAIT);

		assertThat(delta.getResult().getMetadata()).isNotSameAs(ChatGenerationMetadata.NULL);
		assertThat(aggregate.get().getResult().getMetadata().entrySet()).isEmpty();
	}

	@Test
	void rejectedStreamObservationStillReceivesTheResponse() {
		ObservationRegistry registry = ObservationRegistry.create();
		AtomicReference<ChatModelObservationContext> rejected = new AtomicReference<>();
		registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
			@Override
			public boolean supportsContext(Observation.Context context) {
				return true;
			}
		}).observationPredicate((name, context) -> {
			if (context instanceof ChatModelObservationContext chat) {
				rejected.set(chat);
			}
			return false;
		});
		var api = streamApi(null,
				"data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hello\"},\"finish_reason\":\"stop\"}]}\n\n"
						+ "data: [DONE]\n\n");
		var model = OpenRouterChatModel.builder()
			.openRouterApi(api)
			.defaultOptions(OpenRouterChatOptions.builder().model("synthetic").build())
			.observationRegistry(registry)
			.build();

		model.stream(new Prompt("synthetic")).blockLast(WAIT);

		assertThat(rejected.get().getResponse()).isNotNull();
		assertThat(rejected.get().getResponse().getResult().getOutput().getText()).isEqualTo("hello");
	}

	private static ChatCompletionChunk chunk(String json) {
		return new ObjectMapper().readValue(json, ChatCompletionChunk.class);
	}

	private static ChatCompletionRequest request() {
		return ChatCompletionRequest.builder()
			.model("synthetic")
			.messages(List.of(new ChatMessage("user", "hello", null, null, null)))
			.build();
	}

	private static OpenRouterApi streamApi(ObjectMapper mapper, String sse) {
		var builder = OpenRouterApi.builder()
			.apiKey("synthetic-key")
			.baseUrl("https://synthetic.invalid")
			.webClientBuilder(WebClient.builder()
				.exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
					.header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
					.body(sse)
					.build())));
		return (mapper == null ? builder : builder.objectMapper(mapper)).build();
	}

	private static OpenRouterApi blockingApi(ObjectMapper mapper, String body, int limit) {
		RestClient.Builder rest = RestClient.builder().requestFactory((uri, method) -> {
			var request = new MockClientHttpRequest(method, uri);
			request.setResponse(new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), HttpStatus.OK));
			return request;
		});
		return OpenRouterApi.builder()
			.apiKey("synthetic-key")
			.baseUrl("https://synthetic.invalid")
			.objectMapper(mapper)
			.restClientBuilder(rest)
			.maxResponseBodyBytes(limit)
			.build();
	}

	@JsonSerialize(using = StatefulMarkerSerializer.class)
	static final class Marker {

	}

	static final class StatefulMarkerSerializer extends ValueSerializer<Marker> {

		private int calls;

		@Override
		public void serialize(Marker value, JsonGenerator generator, SerializationContext context) {
			generator.writeString("x".repeat(++this.calls));
		}

	}

}
