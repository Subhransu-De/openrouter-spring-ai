package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import de.subhransu.openrouter.springai.api.dto.ContentPart;
import de.subhransu.openrouter.springai.api.dto.ResponsesOutputItem;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.databind.ObjectMapper;

class GeneratedImageReuseTests {

	private final ObjectMapper json = new ObjectMapper();

	private final OpenRouterChatOptions options = OpenRouterChatOptions.builder().model("synthetic/model").build();

	@Test
	void chatImagesSkipMissingPartsAndUrlsWhilePreservingOrder() {
		var images = Arrays.asList(null, ContentPart.text("synthetic text"),
				new ContentPart("image_url", null, new ContentPart.ImageUrl(null)),
				ContentPart.image("data:image/jpeg;base64,AQID"), ContentPart.image("https://example.com/image.png"));
		var media = GeneratedImageMapper.media(images);
		assertThat(media).extracting(Media::getData)
			.containsExactly("data:image/jpeg;base64,AQID", "https://example.com/image.png");
		assertThat(media).extracting(Media::getMimeType)
			.containsExactly(MimeTypeUtils.IMAGE_JPEG, MimeTypeUtils.IMAGE_PNG);
	}

	@ParameterizedTest
	@ValueSource(strings = { "png", "jpeg", "webp" })
	void typedImageItemsRetainTheirOutputFormat(String format) {
		ResponsesOutputItem item = new ResponsesOutputItem("image-1", "image_generation_call", "completed", null, null,
				null, null, null, "AQID", format, null);
		assertThat(GeneratedImageMapper.responsesMedia(List.of(item)).get(0).getData())
			.isEqualTo("data:image/" + format + ";base64,AQID");
		ResponsesOutputItem decoded = this.json.readValue(this.json.writeValueAsString(item),
				ResponsesOutputItem.class);
		assertThat(decoded.outputFormat()).isEqualTo(format);
		ResponsesOutputItem rebuilt = new ResponsesOutputItem(decoded.id(), decoded.type(), decoded.status(),
				decoded.role(), decoded.content(), decoded.callId(), decoded.name(), decoded.arguments(),
				decoded.result(), decoded.outputFormat(), null);
		assertThat(GeneratedImageMapper.responsesMedia(List.of(rebuilt)).get(0).getMimeType())
			.isEqualTo(MimeTypeUtils.parseMimeType("image/" + format));
	}

	@Test
	void dataUrlFormatTakesPrecedenceAndMissingFormatDefaultsToPng() {
		ResponsesOutputItem item = this.json.readValue("""
				{"type":"image_generation_call","result":"data:image/jpeg;base64,AQID","output_format":"png"}
				""", ResponsesOutputItem.class);
		assertThat(GeneratedImageMapper.responsesMedia(List.of(item)).get(0).getMimeType())
			.isEqualTo(MimeTypeUtils.IMAGE_JPEG);
		ResponsesOutputItem plain = new ResponsesOutputItem("image-1", "image_generation_call", "completed", null, null,
				null, null, null, "AQID");
		assertThat(GeneratedImageMapper.responsesMedia(List.of(plain)).get(0).getData())
			.isEqualTo("data:image/png;base64,AQID");
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void rejectsUnsupportedAssistantMediaEvenWithReasoningReplayMetadata(boolean stream) {
		AssistantMessage assistant = AssistantMessage.builder()
			.content("")
			.media(List
				.of(Media.builder().mimeType(MimeTypeUtils.parseMimeType("audio/wav")).data(new byte[] { 1 }).build()))
			.properties(Map.of(ReasoningMetadata.RESPONSES_ITEMS, List.of("synthetic reasoning"),
					ReasoningMetadata.RESPONSES_OUTPUT_ITEMS, List.of("synthetic output")))
			.build();
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new OpenRouterChatRequestMapper(this.json).map(List.of(assistant), this.options, stream,
					List.of()))
			.withMessageContaining("audio replay is unsupported");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new OpenRouterResponsesRequestMapper(this.json).map(List.of(assistant), this.options,
					stream, List.of()))
			.withMessageContaining("audio replay is unsupported");
	}

	@ParameterizedTest
	@ValueSource(strings = { "png", "jpeg", "webp" })
	void responsesImagesCanBeReusedAsUserInput(String format) {
		for (String result : List.of("aW1hZ2U=", "data:image/" + format + ";base64,aW1hZ2U=",
				"https://example.com/image." + format)) {
			ResponsesOutputItem item = this.json.readValue("""
					{"type":"image_generation_call","result":"%s","output_format":"%s"}
					""".formatted(result, format), ResponsesOutputItem.class);
			var sync = new OpenRouterResponsesResponseMapper().map(new ResponsesResult("response-1", "response", 0L,
					"synthetic/model", "completed", List.of(item), null, null));
			var streamed = new OpenRouterResponsesStreamingResponseMapper().map(this.json.readValue(
					"{\"type\":\"response.output_item.done\",\"item\":" + this.json.writeValueAsString(item) + "}",
					ResponsesStreamEvent.class));
			Media media = sync.getResult().getOutput().getMedia().get(0);
			assertThat(streamed.getResult().getOutput().getMedia().get(0).getData()).isEqualTo(media.getData());
			assertThat(streamed.getResult().getOutput().getMedia().get(0).getMimeType()).isEqualTo(media.getMimeType());
			assertThat(media.getMimeType()).isEqualTo(MimeTypeUtils.parseMimeType("image/" + format));
			String expected = result.equals("aW1hZ2U=") ? "data:image/" + format + ";base64," + result : result;
			UserMessage user = UserMessage.builder().text("Edit this image").media(media).build();
			for (boolean stream : List.of(false, true)) {
				assertThat(this.json
					.valueToTree(new OpenRouterChatRequestMapper(this.json).map(List.of(user), this.options, stream,
							List.of()))
					.at("/messages/0/content/1/image_url/url")
					.asString()).isEqualTo(expected);
				assertThat(this.json
					.valueToTree(new OpenRouterResponsesRequestMapper(this.json).map(List.of(user), this.options,
							stream, List.of()))
					.at("/input/0/content/1/image_url")
					.asString()).isEqualTo(expected);
			}
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void assistantImagesArePreservedOrExplicitlyRejected(boolean stream) {
		Media media = Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(new byte[] { 1, 2, 3 }).build();
		AssistantMessage assistant = AssistantMessage.builder()
			.content("Generated image")
			.media(List.of(media))
			.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "inspect", "{}")))
			.properties(Map.of(ReasoningMetadata.REASONING, "Synthetic reasoning"))
			.build();
		var request = new OpenRouterChatRequestMapper(this.json).map(List.of(assistant), this.options, stream,
				List.of());
		assertThat(this.json.valueToTree(request).at("/messages/0/images/0/image_url/url").asString())
			.isEqualTo("data:image/png;base64,AQID");
		assertThat(request.messages().get(0).content()).isEqualTo("Generated image");
		assertThat(request.messages().get(0).toolCalls()).hasSize(1);
		assertThat(request.messages().get(0).reasoning()).isEqualTo("Synthetic reasoning");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new OpenRouterResponsesRequestMapper(this.json).map(List.of(assistant), this.options,
					stream, List.of()))
			.withMessageContaining("UserMessage");
	}

}
