package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.chat.OpenRouterMedia;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.databind.ObjectMapper;

@Tag("multimodal")
@Tag("multimodal.image-output")
class OpenRouterMediaTests {

	private static final String IMAGE_URL = "data:image/png;base64,aW1hZ2Ux";

	private final ObjectMapper json = new ObjectMapper();

	@Test
	void decodesGeneratedImagesInBothModesForCallsAndStreams() {
		String images = "\"images\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"" + IMAGE_URL + "\"}}]";
		Media completion = new OpenRouterChatResponseMapper().map(this.json.readValue("""
				{"choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"",%s}}]}"""
			.formatted(images), ChatCompletionResponse.class)).getResult().getOutput().getMedia().get(0);
		Media chunk = new OpenRouterStreamingResponseMapper()
			.map(this.json.readValue("{\"choices\":[{\"index\":0,\"delta\":{%s}}]}".formatted(images),
					ChatCompletionChunk.class))
			.getResult()
			.getOutput()
			.getMedia()
			.get(0);
		Media responses = new OpenRouterResponsesStreamingResponseMapper().map(this.json.readValue("""
				{"type":"response.output_item.done","item":{"type":"image_generation_call","id":"ig-1",
				 "status":"completed","result":"aW1hZ2Ux"}}""", ResponsesStreamEvent.class))
			.getResult()
			.getOutput()
			.getMedia()
			.get(0);

		for (Media media : new Media[] { completion, chunk, responses }) {
			assertThat(new String(OpenRouterMedia.bytes(media), StandardCharsets.US_ASCII)).isEqualTo("image1");
		}
	}

	@Test
	void returnsRawBytesUnchanged() {
		byte[] audio = { 0, 1, 2 };

		assertThat(OpenRouterMedia
			.bytes(Media.builder().mimeType(MimeTypeUtils.parseMimeType("audio/pcm")).data(audio).build()))
			.containsExactly(0, 1, 2);
	}

	@Test
	void rejectsMediaItCannotDecodeWithoutEchoingTheUrl() {
		Media remote = image("https://example.test/image.png?token=secret");
		Media plain = image("data:image/svg+xml,%3Csvg%3E");
		Media invalid = image("data:image/png;base64,***");

		assertThatThrownBy(() -> OpenRouterMedia.bytes(remote)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("'https' URL")
			.hasMessageNotContaining("secret");
		assertThatThrownBy(() -> OpenRouterMedia.bytes(plain)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("must be base64-encoded")
			.hasMessageContaining("data:image/svg+xml");
		assertThatThrownBy(() -> OpenRouterMedia.bytes(invalid)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("invalid base64");
	}

	private static Media image(String data) {
		return Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(data).build();
	}

}
