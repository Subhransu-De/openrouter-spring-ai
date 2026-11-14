package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.EmbeddingsResponse;
import de.subhransu.openrouter.springai.api.dto.ImagesResponse;
import de.subhransu.openrouter.springai.api.dto.ImagesStreamEvent;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.chat.OpenRouterCostDetails;
import de.subhransu.openrouter.springai.chat.OpenRouterMetadata;
import de.subhransu.openrouter.springai.chat.OpenRouterUsage;
import de.subhransu.openrouter.springai.embedding.mapper.OpenRouterEmbeddingResponseMapper;
import de.subhransu.openrouter.springai.image.mapper.OpenRouterImageResponseMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.image.ImageResponse;
import tools.jackson.databind.ObjectMapper;

@Tag("observability")
@Tag("observability.usage")
class UsageReaderTests {

	private static final String USAGE = """
			{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15,"cost":0.002,
			 "cost_details":{"upstream_inference_cost":0.003}}""";

	private final ObjectMapper json = new ObjectMapper();

	@Test
	void readsChatUsageInBothModesForCallsAndStreams() {
		ChatResponse completion = new OpenRouterChatResponseMapper().map(this.json.readValue("""
				{"choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"hi"}}],
				 "usage":%s}""".formatted(USAGE), ChatCompletionResponse.class));
		ChatResponse chunk = new OpenRouterStreamingResponseMapper()
			.map(this.json.readValue("{\"choices\":[],\"usage\":%s}".formatted(USAGE), ChatCompletionChunk.class));
		ChatResponse responses = new OpenRouterResponsesResponseMapper().map(this.json.readValue("""
				{"status":"completed","output":[],"usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15,
				 "cost":0.002}}""", ResponsesResult.class));
		ChatResponse responsesStream = new OpenRouterResponsesStreamingResponseMapper().map(this.json.readValue("""
				{"type":"response.completed","response":{"status":"completed","output":[],
				 "usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15,"cost":0.002}}}""",
				ResponsesStreamEvent.class));

		for (ChatResponse response : List.of(completion, chunk, responses, responsesStream)) {
			OpenRouterUsage usage = OpenRouterUsage.from(response);
			assertThat(usage).isSameAs(response.getMetadata().getUsage());
			assertThat(usage.getTotalTokens()).isEqualTo(15);
			assertThat(usage.getCost()).isEqualTo(0.002);
		}
		assertThat(OpenRouterUsage.from(completion).getCostDetails().upstreamInferenceCost()).isEqualTo(0.003);
	}

	@Test
	void absentChatUsageReadsAsZeroCountsAndNullValues() {
		ChatResponse response = new OpenRouterChatResponseMapper().map(this.json.readValue("""
				{"choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"hi"}}]}""",
				ChatCompletionResponse.class));

		assertEmpty(OpenRouterUsage.from(response));
	}

	@Test
	void foreignUsageKeepsItsTokenCounts() {
		ChatResponse aggregated = new ChatResponse(List.of(new Generation(new AssistantMessage("hi"))),
				ChatResponseMetadata.builder().usage(new DefaultUsage(3, 4, 7, null, 2L, null)).build());

		OpenRouterUsage usage = OpenRouterUsage.from(aggregated);

		assertThat(usage.getPromptTokens()).isEqualTo(3);
		assertThat(usage.getCompletionTokens()).isEqualTo(4);
		assertThat(usage.getTotalTokens()).isEqualTo(7);
		assertThat(usage.getCachedTokens()).isEqualTo(2);
		assertThat(usage.getCost()).isNull();
	}

	@Test
	void readsEmbeddingUsageOrAnEmptyUsage() {
		var mapper = new OpenRouterEmbeddingResponseMapper();
		String body = """
				{"model":"synthetic/embed","data":[{"index":0,"embedding":[0.1,0.2]}]%s}""";

		OpenRouterUsage reported = OpenRouterUsage
			.from(mapper.map(this.json.readValue(body.formatted(",\"usage\":" + USAGE), EmbeddingsResponse.class)));
		OpenRouterUsage absent = OpenRouterUsage
			.from(mapper.map(this.json.readValue(body.formatted(""), EmbeddingsResponse.class)));

		assertThat(reported.getCost()).isEqualTo(0.002);
		assertEmpty(absent);
	}

	@Test
	void readsImageUsageFromCallsAndStreamEventsUnderThePublicKey() {
		var mapper = new OpenRouterImageResponseMapper();
		ImageResponse call = mapper.map(this.json.readValue("""
				{"created":1,"data":[{"b64_json":"aW1n"}],"usage":%s}""".formatted(USAGE), ImagesResponse.class));
		ImageResponse completed = mapper.map(this.json.readValue("""
				{"type":"image_generation.completed","b64_json":"aW1n","usage":%s}""".formatted(USAGE),
				ImagesStreamEvent.class));
		ImageResponse partial = mapper.map(this.json.readValue("""
				{"type":"image_generation.partial_image","partial_image_index":0,"b64_json":"aW1n"}""",
				ImagesStreamEvent.class));

		assertThat(OpenRouterMetadata.IMAGE_USAGE).isEqualTo("openrouter.image.usage");
		assertThat(OpenRouterUsage.from(call)).isSameAs(call.getMetadata().get("openrouter.image.usage"));
		assertThat(OpenRouterUsage.from(call).getCost()).isEqualTo(0.002);
		assertThat(OpenRouterUsage.from(completed).getCostDetails().upstreamInferenceCost()).isEqualTo(0.003);
		assertEmpty(OpenRouterUsage.from(partial));
	}

	@Test
	void imageUsageOfAnotherTypeReadsAsEmpty() {
		ImageResponse response = new ImageResponse(List.of());
		response.getMetadata().put(OpenRouterMetadata.IMAGE_USAGE, Map.of("cost", 1));

		assertEmpty(OpenRouterUsage.from(response));
	}

	private static void assertEmpty(OpenRouterUsage usage) {
		assertThat(usage.getPromptTokens()).isZero();
		assertThat(usage.getCompletionTokens()).isZero();
		assertThat(usage.getTotalTokens()).isZero();
		assertThat(usage.getCost()).isNull();
		assertThat(usage.getCachedTokens()).isNull();
		assertThat(usage.getReasoningTokens()).isNull();
		assertThat(usage.getByok()).isNull();
		assertThat(usage.getCostDetails()).isEqualTo(new OpenRouterCostDetails(null, null, null, null));
	}

}
