package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.EmbeddingsResponse;
import de.subhransu.openrouter.springai.api.dto.ImagesResponse;
import de.subhransu.openrouter.springai.api.dto.ImagesStreamEvent;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.api.dto.Usage;
import de.subhransu.openrouter.springai.chat.OpenRouterUsage;
import de.subhransu.openrouter.springai.embedding.mapper.OpenRouterEmbeddingResponseMapper;
import de.subhransu.openrouter.springai.image.mapper.OpenRouterImageResponseMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

class UsageWireMappingTests {

	private final ObjectMapper mapper = new ObjectMapper();

	@ParameterizedTest
	@CsvSource({ "3,2", "0,0", "," })
	void responsesRetainNativeDetailsInSyncAndTerminalEvent(Integer cached, Integer reasoning) {
		String json = """
				{"id":"response-synthetic","model":"test-model","status":"completed","output":[],"usage":%s}
				""".formatted(usageJson(true, cached, reasoning));
		ResponsesResult result = this.mapper.readValue(json, ResponsesResult.class);
		assertWireDetails(result.usage(), cached, reasoning);
		assertUsage(new OpenRouterResponsesResponseMapper().map(result).getMetadata().getUsage(), result.usage(),
				cached, reasoning);

		ResponsesStreamEvent event = this.mapper
			.readValue("{\"type\":\"response.completed\",\"response\":" + json + "}", ResponsesStreamEvent.class);
		assertWireDetails(event.response().usage(), cached, reasoning);
		StepVerifier.create(new OpenRouterResponsesStreamingResponseMapper().map(Flux.just(event)))
			.assertNext(response -> assertUsage(response.getMetadata().getUsage(), event.response().usage(), cached,
					reasoning))
			.verifyComplete();
	}

	@ParameterizedTest
	@CsvSource({ "3,2", "0,0", "," })
	void chatEmbeddingsAndImagesUseTheSameNestedAccounting(Integer cached, Integer reasoning) {
		String usage = usageJson(false, cached, reasoning);
		ChatCompletionResponse chat = this.mapper
			.readValue("{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],\"usage\":"
					+ usage + "}", ChatCompletionResponse.class);
		assertUsage(new OpenRouterChatResponseMapper().map(chat).getMetadata().getUsage(), chat.usage(), cached,
				reasoning);
		ChatCompletionChunk chunk = this.mapper.readValue("{\"choices\":[],\"usage\":" + usage + "}",
				ChatCompletionChunk.class);
		StepVerifier.create(new OpenRouterStreamingResponseMapper().map(Flux.just(chunk)))
			.assertNext(response -> assertUsage(response.getMetadata().getUsage(), chunk.usage(), cached, reasoning))
			.verifyComplete();

		EmbeddingsResponse embeddings = this.mapper.readValue(
				"{\"model\":\"test-model\",\"data\":[{\"index\":0,\"embedding\":[0.5]}],\"usage\":" + usage + "}",
				EmbeddingsResponse.class);
		assertUsage(new OpenRouterEmbeddingResponseMapper().map(embeddings).getMetadata().getUsage(),
				embeddings.usage(), cached, reasoning);
		ImagesResponse images = this.mapper.readValue("{\"data\":[],\"usage\":" + usage + "}", ImagesResponse.class);
		assertUsage(new OpenRouterImageResponseMapper().map(images).getMetadata().get("openrouter.usage"),
				images.usage(), cached, reasoning);
		ImagesStreamEvent imageEvent = this.mapper
			.readValue("{\"type\":\"image_generation.completed\",\"usage\":" + usage + "}", ImagesStreamEvent.class);
		assertUsage(new OpenRouterImageResponseMapper().map(imageEvent).getMetadata().get("openrouter.usage"),
				imageEvent.usage(), cached, reasoning);
	}

	private String usageJson(boolean responses, Integer cached, Integer reasoning) {
		String input = responses ? "input" : "prompt";
		String output = responses ? "output" : "completion";
		String details = cached == null ? "" : """
				,"%s_tokens_details":{"cached_tokens":%d},"%s_tokens_details":{"reasoning_tokens":%d}
				""".formatted(input, cached, output, reasoning);
		return """
				{"%s_tokens":12,"%s_tokens":7,"total_tokens":19,"cost":0.003,"details":{"synthetic":true}%s}
				""".formatted(input, output, details);
	}

	private void assertWireDetails(Usage usage, Integer cached, Integer reasoning) {
		if (cached == null) {
			assertThat(usage.promptTokensDetails()).isNull();
			assertThat(usage.completionTokensDetails()).isNull();
		}
		else {
			assertThat(usage.promptTokensDetails()).isNotNull();
			assertThat(usage.promptTokensDetails().cachedTokens()).isEqualTo(cached);
			assertThat(usage.completionTokensDetails().reasoningTokens()).isEqualTo(reasoning);
		}
	}

	private void assertUsage(Object mapped, Usage source, Integer cached, Integer reasoning) {
		assertThat(mapped).isInstanceOf(OpenRouterUsage.class);
		OpenRouterUsage usage = (OpenRouterUsage) mapped;
		assertThat(usage.getPromptTokens()).isEqualTo(12);
		assertThat(usage.getCompletionTokens()).isEqualTo(7);
		assertThat(usage.getTotalTokens()).isEqualTo(19);
		assertThat(usage.getCachedTokens()).isEqualTo(cached);
		assertThat(usage.getReasoningTokens()).isEqualTo(reasoning);
		org.springframework.ai.chat.metadata.Usage portable = usage;
		assertThat(portable.getCacheReadInputTokens()).isEqualTo(cached == null ? null : cached.longValue());
		assertThat(portable.getCacheWriteInputTokens()).isNull();
		assertThat(usage.getCost()).isEqualTo(0.003);
		assertThat(usage.getNativeUsage()).isSameAs(source);
		assertThat(source.details()).containsEntry("synthetic", true);
	}

}
