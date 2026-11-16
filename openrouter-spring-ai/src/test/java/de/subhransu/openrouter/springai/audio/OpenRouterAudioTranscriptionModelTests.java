package de.subhransu.openrouter.springai.audio;

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import de.subhransu.openrouter.springai.chat.OpenRouterTrace;
import de.subhransu.openrouter.springai.errors.OpenRouterNonTransientApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import io.micrometer.observation.tck.TestObservationRegistry;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.AudioTranscriptionResponse;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("multimodal")
@Tag("multimodal.transcription-model")
class OpenRouterAudioTranscriptionModelTests {

	private static final String URL = "https://openrouter.test/api/v1/audio/transcriptions";

	private static final String MODEL = "synthetic/transcription";

	private static final byte[] WAV = { 'R', 'I', 'F', 'F', 0x24, 0x00, 0x00, 0x00, 'W', 'A', 'V', 'E' };

	private static final String JSON_RESPONSE = """
			{"text":"The brake pads are worn.","usage":{"seconds":2.5,"cost":0.0004,"input_tokens":12,"output_tokens":6,"total_tokens":18},"provider_trace":"ignored"}
			""";

	private static final String VERBOSE_RESPONSE = """
			{"text":"Pads worn. Rotor fine.","task":"transcribe","language":"english","duration":3.1,
			 "confidence":0.94,"language_confidence":0.98,
			 "segments":[{"id":0,"start":0.0,"end":1.4,"text":"Pads worn.","speaker":0},null,
			             {"id":1,"start":1.4,"end":3.1,"text":"Rotor fine.","speaker_label":"B","channel":1}],
			 "words":[{"word":"Pads","start":0.0,"end":0.5,"confidence":0.9,"speaker":0},
			          {"word":"(laughter)","start":0.5,"end":0.7,"type":"audio_event"}],
			 "entities":[{"text":"Rotor","type":"part","start_char":11,"end_char":16}],
			 "usage":{"seconds":3.1}}
			""";

	private static final ObjectMapper JSON = new ObjectMapper();

	private final List<JsonNode> requests = new ArrayList<>();

	@Test
	void inlineAudioIsSentAsBase64WithTheFormatOfItsFilename() {
		Fixture fixture = fixture(OpenRouterAudioTranscriptionOptions.builder().model(MODEL).build());
		fixture.expectSuccess(JSON_RESPONSE);

		AudioTranscriptionResponse response = fixture.model().call(new AudioTranscriptionPrompt(wav("Bay-3.WAV")));

		assertThat(response.getResult().getOutput()).isEqualTo("The brake pads are worn.");
		OpenRouterTranscriptionMetadata metadata = OpenRouterTranscriptionMetadata.from(response.getResult());
		assertThat(metadata.usage()).isEqualTo(new OpenRouterTranscriptionMetadata.Usage(2.5, 0.0004, 12, 6, 18));
		assertThat(metadata.segments()).isEmpty();
		assertThat(metadata.language()).isNull();
		JsonNode request = this.requests.get(0);
		assertThat(request.path("input_audio")).isEqualTo(
				JSON.readTree("{\"data\":\"" + Base64.getEncoder().encodeToString(WAV) + "\",\"format\":\"wav\"}"));
		assertThat(request.propertyNames()).containsExactlyInAnyOrder("model", "input_audio");
		fixture.server().verify();
	}

