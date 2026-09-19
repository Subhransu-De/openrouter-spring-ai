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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class MultimodalHttpContractTests {

	@ParameterizedTest
	@CsvSource({ "false,false", "false,true", "true,false", "true,true" })
	void preservesMixedAttachmentsAndConversationOnTheWire(boolean responses, boolean streaming) throws Exception {
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
			UserMessage message = UserMessage.builder()
				.text("Inspect these attachments")
				.media(List.of(inline("application/pdf"), inline("audio/wav"),
						Media.builder()
							.mimeType(MimeTypeUtils.parseMimeType("video/mp4"))
							.data(URI.create("https://example.test/clip.mp4"))
							.build(),
						inline("image/png")))
				.build();
			Prompt prompt = new Prompt(
					List.of(new UserMessage("Earlier question"), new AssistantMessage("Earlier answer"), message),
					OpenRouterChatOptions.builder()
						.model("synthetic/multimodal")
						.requestMode(responses ? OpenRouterRequestMode.OPENAI_RESPONSES
								: OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS)
						.build());
			if (streaming) {
				StepVerifier.create(model.stream(prompt))
					.thenConsumeWhile(value -> true)
					.expectComplete()
					.verify(Duration.ofSeconds(10));
			}
			else {
				model.call(prompt);
			}
			JsonNode request = new ObjectMapper().readTree(captured.get());
			assertThat(request.path("stream").asBoolean()).isEqualTo(streaming);
			JsonNode messages = request.path(responses ? "input" : "messages");
			assertThat(messages.size()).isEqualTo(3);
			assertThat(messages.get(1).path("role").asString()).isEqualTo("assistant");
			JsonNode parts = messages.get(2).path("content");
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
			assertThat(parts).isEqualTo(new ObjectMapper().readTree(expected));
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

}
