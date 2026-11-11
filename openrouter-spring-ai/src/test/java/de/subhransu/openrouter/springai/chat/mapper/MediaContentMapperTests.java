package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import de.subhransu.openrouter.springai.api.dto.ContentPart;
import de.subhransu.openrouter.springai.api.dto.ResponsesContent;
import de.subhransu.openrouter.springai.chat.OpenRouterCacheBreakpoint;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.databind.ObjectMapper;

class MediaContentMapperTests {

	private static final String INVALID_BASE64 = "Invalid base64 media content";

	private static final String SIZE_RANGE = "Inline media must contain between 1 byte and 20 MiB";

	private static final String OVERSIZED = "Inline media exceeds 20 MiB";

	private static final String DECLARED_MIME = "Media data URL must use its declared MIME type and base64 encoding";

	private static final String HTTP_URL_ONLY = "Media URL must be an absolute HTTP(S) URL";

	private static final String AUDIO_URL = "Audio input requires bytes or a base64 data URL; audio URLs are unsupported";

	private static final String PDF_URL = "https://example.test/report.pdf";

	static Stream<Arguments> prefixesAndSizes() {
		List<Named<String>> prefixes = List.of(Named.of("no prefix", ""),
				Named.of("data URL prefix", "data:image/png;base64,"),
				Named.of("prefix with 2-byte character", "data:image/png;name=é;base64,"),
				Named.of("prefix above Latin-1", "data:image/png;name=λ;base64,"));
		return prefixes.stream()
			.flatMap(prefix -> Stream.of(0, 1, 2, 3, 4, 7, 8, 1023, 1024, 1_048_576)
				.map(size -> Arguments.of(prefix, size)));
	}

	@ParameterizedTest(name = "{0}, {1} bytes")
	@MethodSource("prefixesAndSizes")
	void prefixedBase64MatchesStandardEncodingWithoutChangingInput(String prefix, int size) {
		byte[] data = new byte[size];
		new Random(42).nextBytes(data);
		byte[] original = data.clone();
		assertThat(MediaUrlMapper.dataUrl(prefix, data))
			.isEqualTo(prefix + Base64.getEncoder().encodeToString(original));
		assertThat(data).isEqualTo(original);
	}

	@ParameterizedTest(name = "{0} maps to {1} and {2}, filename {3}")
	@CsvSource({ "application/pdf,file,input_file,fixture.pdf", "audio/wav,input_audio,input_audio,",
			"audio/mpeg,input_audio,input_audio,", "video/mp4,video_url,input_video,",
			"video/mpeg,video_url,input_video,", "video/mov,video_url,input_video,",
			"video/webm,video_url,input_video," })
	void mapsBytesAndDataUrlsEquivalentlyWithoutMutatingCallerData(String mime, String chatType, String responsesType,
			String expectedFilename) {
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
		assertThat(Optional.ofNullable(chat.file()).map(ContentPart.FileInput::filename).orElse(null))
			.as("filename sent on Chat Completions")
			.isEqualTo(expectedFilename);
		assertThat(responses.filename()).as("filename sent on Responses").isEqualTo(expectedFilename);
		assertThat(chat).isEqualTo(MediaContentMapper.chat(dataUrl));
		assertThat(responses).isEqualTo(MediaContentMapper.responses(dataUrl));
		assertThat(bytes).containsExactly(1, 2, 3);
	}

