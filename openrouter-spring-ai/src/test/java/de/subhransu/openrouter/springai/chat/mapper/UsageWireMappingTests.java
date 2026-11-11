package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.EmbeddingsResponse;
import de.subhransu.openrouter.springai.api.dto.ImagesResponse;
import de.subhransu.openrouter.springai.api.dto.ImagesStreamEvent;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.api.dto.Usage;
import de.subhransu.openrouter.springai.chat.OpenRouterUsage;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingModel;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingOptions;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.embedding.EmbeddingRequest;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

class UsageWireMappingTests {

	private final ObjectMapper mapper = new ObjectMapper();

	@ParameterizedTest(name = "cached={0}, reasoning={1}, written={2}")
	@CsvSource({ "3,2,7", "0,0,0", "3,2,", ",," })
	void responsesRetainNativeDetailsInSyncAndTerminalEvent(Integer cached, Integer reasoning, Integer written) {
		String json = """
				{"id":"response-synthetic","model":"test-model","status":"completed","output":[],"usage":%s}
				""".formatted(usageJson(true, cached, reasoning, written));
		ResponsesResult result = this.mapper.readValue(json, ResponsesResult.class);
		assertWireDetails(result.usage(), cached, reasoning);
		assertUsage(ResponseMappers.responses().map(result).getMetadata().getUsage(), result.usage(), cached, reasoning,
				written);

		ResponsesStreamEvent event = this.mapper
			.readValue("{\"type\":\"response.completed\",\"response\":" + json + "}", ResponsesStreamEvent.class);
		assertWireDetails(event.response().usage(), cached, reasoning);
		StepVerifier.create(ResponseMappers.responsesStreaming().map(Flux.just(event)))
			.assertNext(response -> assertUsage(response.getMetadata().getUsage(), event.response().usage(), cached,
					reasoning, written))
			.verifyComplete();
	}

	@ParameterizedTest(name = "cached={0}, reasoning={1}, written={2}")
	@CsvSource({ "3,2,7", "0,0,0", "3,2,", ",," })
	void chatCompletionsRetainNativeDetails(Integer cached, Integer reasoning, Integer written) {
		String usage = usageJson(false, cached, reasoning, written);
		ChatCompletionResponse chat = this.mapper
			.readValue("{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],\"usage\":"
					+ usage + "}", ChatCompletionResponse.class);

		assertUsage(ResponseMappers.chat().map(chat).getMetadata().getUsage(), chat.usage(), cached, reasoning,
				written);
	}

	@ParameterizedTest(name = "cached={0}, reasoning={1}, written={2}")
	@CsvSource({ "3,2,7", "0,0,0", "3,2,", ",," })
	void chatStreamsRetainNativeDetails(Integer cached, Integer reasoning, Integer written) {
		ChatCompletionChunk chunk = this.mapper.readValue(
				"{\"choices\":[],\"usage\":" + usageJson(false, cached, reasoning, written) + "}",
				ChatCompletionChunk.class);

		StepVerifier.create(ResponseMappers.chatStream(Flux.just(chunk)))
			.assertNext(response -> assertUsage(response.getMetadata().getUsage(), chunk.usage(), cached, reasoning,
					written))
			.verifyComplete();
	}

	@ParameterizedTest(name = "cached={0}, reasoning={1}, written={2}")
	@CsvSource({ "3,2,7", "0,0,0", "3,2,", ",," })
	void embeddingsRetainNativeDetails(Integer cached, Integer reasoning, Integer written) {
		EmbeddingsResponse embeddings = this.mapper
			.readValue("{\"model\":\"test-model\",\"data\":[{\"index\":0,\"embedding\":[0.5]}],\"usage\":"
					+ usageJson(false, cached, reasoning, written) + "}", EmbeddingsResponse.class);
		OpenRouterApi embeddingsApi = mock(OpenRouterApi.class);
		when(embeddingsApi.embeddings(any())).thenReturn(embeddings);
		OpenRouterEmbeddingModel embeddingModel = OpenRouterEmbeddingModel.builder()
			.openRouterApi(embeddingsApi)
			.defaultOptions(OpenRouterEmbeddingOptions.builder().model("test-model").build())
			.build();

		assertUsage(embeddingModel.call(new EmbeddingRequest(List.of("synthetic"), null)).getMetadata().getUsage(),
				embeddings.usage(), cached, reasoning, written);
	}

