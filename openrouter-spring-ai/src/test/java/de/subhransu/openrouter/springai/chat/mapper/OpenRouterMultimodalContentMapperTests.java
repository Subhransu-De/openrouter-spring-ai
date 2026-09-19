package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.ObjectMapper;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ContentPart;
import de.subhransu.openrouter.springai.api.dto.ResponsesInputMessage;
import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;

/**
 * Image media on a {@link UserMessage} must reach the wire in both request modes: the
 * chat-completions multimodal parts array and the responses-mode {@code input_image}
 * content items.
 */
class OpenRouterMultimodalContentMapperTests {

	private static final String QUESTION = "What is in this image?";

	private static final String CAT_URL = "https://example.test/cat.png";

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final OpenRouterChatRequestMapper chatMapper = new OpenRouterChatRequestMapper(this.objectMapper);

	private final OpenRouterResponsesRequestMapper responsesMapper = new OpenRouterResponsesRequestMapper(
			this.objectMapper);

	private OpenRouterChatOptions options() {
		return OpenRouterChatOptions.builder().model("google/gemini-3-pro").build();
	}

	@Test
	void mapsUrlImageMediaToImageUrlContentPart() {
		UserMessage message = UserMessage.builder()
			.text(QUESTION)
			.media(Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(URI.create(CAT_URL)).build())
			.build();

		ChatCompletionRequest request = this.chatMapper.map(List.of(message), options(), false, List.of());

		assertThat(request.messages()).hasSize(1);
		assertThat(request.messages().get(0).content()).isInstanceOf(List.class);
		@SuppressWarnings("unchecked")
		List<ContentPart> parts = (List<ContentPart>) request.messages().get(0).content();
		assertThat(parts).hasSize(2);
		assertThat(parts.get(0).type()).isEqualTo("text");
		assertThat(parts.get(0).text()).isEqualTo(QUESTION);
		assertThat(parts.get(1).type()).isEqualTo("image_url");
		assertThat(parts.get(1).imageUrl().url()).isEqualTo(CAT_URL);
	}

	@Test
	void serializesImagePartsWithTheOpenRouterWireShape() {
		UserMessage message = UserMessage.builder()
			.text("describe")
			.media(Media.builder()
				.mimeType(MimeTypeUtils.IMAGE_JPEG)
				.data(URI.create("https://example.test/dog.jpg"))
				.build())
			.build();

		ChatCompletionRequest request = this.chatMapper.map(List.of(message), options(), false, List.of());
		String json = this.objectMapper.writeValueAsString(request);

		assertThat(json).contains("\"type\":\"text\"")
			.contains("\"type\":\"image_url\"")
			.contains("\"image_url\":{\"url\":\"https://example.test/dog.jpg\"}");
	}

	@Test
	void encodesByteBackedImageMediaAsBase64DataUrl() {
		UserMessage message = UserMessage.builder()
			.text("describe")
			.media(Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(new byte[] { 1, 2, 3 }).build())
			.build();

		ChatCompletionRequest request = this.chatMapper.map(List.of(message), options(), false, List.of());

		@SuppressWarnings("unchecked")
		List<ContentPart> parts = (List<ContentPart>) request.messages().get(0).content();
		assertThat(parts.get(1).imageUrl().url()).isEqualTo("data:image/png;base64,AQID");
	}

	@Test
	void keepsPlainStringContentForTextOnlyMessages() {
		ChatCompletionRequest request = this.chatMapper.map(List.of(new UserMessage("just text")), options(), false,
				List.of());

		assertThat(request.messages().get(0).content()).isEqualTo("just text");
	}

	@Test
	void rejectsUnsupportedMediaInsteadOfSilentlyDroppingIt() {
		UserMessage message = UserMessage.builder()
			.text("watch this")
			.media(Media.builder()
				.mimeType(MimeTypeUtils.parseMimeType("application/zip"))
				.data(URI.create("https://example.test/clip.mp4"))
				.build())
			.build();
		List<Message> messages = List.of(message);
		OpenRouterChatOptions options = options();

		assertThatThrownBy(() -> this.chatMapper.map(messages, options, false, List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("application/zip");
	}

	@Test
	void mapsImageMediaToInputImageInResponsesMode() {
		UserMessage message = UserMessage.builder()
			.text(QUESTION)
			.media(Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(URI.create(CAT_URL)).build())
			.build();

		ResponsesRequest request = this.responsesMapper.map(List.of(message), options(), false, List.of());

		@SuppressWarnings("unchecked")
		List<Object> input = (List<Object>) request.input();
		ResponsesInputMessage inputMessage = (ResponsesInputMessage) input.get(0);
		assertThat(inputMessage.content()).hasSize(2);
		assertThat(inputMessage.content().get(0).type()).isEqualTo("input_text");
		assertThat(inputMessage.content().get(0).text()).isEqualTo(QUESTION);
		assertThat(inputMessage.content().get(1).type()).isEqualTo("input_image");
		assertThat(inputMessage.content().get(1).imageUrl()).isEqualTo(CAT_URL);
	}

	@Test
	void keepsSingleInputTextContentForTextOnlyMessagesInResponsesMode() {
		ResponsesRequest request = this.responsesMapper.map(List.of(new UserMessage("just text")), options(), false,
				List.of());

		@SuppressWarnings("unchecked")
		List<Object> input = (List<Object>) request.input();
		ResponsesInputMessage inputMessage = (ResponsesInputMessage) input.get(0);
		assertThat(inputMessage.content()).hasSize(1);
		assertThat(inputMessage.content().get(0).type()).isEqualTo("input_text");
	}

}