	@Test
	void verboseJsonDetailsBecomeTypedMetadata() {
		Fixture fixture = fixture(OpenRouterAudioTranscriptionOptions.builder()
			.model(MODEL)
			.responseFormat(OpenRouterTranscriptionFormat.VERBOSE_JSON)
			.build());
		fixture.expectSuccess(VERBOSE_RESPONSE);

		AudioTranscriptionResponse response = fixture.model()
			.call(new AudioTranscriptionPrompt(wav("bay.wav"),
					OpenRouterAudioTranscriptionOptions.builder()
						.timestampGranularities(
								List.of(OpenRouterTimestampGranularity.WORD, OpenRouterTimestampGranularity.SEGMENT))
						.diarize(true)
						.language("en")
						.temperature(0.0)
						.keyterms(List.of("rotor", "brake pad"))
						.build()));

		OpenRouterTranscriptionMetadata metadata = OpenRouterTranscriptionMetadata.from(response.getResult());
		assertThat(metadata.task()).isEqualTo("transcribe");
		assertThat(metadata.language()).isEqualTo("english");
		assertThat(metadata.duration()).isEqualTo(3.1);
		assertThat(metadata.confidence()).isEqualTo(0.94);
		assertThat(metadata.languageConfidence()).isEqualTo(0.98);
		// The null segment from the provider is dropped, not fatal.
		assertThat(metadata.segments()).containsExactly(
				new OpenRouterTranscriptionMetadata.Segment(0, 0.0, 1.4, "Pads worn.", 0, null, null),
				new OpenRouterTranscriptionMetadata.Segment(1, 1.4, 3.1, "Rotor fine.", null, "B", 1));
		assertThat(metadata.words()).containsExactly(
				new OpenRouterTranscriptionMetadata.Word("Pads", 0.0, 0.5, null, 0.9, 0, null, null),
				new OpenRouterTranscriptionMetadata.Word("(laughter)", 0.5, 0.7, "audio_event", null, null, null,
						null));
		assertThat(metadata.entities())
			.containsExactly(new OpenRouterTranscriptionMetadata.Entity("Rotor", "part", 11, 16));
		assertThat(metadata.usage()).isEqualTo(new OpenRouterTranscriptionMetadata.Usage(3.1, null, null, null, null));
		JsonNode request = this.requests.get(0);
		assertThat(request.path("response_format").asString()).isEqualTo("verbose_json");
		assertThat(request.path("timestamp_granularities").toString()).isEqualTo("[\"word\",\"segment\"]");
		assertThat(request.path("diarize").asBoolean()).isTrue();
		assertThat(request.path("language").asString()).isEqualTo("en");
		assertThat(request.path("temperature").asDouble()).isZero();
		assertThat(request.path("keyterms").toString()).isEqualTo("[\"rotor\",\"brake pad\"]");
	}

	@Test
	void defaultsAndRequestOptionsReachTheWire() {
		Fixture fixture = fixture(OpenRouterAudioTranscriptionOptions.builder()
			.model(MODEL)
			.audioFormat("mp3")
			.user("synthetic-user")
			.sessionId("default-session")
			.trace(OpenRouterTrace.builder().traceName("garage").build())
			.provider(OpenRouterProviderPreferences.builder().dataCollection("deny").build())
			.build());
		fixture.expectSuccess(JSON_RESPONSE);

		fixture.model()
			.call(new AudioTranscriptionPrompt(wav("note.bin"),
					OpenRouterAudioTranscriptionOptions.builder()
						.sessionId("request-session")
						.trace(OpenRouterTrace.builder().traceId("run-7").metadata(Map.of("bay", "3")).build())
						.build()));

		JsonNode request = this.requests.get(0);
		// An explicit audioFormat wins over the filename extension.
		assertThat(request.path("input_audio").path("format").asString()).isEqualTo("mp3");
		assertThat(request.path("user").asString()).isEqualTo("synthetic-user");
		assertThat(request.path("session_id").asString()).isEqualTo("request-session");
		assertThat(request.path("trace")).isEqualTo(JSON.readTree("{\"trace_id\":\"run-7\",\"bay\":\"3\"}"));
		assertThat(request.path("provider")).isEqualTo(JSON.readTree("{\"data_collection\":\"deny\"}"));
	}