	@ParameterizedTest(name = "cached={0}, reasoning={1}, written={2}")
	@CsvSource({ "3,2,7", "0,0,0", "3,2,", ",," })
	void imageResponsesRetainNativeDetails(Integer cached, Integer reasoning, Integer written) {
		ImagesResponse images = this.mapper.readValue(
				"{\"data\":[],\"usage\":" + usageJson(false, cached, reasoning, written) + "}", ImagesResponse.class);

		assertUsage(ResponseMappers.image().map(images).getMetadata().get("openrouter.usage"), images.usage(), cached,
				reasoning, written);
	}

	@ParameterizedTest(name = "cached={0}, reasoning={1}, written={2}")
	@CsvSource({ "3,2,7", "0,0,0", "3,2,", ",," })
	void imageStreamEventsRetainNativeDetails(Integer cached, Integer reasoning, Integer written) {
		ImagesStreamEvent imageEvent = this.mapper
			.readValue("{\"type\":\"image_generation.completed\",\"b64_json\":\"aW1hZ2U=\",\"usage\":"
					+ usageJson(false, cached, reasoning, written) + "}", ImagesStreamEvent.class);

		assertUsage(ResponseMappers.image().map(imageEvent).getMetadata().get("openrouter.usage"), imageEvent.usage(),
				cached, reasoning, written);
	}

	static Stream<Arguments> costFields() {
		return Stream.of(Arguments.of(Named.of("no cost field", ""), null),
				Arguments.of(Named.of("zero cost", ",\"cost\":0.0"), 0.0),
				Arguments.of(Named.of("fractional cost", ",\"cost\":0.003"), 0.003));
	}

	@ParameterizedTest
	@MethodSource("costFields")
	void chatCompletionsKeepAnAbsentCostNullAndACostedValueExact(String costField, Double cost) {
		ChatCompletionResponse chat = this.mapper
			.readValue("{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],"
					+ "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2" + costField + "}}",
					ChatCompletionResponse.class);

		OpenRouterUsage usage = (OpenRouterUsage) ResponseMappers.chat().map(chat).getMetadata().getUsage();

		assertThat(usage.getCost()).isEqualTo(cost);
	}

	private String usageJson(boolean responses, Integer cached, Integer reasoning, Integer written) {
		String input = responses ? "input" : "prompt";
		String output = responses ? "output" : "completion";
		String details = cached == null ? ""
				: """
						,"%s_tokens_details":{"cached_tokens":%d,"cache_write_tokens":%s},"%s_tokens_details":{"reasoning_tokens":%d}
						"""
					.formatted(input, cached, written, output, reasoning);
		return """
				{"%s_tokens":12,"%s_tokens":7,"total_tokens":19,"cost":0.003,"details":{"synthetic":true}%s}
				""".formatted(input, output, details);
	}

	private void assertWireDetails(Usage usage, Integer cached, Integer reasoning) {
		assertThat(Optional.ofNullable(usage.promptTokensDetails()).map(Usage.PromptTokensDetails::cachedTokens))
			.isEqualTo(Optional.ofNullable(cached));
		assertThat(Optional.ofNullable(usage.completionTokensDetails())
			.map(Usage.CompletionTokensDetails::reasoningTokens)).isEqualTo(Optional.ofNullable(reasoning));
	}

	private static Long asLong(Integer value) {
		return Optional.ofNullable(value).map(Integer::longValue).orElse(null);
	}

	private void assertUsage(Object mapped, Usage source, Integer cached, Integer reasoning, Integer written) {
		assertThat(mapped).isInstanceOf(OpenRouterUsage.class);
		OpenRouterUsage usage = (OpenRouterUsage) mapped;
		assertThat(usage.getPromptTokens()).isEqualTo(12);
		assertThat(usage.getCompletionTokens()).isEqualTo(7);
		assertThat(usage.getTotalTokens()).isEqualTo(19);
		assertThat(usage.getCachedTokens()).isEqualTo(cached);
		assertThat(usage.getReasoningTokens()).isEqualTo(reasoning);
		org.springframework.ai.chat.metadata.Usage portable = usage;
		assertThat(portable.getCacheReadInputTokens()).isEqualTo(asLong(cached));
		assertThat(portable.getCacheWriteInputTokens()).isEqualTo(asLong(written));
		assertThat(usage.getCost()).isEqualTo(0.003);
		assertThat(usage.getNativeUsage()).isSameAs(source);
		assertThat(source.details()).containsEntry("synthetic", true);
	}

}
