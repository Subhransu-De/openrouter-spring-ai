package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import de.subhransu.openrouter.springai.api.dto.ContentPart;
import de.subhransu.openrouter.springai.api.dto.ResponsesOutputItem;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;
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

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "png", "jpeg", "webp" })
	void typedImageItemsRetainTheirOutputFormat(String format) {
		ResponsesOutputItem item = new ResponsesOutputItem("image-1", "image_generation_call", "completed", null, null,
				null, null, null, "AQID", format, null);
		Media media = GeneratedImageMapper.responsesMedia(List.of(item)).get(0);
		assertThat(media.getData()).isEqualTo("data:image/" + format + ";base64,AQID");
		assertThat(media.getMimeType()).isEqualTo(MimeTypeUtils.parseMimeType("image/" + format));
		ResponsesOutputItem decoded = this.json.readValue(this.json.writeValueAsString(item),
				ResponsesOutputItem.class);
		assertThat(decoded.outputFormat()).isEqualTo(format);
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

	@ParameterizedTest(name = "stream = {0}")
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

	static Stream<Arguments> nonImageAssistantMedia() {
		return Stream.of("application/pdf", "video/mp4")
			.flatMap(mime -> Stream.of(false, true).map(stream -> Arguments.of(mime, stream)));
	}

	@ParameterizedTest(name = "{0}, stream = {1}")
	@MethodSource("nonImageAssistantMedia")
	void rejectsPdfAndVideoAssistantHistoryInBothRequestModes(String mime, boolean stream) {
		AssistantMessage assistant = AssistantMessage.builder()
			.content("")
			.media(List.of(Media.builder().mimeType(MimeTypeUtils.parseMimeType(mime)).data(new byte[] { 1 }).build()))
			.build();

		assertThatIllegalArgumentException()
			.isThrownBy(() -> new OpenRouterChatRequestMapper(this.json).map(List.of(assistant), this.options, stream,
					List.of()))
			.withMessageContaining("accepts only image media")
			.withMessageContaining(mime);
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new OpenRouterResponsesRequestMapper(this.json).map(List.of(assistant), this.options,
					stream, List.of()))
			.withMessageContaining("does not support assistant media history");
	}

	static Stream<Arguments> generatedImageResults() {
		return Stream.of("png", "jpeg", "webp")
			.flatMap(format -> Stream.of(
					Arguments.of(Named.of("bare base64 " + format, format), "aW1hZ2U=",
							"data:image/" + format + ";base64,aW1hZ2U="),
					Arguments.of(Named.of("data URL " + format, format), "data:image/" + format + ";base64,aW1hZ2U=",
							"data:image/" + format + ";base64,aW1hZ2U="),
					Arguments.of(Named.of("remote URL " + format, format), "https://example.com/image." + format,
							"https://example.com/image." + format)));
	}

	@ParameterizedTest
	@MethodSource("generatedImageResults")
	void syncAndStreamedResponsesImagesAgreeAndKeepTheirMimeType(String format, String result, String expected) {
		ResponsesOutputItem item = outputItem(result, format);
		Media media = syncMedia(item);
		Media streamed = streamedMedia(item);
		assertThat(streamed.getData()).isEqualTo(media.getData()).isEqualTo(expected);
		assertThat(streamed.getMimeType()).isEqualTo(media.getMimeType())
			.isEqualTo(MimeTypeUtils.parseMimeType("image/" + format));
	}

	static Stream<Arguments> reuseCases() {
		return generatedImageResults().flatMap(args -> Stream.of(false, true)
			.map(stream -> Arguments.of(args.get()[0], args.get()[1], args.get()[2], stream)));
	}

	@ParameterizedTest(name = "{0} as user input, stream = {3}")
	@MethodSource("reuseCases")
	void responsesImagesCanBeReusedAsUserInputInBothRequestModes(String format, String result, String expected,
			boolean stream) {
		Media media = syncMedia(outputItem(result, format));
		UserMessage user = UserMessage.builder().text("Edit this image").media(media).build();
		var chat = this.json.valueToTree(
				new OpenRouterChatRequestMapper(this.json).map(List.of(user), this.options, stream, List.of()));
		assertThat(chat.at("/messages/0/content/0/type").asString()).isEqualTo("text");
		assertThat(chat.at("/messages/0/content/0/text").asString()).isEqualTo("Edit this image");
		assertThat(chat.at("/messages/0/content/1/type").asString()).isEqualTo("image_url");
		assertThat(chat.at("/messages/0/content/1/image_url/url").asString()).isEqualTo(expected);
		var responses = this.json.valueToTree(
				new OpenRouterResponsesRequestMapper(this.json).map(List.of(user), this.options, stream, List.of()));
		assertThat(responses.at("/input/0/content/0/type").asString()).isEqualTo("input_text");
		assertThat(responses.at("/input/0/content/0/text").asString()).isEqualTo("Edit this image");
		assertThat(responses.at("/input/0/content/1/type").asString()).isEqualTo("input_image");
		assertThat(responses.at("/input/0/content/1/image_url").asString()).isEqualTo(expected);
	}

	private ResponsesOutputItem outputItem(String result, String format) {
		return this.json.readValue("""
				{"type":"image_generation_call","result":"%s","output_format":"%s"}
				""".formatted(result, format), ResponsesOutputItem.class);
	}

	private Media syncMedia(ResponsesOutputItem item) {
		return ResponseMappers.responses()
			.map(new ResponsesResult("response-1", "response", 0L, "synthetic/model", "completed", List.of(item), null,
					null))
			.getResult()
			.getOutput()
			.getMedia()
			.get(0);
	}

	private Media streamedMedia(ResponsesOutputItem item) {
		return ResponseMappers
			.responsesEvent(this.json.readValue("{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":"
					+ this.json.writeValueAsString(item) + "}", ResponsesStreamEvent.class))
			.getResult()
			.getOutput()
			.getMedia()
			.get(0);
	}

	@Test
	void partialImageEventsProduceNoMediaAndOnlyTheDoneItemDoes() {
		var partial = this.json.readValue("""
				{"type":"response.image_generation_call.partial_image","output_index":0,"item_id":"image-1",
				 "partial_image_index":0,"partial_image_b64":"cGFydGlhbA=="}
				""", ResponsesStreamEvent.class);
		var done = this.json.readValue("""
				{"type":"response.output_item.done","output_index":0,"item":
				 {"type":"image_generation_call","id":"image-1","result":"AQID","output_format":"png"}}
				""", ResponsesStreamEvent.class);

		List<ChatResponse> responses = ResponseMappers.responsesStreaming()
			.map(Flux.just(partial, partial, done))
			.collectList()
			.block(Duration.ofSeconds(10));

		assertThat(responses).hasSize(3);
		assertThat(responses.subList(0, 2))
			.allSatisfy(response -> assertThat(response.getResult().getOutput().getMedia())
				.as("partial previews are not final images")
				.isEmpty());
		assertThat(responses.get(2).getResult().getOutput().getMedia()).extracting(Media::getData)
			.containsExactly("data:image/png;base64,AQID");
	}

	@ParameterizedTest(name = "stream = {0}")
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
