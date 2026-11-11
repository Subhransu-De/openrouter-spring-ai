package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class MultimodalHttpContractTests {

	private static final ObjectMapper JSON = new ObjectMapper();

	@ParameterizedTest(name = "{0}")
	@MethodSource("mixedAttachmentModes")
	void preservesMixedAttachmentsAndConversationOnTheWire(boolean responses, boolean streaming) throws Exception {
		Media video = Media.builder()
			.mimeType(MimeTypeUtils.parseMimeType("video/mp4"))
			.data(URI.create("https://example.test/clip.mp4"))
			.build();
		UserMessage message = UserMessage.builder()
			.text("Inspect these attachments")
			.media(List.of(inline("application/pdf"), inline("audio/wav"), video, inline("image/png")))
			.build();
		Prompt prompt = new Prompt(
				List.of(new UserMessage("Earlier question"), new AssistantMessage("Earlier answer"), message),
				options(responses));

		JsonNode request = send(prompt, responses, streaming);

		assertThat(request.path("stream").asBoolean()).isEqualTo(streaming);
		JsonNode messages = request.path(responses ? "input" : "messages");
		assertThat(messages.size()).isEqualTo(3);
		assertThat(messages.get(1).path("role").asString()).isEqualTo("assistant");
		String expected = responses ? """
				[{"type":"input_text","text":"Inspect these attachments"},
				 {"type":"input_file","filename":"fixture.pdf","file_data":"data:application/pdf;base64,AQID"},
				 {"type":"input_audio","input_audio":{"data":"AQID","format":"wav"}},
				 {"type":"input_video","video_url":"https://example.test/clip.mp4"},
				 {"type":"input_image","image_url":"data:image/png;base64,AQID"}]
				""" : """
				[{"type":"text","text":"Inspect these attachments"},
				 {"type":"file","file":{"filename":"fixture.pdf","file_data":"data:application/pdf;base64,AQID"}},
				 {"type":"input_audio","input_audio":{"data":"AQID","format":"wav"}},
				 {"type":"video_url","video_url":{"url":"https://example.test/clip.mp4"}},
				 {"type":"image_url","image_url":{"url":"data:image/png;base64,AQID"}}]
				""";
		assertThat(messages.get(2).path("content")).isEqualTo(JSON.readTree(expected));
	}

	static Stream<Arguments> mixedAttachmentModes() {
		return Stream.of(Arguments.of(Named.of("Chat Completions, sync", false), false),
				Arguments.of(Named.of("Chat Completions, streaming", false), true),
				Arguments.of(Named.of("Responses, sync", true), false),
				Arguments.of(Named.of("Responses, streaming", true), true));
	}

	static Stream<Arguments> exactAttachmentWireShapes() {
		String mp3 = """
				{"type":"input_audio","input_audio":{"data":"AQID","format":"mp3"}}
				""";
		return Stream.of(Arguments.of(Named.of("Chat Completions audio/mpeg bytes", false), inline("audio/mpeg"), mp3),
				Arguments.of(Named.of("Responses audio/mpeg bytes", true), inline("audio/mpeg"), mp3),
				Arguments.of(Named.of("Chat Completions video/mpeg URL", false), remote("video/mpeg", "mpeg"), """
						{"type":"video_url","video_url":{"url":"https://example.test/clip.mpeg"}}
						"""), Arguments.of(Named.of("Responses video/mpeg URL", true), remote("video/mpeg", "mpeg"), """
						{"type":"input_video","video_url":"https://example.test/clip.mpeg"}
						"""),
				Arguments.of(Named.of("Chat Completions video/mov URL", false), remote("video/mov", "mov"), """
						{"type":"video_url","video_url":{"url":"https://example.test/clip.mov"}}
						"""), Arguments.of(Named.of("Responses video/mov URL", true), remote("video/mov", "mov"), """
						{"type":"input_video","video_url":"https://example.test/clip.mov"}
						"""),
				Arguments.of(Named.of("Chat Completions video/webm URL", false), remote("video/webm", "webm"), """
						{"type":"video_url","video_url":{"url":"https://example.test/clip.webm"}}
						"""), Arguments.of(Named.of("Responses video/webm URL", true), remote("video/webm", "webm"), """
						{"type":"input_video","video_url":"https://example.test/clip.webm"}
						"""),
				Arguments.of(Named.of("Chat Completions video/webm bytes", false), inline("video/webm"), """
						{"type":"video_url","video_url":{"url":"data:video/webm;base64,AQID"}}
						"""), Arguments.of(Named.of("Responses video/webm bytes", true), inline("video/webm"), """
						{"type":"input_video","video_url":"data:video/webm;base64,AQID"}
						"""), Arguments.of(Named.of("Chat Completions video/mov bytes", false), inline("video/mov"), """
						{"type":"video_url","video_url":{"url":"data:video/mov;base64,AQID"}}
						"""), Arguments.of(Named.of("Responses video/mpeg bytes", true), inline("video/mpeg"), """
						{"type":"input_video","video_url":"data:video/mpeg;base64,AQID"}
						"""));
	}

	static Stream<Arguments> exactImageWireShapes() {
		return Stream.of(Arguments.of(Named.of("Chat Completions image/png bytes", false), inline("image/png"), """
				{"type":"image_url","image_url":{"url":"data:image/png;base64,AQID"}}
				"""), Arguments.of(Named.of("Responses image/png bytes", true), inline("image/png"), """
				{"type":"input_image","image_url":"data:image/png;base64,AQID"}
				"""), Arguments.of(Named.of("Chat Completions image/jpeg bytes", false), inline("image/jpeg"), """
				{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,AQID"}}
				"""), Arguments.of(Named.of("Responses image/jpeg bytes", true), inline("image/jpeg"), """
				{"type":"input_image","image_url":"data:image/jpeg;base64,AQID"}
				"""), Arguments.of(Named.of("Chat Completions image/webp bytes", false), inline("image/webp"), """
				{"type":"image_url","image_url":{"url":"data:image/webp;base64,AQID"}}
				"""), Arguments.of(Named.of("Responses image/webp bytes", true), inline("image/webp"), """
				{"type":"input_image","image_url":"data:image/webp;base64,AQID"}
				"""),
				Arguments.of(Named.of("Chat Completions https image/png URL", false), remoteImage("image/png", "png"),
						"""
								{"type":"image_url","image_url":{"url":"https://example.test/picture.png"}}
								"""),
				Arguments.of(Named.of("Responses https image/png URL", true), remoteImage("image/png", "png"), """
						{"type":"input_image","image_url":"https://example.test/picture.png"}
						"""),
				Arguments.of(Named.of("Chat Completions https image/webp URL", false),
						remoteImage("image/webp", "webp"), """
								{"type":"image_url","image_url":{"url":"https://example.test/picture.webp"}}
								"""),
				Arguments.of(Named.of("Responses https image/webp URL", true), remoteImage("image/webp", "webp"), """
						{"type":"input_image","image_url":"https://example.test/picture.webp"}
						"""));
	}

	@ParameterizedTest
	@MethodSource("exactImageWireShapes")
	void sendsTheExactWireJsonForInlineAndRemoteImages(boolean responses, Media media, String expectedPart)
			throws Exception {
		assertExactPart(responses, media, expectedPart);
	}

	@ParameterizedTest
	@MethodSource("exactAttachmentWireShapes")
	void sendsTheExactWireJsonForEverySupportedAudioAndVideoType(boolean responses, Media media, String expectedPart)
			throws Exception {
		assertExactPart(responses, media, expectedPart);
	}

	private void assertExactPart(boolean responses, Media media, String expectedPart) throws Exception {
		UserMessage message = UserMessage.builder().text("Describe it").media(media).build();

		JsonNode request = send(new Prompt(List.of(message), options(responses)), responses, false);

		JsonNode parts = request.path(responses ? "input" : "messages").get(0).path("content");
		assertThat(parts.size()).isEqualTo(2);
		assertThat(parts.get(0).path("type").asString()).isEqualTo(responses ? "input_text" : "text");
		assertThat(parts.get(0).path("text").asString()).isEqualTo("Describe it");
		assertThat(parts.get(1)).isEqualTo(JSON.readTree(expectedPart));
	}

	private static OpenRouterChatOptions options(boolean responses) {
		return OpenRouterChatOptions.builder()
			.model("synthetic/multimodal")
			.requestMode(
					responses ? OpenRouterRequestMode.OPENAI_RESPONSES : OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS)
			.build();
	}

	private static JsonNode send(Prompt prompt, boolean responses, boolean streaming) throws Exception {
		AtomicReference<String> captured = new AtomicReference<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		String path = responses ? "/responses" : "/chat/completions";
		server.createContext(path, exchange -> {
			captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			String body = responses ? "{\"id\":\"resp-test\",\"status\":\"completed\",\"output\":[]}"
					: "{\"id\":\"chat-test\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";
			if (streaming) {
				body = responses ? "data: {\"type\":\"response.completed\",\"response\":" + body + "}\n\n"
						: "data: [DONE]\n\n";
			}
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			try (var output = exchange.getResponseBody()) {
				output.write(bytes);
			}
		});
		server.start();
		try {
			OpenRouterApi api = OpenRouterApi.builder()
				.apiKey("synthetic-test-key")
				.baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
				.build();
			OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();
			if (streaming) {
				StepVerifier.create(model.stream(prompt))
					.thenConsumeWhile(value -> true)
					.expectComplete()
					.verify(Duration.ofSeconds(10));
			}
			else {
				model.call(prompt);
			}
			return JSON.readTree(captured.get());
		}
		finally {
			server.stop(0);
		}
	}

	private static Media inline(String mime) {
		return Media.builder()
			.mimeType(MimeTypeUtils.parseMimeType(mime))
			.name("fixture.pdf")
			.data(new byte[] { 1, 2, 3 })
			.build();
	}

	private static Media remoteImage(String mime, String extension) {
		return Media.builder()
			.mimeType(MimeTypeUtils.parseMimeType(mime))
			.data(URI.create("https://example.test/picture." + extension))
			.build();
	}

	private static Media remote(String mime, String extension) {
		return Media.builder()
			.mimeType(MimeTypeUtils.parseMimeType(mime))
			.data(URI.create("https://example.test/clip." + extension))
			.build();
	}

}
