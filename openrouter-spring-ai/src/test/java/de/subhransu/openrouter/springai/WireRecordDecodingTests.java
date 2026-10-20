package de.subhransu.openrouter.springai;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.node.NullNode;

class WireRecordDecodingTests {

	private static final Duration WAIT = Duration.ofSeconds(5);

	@ParameterizedTest
	@ValueSource(strings = { "{}", "{\"choices\":[{}]}",
			"{\"id\":null,\"choices\":[{\"index\":null,\"delta\":{\"content\":null}}]}",
			"{\"id\":1.50,\"created\":2.9,\"choices\":[{\"index\":1.5,\"delta\":{\"content\":42}}]}",
			"{\"choices\":[{\"delta\":{\"tool_calls\":[{\"function\":{\"name\":\"f\",\"arguments\":\"{}\",\"future\":1},\"future\":null}],\"future\":{\"x\":[1,null]}},\"future\":true}],\"future\":null}",
			"{\"id\":\"first\",\"id\":\"last\",\"future\":1,\"future\":2,\"choices\":[{\"delta\":{\"content\":\"first\",\"content\":\"last\"}}]}",
			"{\"extensions\":{\"explicit\":true},\"choices\":[{\"extensions\":null,\"delta\":{\"extensions\":{\"x\":null}}}]}",
			"{\"choices\":{}}", "{\"choices\":[{\"index\":{}}]}", "{\"choices\":[{\"delta\":{\"content\":[]}}]}",
			"{\"choices\":[{\"delta\":{\"tool_calls\":[{\"function\":{\"arguments\":{}}}]}}]}",
			"{\"created\":9223372036854775808,\"choices\":[]}", "{\"choices\":[{\"index\":2147483648,\"delta\":{}}]}",
			"{\"choices\":[{\"delta\":{\"tool_calls\":[null,{\"index\":null,\"function\":null}]}}]}",
			"{\"choices\":[{\"delta\":{\"tool_calls\":{}}}]}",
			"{\"choices\":[{\"delta\":{\"tool_calls\":[{\"function\":{\"name\":\"first\",\"name\":\"last\",\"arguments\":\"{}\"}}]}}]}",
			"{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"index\":1,\"function\":{\"arguments\":\"{}\"}}]}}]}",
			"{\"choices\":[{\"native_finish_reason\":{\"opaque\":[null,1.5]}}]}" })
	void chatRecordsRetainTreeBindingValuesAndFailures(String json) {
		assertThat(outcome(() -> api(json, null).chatCompletionStream(request()).collectList().block(WAIT))).isEqualTo(
				outcome(() -> api(json, new ObjectMapper()).chatCompletionStream(request()).collectList().block(WAIT)));
	}

