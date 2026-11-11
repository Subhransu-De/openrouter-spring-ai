package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.dto.ContentPart;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import java.util.Arrays;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Chat-mode image generation: {@code modalities} and {@code image_config} must reach the
 * wire, and the {@code images} array on assistant messages (sync) and deltas (streaming)
 * must surface as {@link Media} on the assistant message.
 */
class OpenRouterChatImageOutputMapperTests {

	private static final String BASE_URL = "https://openrouter.test/api/v1";

	private static final String DATA_URL = "data:image/png;base64,aW1hZ2Ux";

	private static final String JPEG_URL = "data:image/jpeg;base64,AQID";

	private static final String MODEL = "google/gemini-2.5-flash-image";

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
	void surfacesGeneratedImagesAsAssistantMedia() {
		ChatResponse response = call(syncBody("Here you go", imageEntry(DATA_URL)));

		var message = response.getResult().getOutput();
		assertThat(message.getText()).isEqualTo("Here you go");
		assertThat(message.getMedia()).singleElement().satisfies(media -> {
			assertThat(media.getMimeType()).isEqualTo(MimeTypeUtils.IMAGE_PNG);
			assertThat(media.getData()).isEqualTo(DATA_URL);
		});
	}

	@Test
	void keepsSeveralSyncImagesInOrderAndSkipsEntriesWithoutAUrl() {
		ChatResponse response = call(syncBody("", imageEntry(JPEG_URL), "{\"type\":\"image_url\",\"image_url\":{}}",
				imageEntry("data:image/webp;base64,BAUG")));

		assertThat(response.getResult().getOutput().getMedia()).extracting(Media::getData)
			.containsExactly(JPEG_URL, "data:image/webp;base64,BAUG");
		assertThat(response.getResult().getOutput().getMedia()).extracting(Media::getMimeType)
			.containsExactly(MimeTypeUtils.IMAGE_JPEG, MimeType.valueOf("image/webp"));
	}

	static Stream<Arguments> nonDataImageUrls() {
		return Stream.of(
				Arguments.of(Named.of("remote https URL", "https://example.test/generated.jpg"),
						MimeTypeUtils.IMAGE_PNG),
				Arguments.of(Named.of("relative path", "generated/image.webp"), MimeTypeUtils.IMAGE_PNG),
				Arguments.of(Named.of("data URL without a MIME type", "data:;base64,AQID"), MimeTypeUtils.IMAGE_PNG),
				Arguments.of(Named.of("data URL without parameters", "data:image/jpeg,AQID"), MimeTypeUtils.IMAGE_PNG),
				Arguments.of(Named.of("data URL with an invalid MIME type", "data:not a mime;base64,AQID"),
						MimeTypeUtils.IMAGE_PNG),
				Arguments.of(Named.of("data URL with a valid MIME type", "data:image/gif;base64,AQID"),
						MimeType.valueOf("image/gif")));
	}

	@ParameterizedTest
	@MethodSource("nonDataImageUrls")
	void keepsAnyImageUrlVerbatimAndFallsBackToPngWhenTheMimeTypeIsUnreadable(String url, MimeType expectedMime) {
		ChatResponse response = call(syncBody("", imageEntry(url)));

		assertThat(response.getResult().getOutput().getMedia()).singleElement().satisfies(media -> {
			assertThat(media.getData()).isEqualTo(url);
			assertThat(media.getMimeType()).isEqualTo(expectedMime);
		});
	}

	@Test
	void surfacesStreamedDeltaImagesAsAssistantMedia() {
		List<ChatResponse> responses = stream(chunk("", imageEntry(DATA_URL)));

		assertThat(responses).flatExtracting(response -> response.getResult().getOutput().getMedia())
			.singleElement()
			.satisfies(media -> {
				assertThat(media.getData()).isEqualTo(DATA_URL);
				assertThat(media.getMimeType()).isEqualTo(MimeTypeUtils.IMAGE_PNG);
			});
	}

	@Test
	void collectsImagesFromSeveralStreamedChunksInArrivalOrder() {
		List<ChatResponse> responses = stream(
				chunk("Here ", imageEntry(JPEG_URL), imageEntry("data:image/png;base64,BAUG")), chunk("", ""),
				chunk("you go", imageEntry("https://example.test/third.webp")));

		assertThat(responses).flatExtracting(response -> response.getResult().getOutput().getMedia())
			.extracting(Media::getData)
			.containsExactly(JPEG_URL, "data:image/png;base64,BAUG", "https://example.test/third.webp");
		assertThat(responses).flatExtracting(response -> response.getResult().getOutput().getMedia())
			.extracting(Media::getMimeType)
			.containsExactly(MimeTypeUtils.IMAGE_JPEG, MimeTypeUtils.IMAGE_PNG, MimeTypeUtils.IMAGE_PNG);
		assertThat(responses).extracting(response -> response.getResult().getOutput().getText())
			.containsExactly("Here ", "", "you go");
	}

	@Test
	void ignoresAbsentImagesArray() {
		ChatResponse response = call("""
				{
				  "id": "gen-1",
				  "model": "openai/gpt-5.4-mini",
				  "choices": [
				    {"index": 0, "message": {"role": "assistant", "content": "text only"}, "finish_reason": "stop"}
				  ]
				}
				""");

		assertThat(response.getResult().getOutput().getText()).isEqualTo("text only");
		assertThat(response.getResult().getOutput().getMedia()).isEmpty();
	}

	private static String imageEntry(String url) {
		return "{\"type\":\"image_url\",\"image_url\":{\"url\":\"" + url + "\"}}";
	}

	private static String syncBody(String text, String... images) {
		return """
				{
				  "id": "gen-img-1",
				  "model": "%s",
				  "choices": [
				    {
				      "index": 0,
				      "message": {"role": "assistant", "content": "%s", "images": [%s]},
				      "finish_reason": "stop"
				    }
				  ]
				}
				""".formatted(MODEL, text, String.join(",", images));
	}

	private static String chunk(String text, String... images) {
		return "data: {\"id\":\"gen-img-1\",\"object\":\"chat.completion.chunk\",\"model\":\"" + MODEL
				+ "\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + text + "\",\"images\":["
				+ String.join(",", images) + "]}}]}\n\n";
	}

	private static Prompt prompt() {
		return new Prompt("draw", OpenRouterChatOptions.builder().model(MODEL).build());
	}

	private static ChatResponse call(String body) {
		RestClient.Builder rest = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
		server.expect(requestTo(BASE_URL + "/chat/completions"))
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey("test-key")
			.baseUrl(BASE_URL)
			.restClientBuilder(rest)
			.build();
		ChatResponse response = OpenRouterChatModel.builder().openRouterApi(api).build().call(prompt());
		server.verify();
		return response;
	}

	private static List<ChatResponse> stream(String... chunks) {
		String body = String.join("", chunks) + "data: [DONE]\n\n";
		WebClient.Builder web = WebClient.builder()
			.exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
				.header("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE)
				.body(body)
				.build()));
		OpenRouterApi api = OpenRouterApi.builder().apiKey("test-key").baseUrl(BASE_URL).webClientBuilder(web).build();
		return OpenRouterChatModel.builder()
			.openRouterApi(api)
			.build()
			.stream(prompt())
			.collectList()
			.block(Duration.ofSeconds(10));
	}

}