	@ParameterizedTest(name = "{0} is sent as {1}")
	@CsvSource({ "audio/wav,wav", "audio/mpeg,mp3" })
	void mapsAudioToTheFormatNameOfItsMimeType(String mime, String format) {
		Media inline = Media.builder().mimeType(MimeTypeUtils.parseMimeType(mime)).data(new byte[] { 1, 2, 3 }).build();
		assertThat(MediaContentMapper.chat(inline).inputAudio()).isEqualTo(new ContentPart.AudioInput("AQID", format));
		assertThat(MediaContentMapper.responses(inline).inputAudio())
			.isEqualTo(new ContentPart.AudioInput("AQID", format));
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
	@ValueSource(strings = { "AQ==", "AQ", "AQI=", "AQI", "AQID" })
	void preservesPaddedAndUnpaddedAudioPayloads(String payload) {
		Media inline = media("audio/wav", "data:audio/wav;base64," + payload);
		assertThat(MediaContentMapper.chat(inline).inputAudio().data()).isEqualTo(payload);
		assertThat(MediaContentMapper.responses(inline).inputAudio().data()).isEqualTo(payload);
	}

	static Stream<Arguments> malformedPayloads() {
		return Stream.of("application/pdf", "audio/wav", "video/mp4")
			.flatMap(mime -> Stream.of("AQ===", "AQ==tail", "Ā", "😀", "!!!")
				.map(payload -> Arguments.of(mime, payload)));
	}

	@ParameterizedTest(name = "{0} with payload {1}")
	@MethodSource("malformedPayloads")
	void preservesBase64DecodingDiagnosticsInBothRequestModes(String mime, String payload) {
		Throwable expected = catchThrowable(() -> Base64.getDecoder().decode(payload));
		Media invalid = media(mime, "data:" + mime + ";base64," + payload);
		assertThatThrownBy(() -> MediaContentMapper.chat(invalid)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage(INVALID_BASE64)
			.hasCauseInstanceOf(IllegalArgumentException.class)
			.hasRootCauseMessage(expected.getMessage());
		assertThatThrownBy(() -> MediaContentMapper.responses(invalid)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage(INVALID_BASE64)
			.hasCauseInstanceOf(IllegalArgumentException.class)
			.hasRootCauseMessage(expected.getMessage());
	}

	@ParameterizedTest(name = "{3}")
	@CsvSource(delimiter = '|',
			value = { "application/pdf|data:application/pdf;base64,|" + SIZE_RANGE + "|empty payload",
					"application/pdf|data:application/pdf;base64,!!!|" + INVALID_BASE64 + "|non-base64 payload",
					"application/pdf|data:audio/wav;base64,AQID|" + DECLARED_MIME
							+ "|data URL declares another MIME type",
					"application/pdf|data:application/pdf,AQID|" + DECLARED_MIME + "|data URL without base64 marker" })
	void rejectsMalformedDataUrls(String mime, String source, String message, String scenario) {
		assertRejected(media(mime, source), message);
	}

	@ParameterizedTest(name = "{3}")
	@CsvSource(delimiter = '|', value = { "audio/wav|https://example.test/clip.wav|" + AUDIO_URL + "|audio URL",
			"audio/mpeg|https://example.test/clip.mp3|" + AUDIO_URL + "|MPEG audio URL",
			"application/pdf|file:///report.pdf|" + HTTP_URL_ONLY + "|non-HTTP scheme",
			"video/mp4|relative.mp4|" + HTTP_URL_ONLY + "|relative URL",
			"application/pdf|https:/missing-host|" + HTTP_URL_ONLY + "|URL without a host",
			"audio/ogg|data:audio/ogg;base64,AQID|Unsupported input media MIME type: audio/ogg|unsupported audio type",
			"audio/mp3|data:audio/mp3;base64,AQID|Unsupported input media MIME type: audio/mp3|non-standard MP3 type",
			"video/avi|https://example.test/clip.avi|Unsupported input media MIME type: video/avi|unsupported video type",
			"application/zip|https://example.test/archive.zip|Unsupported input media MIME type: application/zip|unsupported archive type" })
	void rejectsUnsupportedMimeTypesAndSources(String mime, String source, String message, String scenario) {
		assertRejected(media(mime, source), message);
	}

	@ParameterizedTest(name = "{1}")
	@CsvSource(delimiter = '|',
			value = { "file:///photo.png|non-HTTP scheme", "relative.png|relative URL",
					"https:/missing-host|URL without a host", "data:image/png;base64,!!!|non-base64 data URL",
					"data:image/jpeg;base64,AQID|data URL declaring another MIME type" })
	void imageSourcesAreForwardedUnvalidatedBecauseOnlyOtherMediaKindsAreCheckedLocally(String source,
			String scenario) {
		// Unlike pdf, audio and video, image values are handed to the provider as given,
		// so none of the rejections above apply to them in either request mode.
		Media image = media("image/png", source);

		assertThat(MediaContentMapper.chat(image).imageUrl().url()).isEqualTo(source);
		assertThat(MediaContentMapper.responses(image).imageUrl()).isEqualTo(source);
	}

	@ParameterizedTest(name = "{0} bytes")
	@ValueSource(ints = { 0, MediaContentMapper.MAX_INLINE_BYTES + 1 })
	void rejectsEmptyAndOversizedBytes(int size) {
		assertRejected(
				Media.builder().mimeType(MimeTypeUtils.parseMimeType("application/pdf")).data(new byte[size]).build(),
				SIZE_RANGE);
	}

	@Test
	void rejectsOversizedBase64BeforeDecoding() {
		// The payload is not valid base64, so a decode attempt would fail with the
		// base64 message instead of the size message.
		String payload = "!".repeat(4 * ((MediaContentMapper.MAX_INLINE_BYTES + 2) / 3) + 4);
		assertRejected(media("application/pdf", "data:application/pdf;base64," + payload), OVERSIZED);
	}

	@Test
	void rejectsOversizedDecodedBase64AfterTheLengthCheck() {
		String payload = "A".repeat(4 * ((MediaContentMapper.MAX_INLINE_BYTES + 2) / 3));
		assertRejected(media("application/pdf", "data:application/pdf;base64," + payload), SIZE_RANGE);
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
	void requestMappersRejectUnsupportedMediaInsteadOfDroppingIt() {
		ObjectMapper json = new ObjectMapper();
		List<Message> messages = List.of(UserMessage.builder()
			.text("watch this")
			.media(media("application/zip", "https://example.test/clip.mp4"))
			.build());
		var options = OpenRouterChatOptions.builder().model("synthetic/model").build();
		var chat = new OpenRouterChatRequestMapper(json);
		var responses = new OpenRouterResponsesRequestMapper(json);
		assertThatThrownBy(() -> chat.map(messages, options, false, List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Unsupported input media MIME type: application/zip");
		assertThatThrownBy(() -> responses.map(messages, options, false, List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Unsupported input media MIME type: application/zip");
	}

	private static Media media(String mime, String source) {
		return Media.builder()
			.mimeType(MimeTypeUtils.parseMimeType(mime))
			.name("fixture.pdf")
			.data(URI.create(source))
			.build();
	}

	private static void assertRejected(Media media, String message) {
		assertThatThrownBy(() -> MediaContentMapper.chat(media)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage(message);
		assertThatThrownBy(() -> MediaContentMapper.responses(media)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage(message);
	}

}