	@Test
	void commonToolChunksRetainTheDecodedContainerTypes() {
		String json = "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
				+ "\"function\":{\"name\":\"f\",\"arguments\":\"{}\"}}]}}]}";
		ChatCompletionChunk direct = api(json, null).chatCompletionStream(request()).blockFirst(WAIT);
		ChatCompletionChunk tree = api(json, new ObjectMapper()).chatCompletionStream(request()).blockFirst(WAIT);

		assertThat(direct).isEqualTo(tree);
		assertThat(direct.extensions()).isExactlyInstanceOf(tree.extensions().getClass());
		assertThat(direct.choices()).isExactlyInstanceOf(tree.choices().getClass());
		var directChoice = direct.choices().get(0);
		var treeChoice = tree.choices().get(0);
		assertThat(directChoice.extensions()).isExactlyInstanceOf(treeChoice.extensions().getClass());
		assertThat(directChoice.delta().extensions()).isExactlyInstanceOf(treeChoice.delta().extensions().getClass());
		assertThat(directChoice.delta().toolCalls()).isExactlyInstanceOf(treeChoice.delta().toolCalls().getClass());
		assertThat(directChoice.delta().toolCalls().get(0).extensions())
			.isExactlyInstanceOf(treeChoice.delta().toolCalls().get(0).extensions().getClass());
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"type\":\"response.output_text.delta\",\"delta\":\"x\",\"future\":true}",
			"{\"type\":\"response.output_text.delta\",\"delta\":\"x\",\"metadata\":null}",
			"{\"type\":\"response.output_text.delta\",\"delta\":null,\"output_index\":null}",
			"{\"type\":\"response.output_item.added\",\"item\":{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"x\",\"future\":{\"x\":1}}],\"opaque\":[null,1]}}",
			"{\"type\":\"response.output_text.delta\",\"code\":{\"x\":1},\"message\":[1,null],\"error_type\":2}",
			"{\"type\":\"response.output_text.delta\",\"output_index\":\"2\",\"content_index\":1.9,\"text\":42}",
			"{\"type\":\"response.output_text.delta\",\"delta\":\"first\",\"delta\":\"last\"}",
			"{\"type\":\"response.output_text.delta\",\"output_index\":{}}",
			"{\"type\":\"response.output_item.added\",\"item\":{\"type\":\"message\",\"content\":{}}}" })
	void responsesRetainAnnotationsAndCustomDeserializers(String json) {
		var request = ResponsesRequest.builder().model("synthetic").input("hello").build();
		assertThat(outcome(() -> api(json, null).responsesStream(request).collectList().block(WAIT)))
			.isEqualTo(outcome(() -> api(json, new ObjectMapper()).responsesStream(request).collectList().block(WAIT)));
	}

	@Test
	void responsesDistinguishMissingMetadataFromAnExplicitJsonNull() {
		var request = ResponsesRequest.builder().model("synthetic").input("hello").build();
		var missing = api("{\"type\":\"response.output_text.delta\"}", null).responsesStream(request).blockFirst(WAIT);
		var explicit = api("{\"type\":\"response.output_text.delta\",\"metadata\":null}", null).responsesStream(request)
			.blockFirst(WAIT);

		assertThat(missing.metadata()).isNull();
		assertThat(explicit.metadata()).isSameAs(NullNode.getInstance());
	}

	@Test
	void suppliedMapperModulesAreNeverReplayedAndCustomCreatorsRemainInControl() {
		AtomicInteger setups = new AtomicInteger();
		SimpleModule module = new SimpleModule() {
			@Override
			public void setupModule(SetupContext context) {
				setups.incrementAndGet();
				super.setupModule(context);
			}
		};
		module.addDeserializer(ChatCompletionChunk.class, new ValueDeserializer<ChatCompletionChunk>() {
			@Override
			public ChatCompletionChunk deserialize(JsonParser parser, DeserializationContext context) {
				JsonNode node = parser.readValueAsTree();
				String value = node.get("id").asString();
				return new ChatCompletionChunk(value, null, null, "custom", null,
						List.of(new Choice(0, null, null, null, null)), null, null);
			}
		});
		ObjectMapper mapper = JsonMapper.builder().addModule(module).build();
		var api = api("{\"id\":1.50}", mapper);

		var chunks = api.chatCompletionStream(request()).collectList().block(WAIT);

		assertThat(setups).hasValue(1);
		assertThat(chunks).singleElement().satisfies(chunk -> {
			assertThat(chunk.id()).isEqualTo("1.5");
			assertThat(chunk.model()).isEqualTo("custom");
		});
	}

	private static String outcome(Supplier<?> action) {
		try {
			return String.valueOf(action.get());
		}
		catch (RuntimeException ex) {
			StringBuilder result = new StringBuilder();
			for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
				result.append(cause.getClass().getName()).append(':').append(cause.getMessage()).append('\n');
			}
			return result.toString();
		}
	}

	private static ChatCompletionRequest request() {
		return ChatCompletionRequest.builder()
			.model("synthetic")
			.messages(List.of(new ChatMessage("user", "hello", null, null, null)))
			.build();
	}

	private static OpenRouterApi api(String json, ObjectMapper mapper) {
		var builder = OpenRouterApi.builder()
			.apiKey("synthetic-key")
			.baseUrl("https://synthetic.invalid")
			.webClientBuilder(WebClient.builder()
				.exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
					.header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
					.body("data: " + json + "\n\n"
							+ (request.url().getPath().endsWith("/responses")
									? "data: {\"type\":\"response.completed\"}\n\n" : "")
							+ "data: [DONE]\n\n")
					.build())));
		return (mapper == null ? builder : builder.objectMapper(mapper)).build();
	}

}