	@Test
	void anHttpResourceIsSentAsAUrlOnlyWhenAskedTo() throws MalformedURLException {
		Fixture fixture = fixture(
				OpenRouterAudioTranscriptionOptions.builder().model(MODEL).sendAudioUrl(true).build());
		fixture.expectSuccess(JSON_RESPONSE);

		fixture.model().call(new AudioTranscriptionPrompt(new UrlResource("https://garage.example/notes/bay-3.mp3")));

		assertThat(this.requests.get(0).path("input_audio"))
			.isEqualTo(JSON.readTree("{\"url\":\"https://garage.example/notes/bay-3.mp3\",\"format\":\"mp3\"}"));
		assertThatThrownBy(() -> fixture.model().call(new AudioTranscriptionPrompt(wav("bay.wav"))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("sendAudioUrl requires an HTTP(S) resource");
		assertThatThrownBy(() -> fixture.model()
			.call(new AudioTranscriptionPrompt(new UrlResource("https://garage.example/stream"))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Set audioFormat");
		fixture.server().verify();
	}

	@Test
	void invalidInputFailsBeforeAnyRequestIsSent() {
		Fixture fixture = fixture(OpenRouterAudioTranscriptionOptions.builder().model(MODEL).build(), 4);
		fixture.server().expect(never(), requestTo(URL));

		assertThatThrownBy(() -> fixture.model().call(new AudioTranscriptionPrompt(wav("bay.wav"))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("larger than the inline audio limit of 4 bytes");
		assertThatThrownBy(() -> fixture.model().call(new AudioTranscriptionPrompt(resource(new byte[] { 1 }, null))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Set audioFormat");
		assertThatThrownBy(() -> fixture.model().call(new AudioTranscriptionPrompt(resource(new byte[0], "empty.wav"))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("is empty");
		assertThatThrownBy(() -> fixture.model()
			.call(new AudioTranscriptionPrompt(resource(new byte[] { 1 }, "bay.wav"),
					OpenRouterAudioTranscriptionOptions.builder().diarize(true).build())))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("diarize requires responseFormat VERBOSE_JSON");
		assertThatThrownBy(() -> fixture.model()
			.call(new AudioTranscriptionPrompt(resource(new byte[] { 1 }, "bay.wav"),
					OpenRouterAudioTranscriptionOptions.builder()
						.responseFormat(OpenRouterTranscriptionFormat.JSON)
						.timestampGranularities(List.of(OpenRouterTimestampGranularity.WORD))
						.build())))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("timestampGranularities requires responseFormat VERBOSE_JSON");
		assertThatThrownBy(() -> fixture.model()
			.call(new AudioTranscriptionPrompt(resource(new byte[] { 1 }, "bay.wav"),
					OpenRouterAudioTranscriptionOptions.builder()
						.provider(OpenRouterProviderPreferences.builder().order(List.of("provider-a")).build())
						.build())))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("does not apply provider order; it accepts only dataCollection");
		assertThatThrownBy(() -> fixture(OpenRouterAudioTranscriptionOptions.builder().build()).model()
			.call(new AudioTranscriptionPrompt(wav("bay.wav")))).hasMessageContaining("Transcription model");
		fixture.server().verify();
	}

	@Test
	void optionValuesAreCheckedWhenBuilt() {
		assertThatThrownBy(() -> OpenRouterAudioTranscriptionOptions.builder().audioFormat("wav;base64"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("audioFormat \"wav;base64\"");
		assertThatThrownBy(() -> OpenRouterAudioTranscriptionOptions.builder().temperature(1.5))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> OpenRouterAudioTranscriptionOptions.builder().keyterms(List.of("")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("1 to 100 characters");
		assertThatThrownBy(
				() -> OpenRouterAudioTranscriptionOptions.builder().keyterms(java.util.Collections.nCopies(1001, "x")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("at most 1000 terms");
		assertThatThrownBy(() -> OpenRouterAudioTranscriptionOptions.builder().sessionId("x".repeat(257)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("sessionId");
		assertThat(OpenRouterAudioTranscriptionOptions.fromOptions(() -> "portable/model").getModel())
			.isEqualTo("portable/model");
	}

	@Test
	void providerErrorsAndMissingTextAreClassified() {
		Fixture fixture = fixture(OpenRouterAudioTranscriptionOptions.builder().model(MODEL).build());
		fixture.server()
			.expect(once(), requestTo(URL))
			.andRespond(withStatus(HttpStatus.PAYLOAD_TOO_LARGE).contentType(MediaType.APPLICATION_JSON)
				.body("{\"error\":{\"code\":413,\"message\":\"Request payload too large\"}}"));
		fixture.expectSuccess("{\"usage\":{\"seconds\":1.0}}");

		assertThatThrownBy(() -> fixture.model().call(new AudioTranscriptionPrompt(wav("bay.wav"))))
			.isInstanceOfSatisfying(OpenRouterNonTransientApiException.class, failure -> {
				assertThat(failure.getStatusCode().value()).isEqualTo(413);
				assertThat(failure.getErrorDetails().message()).isEqualTo("Request payload too large");
			});
		assertThatThrownBy(() -> fixture.model().call(new AudioTranscriptionPrompt(wav("bay.wav"))))
			.isInstanceOfSatisfying(OpenRouterProtocolException.class,
					failure -> assertThat(failure.getKind()).isEqualTo(OpenRouterProtocolException.Kind.MISSING_FIELD));
		fixture.server().verify();
	}

	@Test
	void streamingIsNotOffered() {
		Fixture fixture = fixture(OpenRouterAudioTranscriptionOptions.builder().model(MODEL).build());

		assertThatThrownBy(() -> fixture.model().stream(new AudioTranscriptionPrompt(wav("bay.wav"))).blockLast())
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Tag("observability")
	@Tag("observability.observations")
	@Test
	void callsAreObservedAsTranscriptionOperations() {
		TestObservationRegistry registry = TestObservationRegistry.create();
		RestClient.Builder restClient = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(restClient).build();
		server.expect(once(), requestTo(URL)).andRespond(withSuccess(JSON_RESPONSE, MediaType.APPLICATION_JSON));
		OpenRouterAudioTranscriptionModel model = OpenRouterAudioTranscriptionModel.builder()
			.openRouterApi(api(restClient))
			.options(OpenRouterAudioTranscriptionOptions.builder().model(MODEL).build())
			.observationRegistry(registry)
			.build();

		model.call(new AudioTranscriptionPrompt(wav("bay.wav")));

		assertThat(registry).hasObservationWithNameEqualTo("gen_ai.client.operation")
			.that()
			.hasLowCardinalityKeyValue("gen_ai.operation.name", "transcription")
			.hasLowCardinalityKeyValue("gen_ai.system", "openrouter")
			.hasLowCardinalityKeyValue("gen_ai.request.model", MODEL)
			.hasBeenStopped();
	}

	private Fixture fixture(OpenRouterAudioTranscriptionOptions defaults) {
		return fixture(defaults, OpenRouterAudioTranscriptionModel.DEFAULT_MAX_INLINE_AUDIO_BYTES);
	}

	private Fixture fixture(OpenRouterAudioTranscriptionOptions defaults, int maxInlineAudioBytes) {
		RestClient.Builder restClient = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(restClient).build();
		OpenRouterAudioTranscriptionModel model = OpenRouterAudioTranscriptionModel.builder()
			.openRouterApi(api(restClient))
			.options(defaults)
			.maxInlineAudioBytes(maxInlineAudioBytes)
			.build();
		return new Fixture(model, server, this.requests);
	}

	private static OpenRouterApi api(RestClient.Builder restClient) {
		return OpenRouterApi.builder()
			.apiKey("test-key")
			.baseUrl("https://openrouter.test/api/v1")
			.restClientBuilder(restClient)
			.build();
	}

	private static Resource wav(String filename) {
		return resource(WAV, filename);
	}

	private static Resource resource(byte[] bytes, String filename) {
		return new ByteArrayResource(bytes) {

			@Override
			public String getFilename() {
				return filename;
			}

		};
	}

	private record Fixture(OpenRouterAudioTranscriptionModel model, MockRestServiceServer server,
			List<JsonNode> requests) {

		void expectSuccess(String body) {
			this.server.expect(once(), requestTo(URL))
				.andExpect(method(HttpMethod.POST))
				.andExpect(request -> this.requests
					.add(JSON.readTree(((MockClientHttpRequest) request).getBodyAsString())))
				.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
		}

	}

}
