package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.subhransu.openrouter.springai.api.dto.ContentPart;
import de.subhransu.openrouter.springai.api.dto.ResponsesContent;
import java.net.URI;
import java.util.List;
import java.util.Map;
import de.subhransu.openrouter.springai.chat.OpenRouterCacheBreakpoint;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import org.springframework.ai.chat.messages.UserMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.databind.ObjectMapper;

class MediaContentMapperTests {

	private static final String PDF_URL = "https://example.test/report.pdf";

	@ParameterizedTest
	@CsvSource({ "application/pdf,file,input_file", "audio/wav,input_audio,input_audio",
			"audio/mpeg,input_audio,input_audio", "video/mp4,video_url,input_video", "video/mpeg,video_url,input_video",
			"video/mov,video_url,input_video", "video/webm,video_url,input_video" })
	void mapsBytesAndDataUrlsEquivalentlyWithoutMutatingCallerData(String mime, String chatType, String responsesType) {
		byte[] bytes = { 1, 2, 3 };
		Media inline = Media.builder()
			.mimeType(MimeTypeUtils.parseMimeType(mime))
			.name("fixture.pdf")
			.data(bytes)
			.build();
		Media dataUrl = media(mime, "data:" + mime + ";base64,AQID");
		ContentPart chat = MediaContentMapper.chat(inline);
		ResponsesContent responses = MediaContentMapper.responses(inline);
		assertThat(chat.type()).isEqualTo(chatType);
		assertThat(responses.type()).isEqualTo(responsesType);
		assertThat(chat).isEqualTo(MediaContentMapper.chat(dataUrl)).isEqualTo(MediaContentMapper.chat(inline));
		assertThat(responses).isEqualTo(MediaContentMapper.responses(dataUrl));
		assertThat(bytes).containsExactly(1, 2, 3);
		if (chat.inputAudio() != null) {
			assertThat(chat.inputAudio().data()).isEqualTo("AQID");
			assertThat(chat.inputAudio().format()).isEqualTo(mime.equals("audio/wav") ? "wav" : "mp3");
		}
	}

	@Test
	void mapsRemotePdfToEndpointSpecificFieldsAndPreservesFilename() {
		Media pdf = media("application/pdf", PDF_URL);
		assertThat(MediaContentMapper.chat(pdf).file()).isEqualTo(new ContentPart.FileInput("fixture.pdf", PDF_URL));
		ResponsesContent part = MediaContentMapper.responses(pdf);
		assertThat(part.fileUrl()).isEqualTo(PDF_URL);
		assertThat(part.fileData()).isNull();
		assertThat(part.filename()).isEqualTo("fixture.pdf");
	}

	@ParameterizedTest
	@CsvSource({ "application/pdf,data:application/pdf;base64,", "application/pdf,data:application/pdf;base64,!!!",
			"application/pdf,data:audio/wav;base64,AQID", "application/pdf,data:application/pdf,AQID" })
	void rejectsMalformedDataUrls(String mime, String prefix, String payload) {
		assertRejected(media(mime, prefix + "," + (payload == null ? "" : payload)));
	}

	@ParameterizedTest
	@CsvSource({ "audio/wav,https://example.test/clip.wav", "audio/mpeg,https://example.test/clip.mp3",
			"application/pdf,file:///report.pdf", "video/mp4,relative.mp4", "application/pdf,https:/missing-host",
			"audio/ogg,data:audio/ogg;base64,AQID", "audio/mp3,data:audio/mp3;base64,AQID",
			"video/avi,https://example.test/clip.avi", "application/zip,https://example.test/archive.zip" })
	void rejectsUnsupportedMimeTypesAndSources(String mime, String source) {
		assertRejected(media(mime, source));
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, MediaContentMapper.MAX_INLINE_BYTES + 1 })
	void rejectsEmptyAndOversizedBytes(int size) {
		assertRejected(
				Media.builder().mimeType(MimeTypeUtils.parseMimeType("application/pdf")).data(new byte[size]).build());
	}

	@Test
	void rejectsOversizedBase64BeforeDecoding() {
		assertRejected(media("application/pdf",
				"data:application/pdf;base64," + "A".repeat(4 * ((MediaContentMapper.MAX_INLINE_BYTES + 2) / 3) + 4)));
	}

	@Test
	void acceptsTheInlineSizeBoundary() {
		Media media = Media.builder()
			.mimeType(MimeTypeUtils.parseMimeType("audio/wav"))
			.data(new byte[MediaContentMapper.MAX_INLINE_BYTES])
			.build();
		String encoded = MediaContentMapper.chat(media).inputAudio().data();
		assertThat(encoded.length()).isEqualTo(4 * ((MediaContentMapper.MAX_INLINE_BYTES + 2) / 3));
		assertThat(MediaContentMapper.responses(media("audio/wav", "data:audio/wav;base64," + encoded))
			.inputAudio()
			.data()).isEqualTo(encoded);
	}

	@Test
	void preservesAttachmentsAfterCacheBreakpointsAndWithoutText() {
		ObjectMapper json = new ObjectMapper();
		var mapper = new OpenRouterChatRequestMapper(json);
		var options = OpenRouterChatOptions.builder().model("synthetic/model").build();
		Media pdf = media("application/pdf", PDF_URL);
		UserMessage cached = UserMessage.builder()
			.text("prefix tail")
			.media(pdf)
			.metadata(Map.of(OpenRouterCacheBreakpoint.METADATA_KEY, List.of(new OpenRouterCacheBreakpoint(6))))
			.build();
		var request = json.valueToTree(mapper.map(List.of(cached), options, false, List.of()));
		assertThat(request.at("/messages/0/content/2/type").asString()).isEqualTo("file");
		assertThat(request.at("/messages/0/content/0/cache_control/type").asString()).isEqualTo("ephemeral");
		UserMessage attachmentOnly = UserMessage.builder().text("").media(pdf).build();
		var chat = json.valueToTree(mapper.map(List.of(attachmentOnly), options, false, List.of()));
		var responses = json.valueToTree(
				new OpenRouterResponsesRequestMapper(json).map(List.of(attachmentOnly), options, false, List.of()));
		assertThat(chat.at("/messages/0/content").size()).isEqualTo(1);
		assertThat(responses.at("/input/0/content").size()).isEqualTo(1);
	}

	@Test
	void newWireRecordsTolerateUnknownFields() {
		ObjectMapper mapper = new ObjectMapper();
		ContentPart part = mapper.readValue(
				"""
						{"type":"file","file":{"filename":"fixture.pdf","file_data":"data:application/pdf;base64,AQID","future":true},"future":true}
						""",
				ContentPart.class);
		assertThat(part.file().filename()).isEqualTo("fixture.pdf");
		assertThat(
				mapper.readValue("{\"data\":\"AQID\",\"format\":\"wav\",\"future\":true}", ContentPart.AudioInput.class)
					.format())
			.isEqualTo("wav");
		assertThat(
				mapper.readValue("{\"url\":\"https://example.test/v.mp4\",\"future\":true}", ContentPart.VideoUrl.class)
					.url())
			.endsWith("v.mp4");
	}

	private static Media media(String mime, String source) {
		return Media.builder()
			.mimeType(MimeTypeUtils.parseMimeType(mime))
			.name("fixture.pdf")
			.data(URI.create(source))
			.build();
	}

	private static void assertRejected(Media media) {
		assertThatThrownBy(() -> MediaContentMapper.chat(media)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> MediaContentMapper.responses(media)).isInstanceOf(IllegalArgumentException.class);
	}

}
