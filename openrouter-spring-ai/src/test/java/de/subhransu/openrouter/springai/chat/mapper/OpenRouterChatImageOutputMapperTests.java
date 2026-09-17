package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.ContentPart;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;

/**
 * Chat-mode image generation: {@code modalities} and {@code image_config} must reach the
 * wire, and the {@code images} array on assistant messages (sync) and deltas (streaming)
 * must surface as {@link Media} on the {@link AssistantMessage}.
 */
class OpenRouterChatImageOutputMapperTests {

	private static final String DATA_URL = "data:image/png;base64,aW1hZ2Ux";

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void joinsMixedContentWithoutChangingWhitespaceOrNullHandling() {
		var content = Arrays.asList(null, 42, " first ", ContentPart.text(""),
				Map.of("type", "text", "text", "\nsecond"),
				new ContentPart("image_url", null, new ContentPart.ImageUrl(DATA_URL)), Map.of("text", 123),
				ContentPart.text(" last "));
		var mapped = AssistantContentMapper.map(content);
		assertThat(mapped.text()).isEqualTo(" first \nsecond last ");
		assertThat(mapped.media()).singleElement().extracting(Media::getData).isEqualTo(DATA_URL);
		assertThat(AssistantContentMapper.map(Arrays.asList(null, 42, Map.of())).text()).isEmpty();
		assertThat(AssistantContentMapper.map(List.of()).text()).isEmpty();
	}

	@Test
	void putsModalitiesAndImageConfigOnTheWire() {
		OpenRouterChatRequestMapper mapper = new OpenRouterChatRequestMapper(this.objectMapper);
		OpenRouterChatOptions options = OpenRouterChatOptions.builder()
			.model("google/gemini-2.5-flash-image")
			.modalities(List.of("image", "text"))
			.imageConfig(Map.of("aspect_ratio", "16:9"))
			.build();

		ChatCompletionRequest request = mapper.map(List.of(new UserMessage("a red panda")), options, false, List.of());
		String json = this.objectMapper.writeValueAsString(request);

		assertThat(request.modalities()).containsExactly("image", "text");
		assertThat(json).contains("\"modalities\":[\"image\",\"text\"]")
			.contains("\"image_config\":{\"aspect_ratio\":\"16:9\"}");
	}

	@Test
	void surfacesGeneratedImagesAsAssistantMedia() {
		String body = """
				{
				  "id": "gen-img-1",
				  "model": "google/gemini-2.5-flash-image",
				  "choices": [
				    {
				      "index": 0,
				      "message": {
				        "role": "assistant",
				        "content": "Here you go",
				        "images": [
				          {"type": "image_url", "image_url": {"url": "%s"}}
				        ]
				      },
				      "finish_reason": "stop"
				    }
				  ]
				}
				""".formatted(DATA_URL);
		ChatCompletionResponse response = this.objectMapper.readValue(body, ChatCompletionResponse.class);

		ChatResponse chatResponse = new OpenRouterChatResponseMapper().map(response);

		AssistantMessage message = chatResponse.getResult().getOutput();
		assertThat(message.getText()).isEqualTo("Here you go");
		assertThat(message.getMedia()).hasSize(1);
		Media media = message.getMedia().get(0);
		assertThat(media.getMimeType()).isEqualTo(MimeTypeUtils.IMAGE_PNG);
		assertThat(media.getData()).isEqualTo(DATA_URL);
	}

	@Test
	void surfacesStreamedDeltaImagesAsAssistantMedia() {
		String chunk = """
				{
				  "id": "gen-img-1",
				  "object": "chat.completion.chunk",
				  "model": "google/gemini-2.5-flash-image",
				  "choices": [
				    {
				      "index": 0,
				      "delta": {
				        "content": "",
				        "images": [
				          {"type": "image_url", "image_url": {"url": "%s"}}
				        ]
				      }
				    }
				  ]
				}
				""".formatted(DATA_URL);
		ChatCompletionChunk completionChunk = this.objectMapper.readValue(chunk, ChatCompletionChunk.class);

		ChatResponse chatResponse = new OpenRouterStreamingResponseMapper().map(completionChunk);

		assertThat(chatResponse.getResult().getOutput().getMedia()).hasSize(1);
		assertThat(chatResponse.getResult().getOutput().getMedia().get(0).getData()).isEqualTo(DATA_URL);
	}

	@Test
	void ignoresAbsentImagesArray() {
		String body = """
				{
				  "id": "gen-1",
				  "model": "openai/gpt-5.4-mini",
				  "choices": [
				    {"index": 0, "message": {"role": "assistant", "content": "text only"}, "finish_reason": "stop"}
				  ]
				}
				""";
		ChatCompletionResponse response = this.objectMapper.readValue(body, ChatCompletionResponse.class);

		ChatResponse chatResponse = new OpenRouterChatResponseMapper().map(response);

		assertThat(chatResponse.getResult().getOutput().getMedia()).isEmpty();
	}

}
