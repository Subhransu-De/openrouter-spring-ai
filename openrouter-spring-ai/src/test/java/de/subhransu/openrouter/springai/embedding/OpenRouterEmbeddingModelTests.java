package de.subhransu.openrouter.springai.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import de.subhransu.openrouter.springai.chat.OpenRouterUsage;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import de.subhransu.openrouter.springai.api.errors.OpenRouterApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorDetails;
import de.subhransu.openrouter.springai.errors.OpenRouterHttpException;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorCategory;
import de.subhransu.openrouter.springai.errors.OpenRouterNonTransientApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterTransientApiException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.document.DefaultContentFormatter;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

class OpenRouterEmbeddingModelTests {

	private static final String BASE_URL = "https://openrouter.test/api/v1";

	private static final String DOCUMENT_TEXT = "synthetic passage";

	private static final String SINGLE_EMBEDDING_BODY = "{\"data\":[{\"index\":0,\"embedding\":[1,2]}]}";

	private static final String MODEL = "openai/text-embedding-3-small";

	private static final Duration CONTENDER_FAIL_SAFE = Duration.ofSeconds(10);

	private static final String OTHER_MODEL = "qwen/qwen3-embedding-8b";

	private static final OpenRouterProviderPreferences DEFAULT_PROVIDER = new OpenRouterProviderPreferences(false, true,
			"deny", List.of("openai", "azure"), null, null, null);

	private static final String SUCCESS_BODY = """
			{
			  "object": "list",
			  "data": [
			    {"object": "embedding", "index": 0, "embedding": [0.25, -0.5]},
			    {"object": "embedding", "index": 1, "embedding": [0.75, 1.0]}
			  ],
			  "model": "openai/text-embedding-3-small",
			  "usage": {"prompt_tokens": 4, "total_tokens": 4}
			}
			""";

	private Fixture fixture(OpenRouterEmbeddingOptions defaultOptions) {
		return fixture(defaultOptions, MetadataMode.NONE);
	}

	private Fixture fixture(OpenRouterEmbeddingOptions defaultOptions, MetadataMode metadataMode) {
		RestClient.Builder restClientBuilder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey("test-key")
			.baseUrl(BASE_URL)
			.restClientBuilder(restClientBuilder)
			.build();
		OpenRouterEmbeddingModel model = OpenRouterEmbeddingModel.builder()
			.openRouterApi(api)
			.defaultOptions(defaultOptions)
			.metadataMode(metadataMode)
			.build();
		return new Fixture(model, server);
	}

	@Test
	void mapsEmbeddingsResponseIntoSpringAiTypes() {
		Fixture fixture = fixture(OpenRouterEmbeddingOptions.builder().model(MODEL).build());
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andExpect(jsonPath("$.model").value(MODEL))
			.andExpect(jsonPath("$.input[0]").value("hello"))
			.andExpect(jsonPath("$.input[1]").value("world"))
			.andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

		EmbeddingResponse response = fixture.model()
			.call(new EmbeddingRequest(List.of("hello", "world"), OpenRouterEmbeddingOptions.builder().build()));

		assertThat(response.getResults()).hasSize(2);
		assertThat(response.getResults().get(0).getOutput()).containsExactly(0.25f, -0.5f);
		assertThat(response.getResults().get(1).getIndex()).isEqualTo(1);
		assertThat(response.getMetadata().getModel()).isEqualTo(MODEL);
		assertThat(response.getMetadata().getUsage()).isInstanceOf(OpenRouterUsage.class);
		assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(4);
		fixture.server().verify();
	}

	@ParameterizedTest(name = "code {0}, data present: {1}")
	@CsvSource({ "401,false,AUTHENTICATION", "401,true,AUTHENTICATION" })
	void permanentInBandErrorsPreserveDiagnosticsAndTakePrecedenceOverData(int code, boolean withData,
			OpenRouterErrorCategory category) {
		Fixture fixture = inBandFixture(code, withData);

		assertThatThrownBy(() -> fixture.model().embed("hello")).isInstanceOf(OpenRouterApiException.class)
			.satisfies(failure -> assertInBandDetails(((OpenRouterApiException) failure).getErrorDetails(), code,
					category));
		fixture.server().verify();
	}

	@ParameterizedTest(name = "code {0}, data present: {1}")
	@CsvSource({ "429,false,RATE_LIMIT", "429,true,RATE_LIMIT", "503,false,PROVIDER_UNAVAILABLE",
			"503,true,PROVIDER_UNAVAILABLE" })
	void transientInBandErrorsPreserveDiagnosticsAndTakePrecedenceOverData(int code, boolean withData,
			OpenRouterErrorCategory category) {
		Fixture fixture = inBandFixture(code, withData);

		assertThatThrownBy(() -> fixture.model().embed("hello")).isInstanceOf(OpenRouterTransientApiException.class)
			.satisfies(failure -> assertInBandDetails(((OpenRouterHttpException) failure).getErrorDetails(), code,
					category));
		fixture.server().verify();
	}

	@ParameterizedTest(name = "code {0}")
	@ValueSource(ints = { 400, 401, 402, 403, 404 })
	void permanentInBandErrorIsNotRetriedByTheDefaultRetryTemplate(int code) {
		// The default Spring AI retry template retries with real sleeps, so a regression
		// that retries would also fail the one-request expectation below.
		Fixture fixture = fixture(OpenRouterEmbeddingOptions.builder().model(MODEL).build());
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andRespond(
					withSuccess("{\"error\":{\"code\":%d,\"message\":\"synthetic permanent failure\"}}".formatted(code),
							MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> fixture.model().embed("hello")).isInstanceOf(OpenRouterApiException.class)
			.satisfies(failure -> {
				var details = ((OpenRouterApiException) failure).getErrorDetails();
				assertThat(details.code()).isEqualTo(Integer.toString(code));
				assertThat(details.message()).isEqualTo("synthetic permanent failure");
			});
		fixture.server().verify();
	}

	@Test
	void runtimeOptionsOverrideDefaults() {
		Fixture fixture = fixture(OpenRouterEmbeddingOptions.builder().model(MODEL).dimensions(2).build());
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andExpect(jsonPath("$.model").value(OTHER_MODEL))
			.andExpect(jsonPath("$.dimensions").value(2))
			.andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

		fixture.model()
			.call(new EmbeddingRequest(List.of("hello", "world"),
					OpenRouterEmbeddingOptions.builder().model(OTHER_MODEL).build()));

		fixture.server().verify();
	}

	@Test
	void rejectsAMissingModelBeforeCallingTheApi() {
		Fixture fixture = fixture(OpenRouterEmbeddingOptions.builder().build());
		OpenRouterEmbeddingModel model = fixture.model();

		assertThatThrownBy(() -> model.embed("hello")).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Embedding model must not be empty");
		assertThatThrownBy(model::dimensions).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Embedding model must not be empty");
		fixture.server().verify();
	}

	@ParameterizedTest(name = "encoding_format {0}")
	@ValueSource(strings = { "base64", "Float" })
	void rejectsNonFloatEncodingFormatBeforeCallingTheApi(String format) {
		Fixture fixture = fixture(OpenRouterEmbeddingOptions.builder().model(MODEL).encodingFormat(format).build());
		OpenRouterEmbeddingModel model = fixture.model();

		assertThatThrownBy(() -> model.embed("hello")).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Unsupported encoding_format '" + format + "': only 'float' embeddings can be decoded");
		fixture.server().verify();
	}

	@ParameterizedTest
	@ValueSource(strings = { "[{\"index\":0,\"embedding\":[1,2]},{\"index\":1,\"embedding\":[3,4]}]",
			"[{\"index\":1,\"embedding\":[3,4]},{\"index\":0,\"embedding\":[1,2]}]" })
	void convenienceMethodReturnsVectorsInInputOrder(String data) {
		Fixture fixture = fixture(OpenRouterEmbeddingOptions.builder().model(MODEL).build());
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andRespond(withSuccess("{\"data\":" + data + "}", MediaType.APPLICATION_JSON));

		List<float[]> vectors = fixture.model().embed(List.of("first", "second"));

		assertThat(vectors).hasSize(2);
		assertThat(vectors.get(0)).containsExactly(1, 2);
		assertThat(vectors.get(1)).containsExactly(3, 4);
		fixture.server().verify();
	}

	static Stream<Arguments> malformedResults() {
		String count = "Embedding response count must match input count";
		String range = "Embedding response index must be within input range";
		String empty = "Embedding response vector must not be empty";
		String integer = "Embedding response index must be an integer";
		String two = ",{\"index\":1,\"embedding\":[2]}]";
		return Stream.of(argumentSet("data null", "null", count), argumentSet("no results", "[]", count),
				argumentSet("too few results", "[{\"index\":0,\"embedding\":[1]}]", count),
				argumentSet("too many results",
						"[{\"index\":0,\"embedding\":[1]},{\"index\":1,\"embedding\":[2]},{\"index\":2,\"embedding\":[3]}]",
						count),
				argumentSet("null item", "[null" + two, range),
				argumentSet("null index", "[{\"index\":null,\"embedding\":[1]}" + two, range),
				argumentSet("missing index", "[{\"embedding\":[1]}" + two, range),
				argumentSet("negative index", "[{\"index\":-1,\"embedding\":[1]}" + two, range),
				argumentSet("index beyond input", "[{\"index\":2,\"embedding\":[1]}" + two, range),
				argumentSet("Embedding response contains duplicate index",
						"[{\"index\":0,\"embedding\":[1]},{\"index\":0,\"embedding\":[2]}]",
						"Embedding response contains duplicate index"),
				argumentSet("null vector", "[{\"index\":0,\"embedding\":null}" + two, empty),
				argumentSet("empty vector", "[{\"index\":0,\"embedding\":[]}" + two, empty),
				argumentSet("ragged vectors", "[{\"index\":0,\"embedding\":[1,2]}" + two,
						"Embedding response vector dimensions must match"),
				argumentSet("float overflow", "[{\"index\":0,\"embedding\":[1e100]}" + two,
						"Embedding response vector values must be finite"),
				argumentSet("negative fractional index", "[{\"index\":-0.5,\"embedding\":[1]}" + two, integer),
				argumentSet("fractional index", "[{\"index\":0.5,\"embedding\":[1]}" + two, integer),
				argumentSet("index just below one", "[{\"index\":0.9999999999999999999999,\"embedding\":[1]}" + two,
						integer));
	}

	@ParameterizedTest
	@MethodSource("malformedResults")
	void rejectsMalformedResults(String data, String messageFragment) {
		Fixture fixture = fixture(OpenRouterEmbeddingOptions.builder().model(MODEL).build());
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andRespond(withSuccess("{\"data\":" + data + "}", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> fixture.model().embed(List.of("first", "second")))
			.isInstanceOf(OpenRouterProtocolException.class)
			.hasStackTraceContaining(messageFragment);
		fixture.server().verify();
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "0.0", "0e0", "\"0\"" })
	void preservesIntegralEmbeddingIndexes(String index) {
		Fixture fixture = fixture(OpenRouterEmbeddingOptions.builder().model(MODEL).build());
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andRespond(withSuccess("{\"data\":[{\"index\":" + index + ",\"embedding\":[1,2]}]}",
					MediaType.APPLICATION_JSON));

		assertThat(fixture.model().embed("hello")).containsExactly(1, 2);
		fixture.server().verify();
	}

	@ParameterizedTest(name = "default {0}, request {1} -> wire {2}")
	@CsvSource({ "2, , 2", "3, 2, 2" })
	void acceptsMergedDimensionsThatMatchTheResponse(int defaultDimensions, Integer runtimeDimensions,
			int wireDimensions) {
		Fixture fixture = dimensionsFixture(defaultDimensions, wireDimensions);

		EmbeddingResponse response = fixture.model().call(dimensionsRequest(runtimeDimensions));

		assertThat(response.getResults()).hasSize(2);
		fixture.server().verify();
	}

	@ParameterizedTest(name = "default {0}, request {1} -> wire {2}")
	@CsvSource({ "3, , 3", "2, 3, 3" })
	void rejectsMergedDimensionsThatDifferFromTheResponse(int defaultDimensions, Integer runtimeDimensions,
			int wireDimensions) {
		Fixture fixture = dimensionsFixture(defaultDimensions, wireDimensions);

		assertThatThrownBy(() -> fixture.model().call(dimensionsRequest(runtimeDimensions)))
			.isInstanceOf(OpenRouterProtocolException.class)
			.hasMessage("Embedding response vector dimensions must match");
		fixture.server().verify();
	}

	private Fixture dimensionsFixture(int defaultDimensions, int expectedWireDimensions) {
		Fixture fixture = fixture(
				OpenRouterEmbeddingOptions.builder().model(MODEL).dimensions(defaultDimensions).build());
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andExpect(jsonPath("$.dimensions").value(expectedWireDimensions))
			.andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));
		return fixture;
	}

	private static EmbeddingRequest dimensionsRequest(Integer runtimeDimensions) {
		return new EmbeddingRequest(List.of("first", "second"),
				OpenRouterEmbeddingOptions.builder().dimensions(runtimeDimensions).build());
	}

	@ParameterizedTest
	@EnumSource(MetadataMode.class)
	void formatsSingleAndBatchedDocumentsWithSelectedMetadataPolicy(MetadataMode mode) {
		Fixture fixture = fixture(OpenRouterEmbeddingOptions.builder().model(MODEL).build(), mode);
		Document document = new Document(DOCUMENT_TEXT,
				Map.of("topic", "synthetic-topic", "excluded", "synthetic-excluded"));
		document.setContentFormatter(DefaultContentFormatter.builder()
			.withExcludedEmbedMetadataKeys("excluded")
			.withExcludedInferenceMetadataKeys("topic")
			.build());
		String expected = switch (mode) {
			case NONE -> DOCUMENT_TEXT;
			case EMBED -> "topic: synthetic-topic\n\nsynthetic passage";
			case INFERENCE -> "excluded: synthetic-excluded\n\nsynthetic passage";
			// Spring AI joins metadata entries with the platform line separator.
			case ALL ->
				"excluded: synthetic-excluded" + System.lineSeparator() + "topic: synthetic-topic\n\nsynthetic passage";
		};
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andExpect(jsonPath("$.input[0]").value(expected))
			.andRespond(withSuccess(SINGLE_EMBEDDING_BODY, MediaType.APPLICATION_JSON));
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andExpect(jsonPath("$.input[0]").value(expected))
			.andExpect(jsonPath("$.input[1]").value(expected))
			.andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

		assertThat(fixture.model().embed(document)).containsExactly(1, 2);
		assertThat(fixture.model().embed(List.of(document, document), null, List::of)).hasSize(2);
		fixture.server().verify();
	}

	@Test
	void severalBatchesSendOneRequestEachAndKeepVectorsInInputOrder() {
		Fixture fixture = fixture(OpenRouterEmbeddingOptions.builder().model(MODEL).build());
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andExpect(jsonPath("$.input.length()").value(2))
			.andExpect(jsonPath("$.input[0]").value("first"))
			.andExpect(jsonPath("$.input[1]").value("second"))
			.andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andExpect(jsonPath("$.input.length()").value(1))
			.andExpect(jsonPath("$.input[0]").value("third"))
			.andRespond(withSuccess("{\"data\":[{\"index\":0,\"embedding\":[9,8]}]}", MediaType.APPLICATION_JSON));
		List<Document> documents = List.of(new Document("first"), new Document("second"), new Document("third"));

		List<float[]> vectors = fixture.model()
			.embed(documents, null, batchable -> List.of(batchable.subList(0, 2), batchable.subList(2, 3)));

		assertThat(vectors).containsExactly(new float[] { 0.25f, -0.5f }, new float[] { 0.75f, 1.0f },
				new float[] { 9, 8 });
		fixture.server().verify();
	}

	@Test
	void defaultMetadataPolicyPreservesRawTextEvenWithCustomFormatter() {
		OpenRouterEmbeddingModel model = OpenRouterEmbeddingModel.builder()
			.openRouterApi(OpenRouterApi.builder().apiKey("test-key").build())
			.build();
		Document document = new Document(DOCUMENT_TEXT, Map.of("topic", "synthetic-topic"));
		document.setContentFormatter((value, mode) -> "custom formatted content");

		assertThat(model.getEmbeddingContent(document)).isEqualTo(DOCUMENT_TEXT);
	}

	@Test
	void builderRejectsANullMetadataMode() {
		assertThatThrownBy(() -> OpenRouterEmbeddingModel.builder().metadataMode(null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("MetadataMode must not be null");
	}

	@Test
	void configuredDimensionsUseSnapshotWithoutProbing() {
		OpenRouterEmbeddingOptions options = OpenRouterEmbeddingOptions.builder().model(MODEL).dimensions(2).build();
		Fixture first = fixture(options);
		Fixture second = fixture(options.mutate().dimensions(3).build());
		assertThat(first.model().dimensions()).isEqualTo(2);
		assertThat(second.model().dimensions()).isEqualTo(3);
		first.server().verify();
		second.server().verify();
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, -1 })
	void rejectsInvalidConfiguredDimensionsWithoutProbing(int dimensions) {
		Fixture fixture = fixture(OpenRouterEmbeddingOptions.builder().model(MODEL).dimensions(dimensions).build());
		assertThatThrownBy(() -> fixture.model().dimensions()).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Dimensions must be positive");
		fixture.server().verify();
	}

	@Test
	void dimensionDiscoveryIsIsolatedFromOtherModelsAndRequestOverrides() {
		OpenRouterEmbeddingOptions defaults = OpenRouterEmbeddingOptions.builder().model("synthetic/model-a").build();
		Fixture first = fixture(defaults);
		OpenRouterEmbeddingOptions options = defaults.mutate().model("synthetic/model-b").build();
		Fixture second = fixture(options);
		first.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andExpect(jsonPath("$.model").value("synthetic/model-b"))
			.andRespond(withSuccess("{\"data\":[{\"index\":0,\"embedding\":[1,2,3]}]}", MediaType.APPLICATION_JSON));
		first.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andExpect(jsonPath("$.model").value("synthetic/model-a"))
			.andRespond(withSuccess(SINGLE_EMBEDDING_BODY, MediaType.APPLICATION_JSON));
		second.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andExpect(jsonPath("$.model").value("synthetic/model-b"))
			.andRespond(withSuccess("{\"data\":[{\"index\":0,\"embedding\":[1,2,3]}]}", MediaType.APPLICATION_JSON));
		first.model().call(new EmbeddingRequest(List.of(DOCUMENT_TEXT), options));
		assertThat(first.model().dimensions()).isEqualTo(2);
		assertThat(second.model().dimensions()).isEqualTo(3);
		assertThat(first.model().dimensions()).isEqualTo(2);
		assertThat(second.model().dimensions()).isEqualTo(3);
		first.server().verify();
		second.server().verify();
	}

	static Stream<Arguments> failedProbes() {
		return Stream.of(
				argumentSet("empty vector",
						withSuccess("{\"data\":[{\"index\":0,\"embedding\":[]}]}", MediaType.APPLICATION_JSON),
						"Embedding response vector must not be empty"),
				argumentSet("null vector",
						withSuccess("{\"data\":[{\"index\":0,\"embedding\":null}]}", MediaType.APPLICATION_JSON),
						"Embedding response vector must not be empty"));
	}

	@ParameterizedTest
	@MethodSource("failedProbes")
	void failedDimensionDiscoveryCanRecover(ResponseCreator failure, String message) {
		Fixture fixture = fixture(OpenRouterEmbeddingOptions.builder().model(MODEL).build());
		fixture.server().expect(once(), requestTo(BASE_URL + "/embeddings")).andRespond(failure);
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andRespond(withSuccess(SINGLE_EMBEDDING_BODY, MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> fixture.model().dimensions()).isInstanceOf(OpenRouterProtocolException.class)
			.hasMessage(message);
		assertThat(fixture.model().dimensions()).isEqualTo(2);
		assertThat(fixture.model().dimensions()).isEqualTo(2);
		fixture.server().verify();
	}

	@Test
	void dimensionDiscoveryAfterAClientErrorCanRecover() {
		Fixture fixture = fixture(OpenRouterEmbeddingOptions.builder().model(MODEL).build());
		fixture.server().expect(once(), requestTo(BASE_URL + "/embeddings")).andRespond(withBadRequest());
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andRespond(withSuccess(SINGLE_EMBEDDING_BODY, MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> fixture.model().dimensions()).isInstanceOf(OpenRouterNonTransientApiException.class)
			.satisfies(
					failure -> assertThat(((OpenRouterHttpException) failure).getStatusCode().value()).isEqualTo(400));
		assertThat(fixture.model().dimensions()).isEqualTo(2);
		assertThat(fixture.model().dimensions()).isEqualTo(2);
		fixture.server().verify();
	}

	@Test
	void concurrentDimensionDiscoveryUsesOneProbe() throws InterruptedException {
		Fixture fixture = fixture(OpenRouterEmbeddingOptions.builder().model(MODEL).build());
		OpenRouterEmbeddingModel model = fixture.model();
		AtomicReference<Integer> contenderDimensions = new AtomicReference<>();
		AtomicReference<Throwable> contenderFailure = new AtomicReference<>();
		Thread contender = new Thread(() -> {
			try {
				contenderDimensions.set(model.dimensions());
			}
			catch (Throwable ex) { // NOPMD - handed to the test thread
				contenderFailure.set(ex);
			}
		});
		// The contender starts while the first caller's probe is in flight and the probe
		// response is held until the contender has parked behind that probe, or has
		// already failed by sending a second request. No clock is involved.
		fixture.server().expect(once(), requestTo(BASE_URL + "/embeddings")).andRespond(request -> {
			contender.start();
			// The deadline is a fail-safe only: a passing run leaves the loop as soon as
			// the contender parks, so no test outcome depends on the clock.
			long failSafe = System.nanoTime() + CONTENDER_FAIL_SAFE.toNanos();
			while (contender.getState() == Thread.State.NEW || contender.getState() == Thread.State.RUNNABLE) {
				if (System.nanoTime() > failSafe) {
					throw new AssertionError("contender thread never parked behind the first probe");
				}
				Thread.onSpinWait();
			}
			return withSuccess(SINGLE_EMBEDDING_BODY, MediaType.APPLICATION_JSON).createResponse(request);
		});

		int first = model.dimensions();
		contender.join(CONTENDER_FAIL_SAFE.toMillis());
		assertThat(contender.isAlive()).as("contender must finish once the first probe has completed").isFalse();

		assertThat(first).isEqualTo(2);
		assertThat(contenderFailure.get()).as("contender must wait for the first probe").isNull();
		assertThat(contenderDimensions.get()).isEqualTo(2);
		fixture.server().verify();
	}

	@Test
	void anEmptyInputListIsSentAsIsAndYieldsNoEmbeddings() {
		Fixture fixture = fixture(OpenRouterEmbeddingOptions.builder().model(MODEL).build());
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andExpect(jsonPath("$.input").isEmpty())
			.andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

		assertThat(fixture.model().embed(List.of())).isEmpty();
		fixture.server().verify();
	}

	@Test
	void aNullInputElementIsSentAsJsonNullForTheProviderToJudge() {
		Fixture fixture = fixture(OpenRouterEmbeddingOptions.builder().model(MODEL).build());
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andExpect(jsonPath("$.input[0]").value("first"))
			.andExpect(jsonPath("$.input[1]").value(nullValue()))
			.andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

		assertThat(fixture.model().embed(Arrays.asList("first", null))).hasSize(2);
		fixture.server().verify();
	}

	@Test
	void aRuntimeEncodingFormatOverridesTheDefaultBeforeValidation() {
		Fixture fixture = fixture(OpenRouterEmbeddingOptions.builder().model(MODEL).encodingFormat("base64").build());
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andExpect(jsonPath("$.encoding_format").value("float"))
			.andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

		EmbeddingResponse response = fixture.model()
			.call(new EmbeddingRequest(List.of("first", "second"),
					OpenRouterEmbeddingOptions.builder().encodingFormat("float").build()));

		assertThat(response.getResults()).hasSize(2);
		fixture.server().verify();
	}

	@Test
	void aRuntimeEncodingFormatThatCannotBeDecodedIsRejectedBeforeCallingTheApi() {
		Fixture fixture = fixture(OpenRouterEmbeddingOptions.builder().model(MODEL).encodingFormat("float").build());
		EmbeddingRequest request = new EmbeddingRequest(List.of("first"),
				OpenRouterEmbeddingOptions.builder().encodingFormat("base64").build());

		assertThatThrownBy(() -> fixture.model().call(request)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Unsupported encoding_format 'base64': only 'float' embeddings can be decoded");
		fixture.server().verify();
	}

	@Test
	void usageCachedTokensAndCostReachTheResponseMetadata() {
		Fixture fixture = fixture(OpenRouterEmbeddingOptions.builder().model(MODEL).build());
		fixture.server().expect(once(), requestTo(BASE_URL + "/embeddings")).andRespond(withSuccess("""
				{"data":[{"index":0,"embedding":[1,2]}],
				 "usage":{"prompt_tokens":4,"total_tokens":4,"cost":0.0004,
				 "prompt_tokens_details":{"cached_tokens":3}}}
				""", MediaType.APPLICATION_JSON));

		EmbeddingResponse response = fixture.model().call(new EmbeddingRequest(List.of("hello"), null));

		assertThat(response.getMetadata().getUsage()).isInstanceOfSatisfying(OpenRouterUsage.class, usage -> {
			assertThat(usage.getPromptTokens()).isEqualTo(4);
			assertThat(usage.getCachedTokens()).isEqualTo(3);
			assertThat(usage.getCost()).isEqualTo(0.0004);
		});
		fixture.server().verify();
	}

	@ParameterizedTest(name = "HTTP {0}")
	@ValueSource(ints = { 400, 401, 402, 403, 404 })
	void clientErrorsAreNotRetriedEvenWhenRetriesAreAllowed(int status) {
		Fixture fixture = retryingFixture(2);
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
				.body("{\"error\":{\"code\":%d,\"message\":\"synthetic client error\"}}".formatted(status)));

		assertThatThrownBy(() -> fixture.model().embed("hello")).isInstanceOf(OpenRouterNonTransientApiException.class)
			.satisfies(failure -> assertThat(((OpenRouterHttpException) failure).getStatusCode().value())
				.isEqualTo(status));
		fixture.server().verify();
	}

	@Test
	void exhaustedRetriesSurfaceTheFinalTransientFailure() {
		Fixture fixture = retryingFixture(2);
		fixture.server()
			.expect(times(3), requestTo(BASE_URL + "/embeddings"))
			.andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
				.body("{\"error\":{\"code\":503,\"message\":\"synthetic overload\"}}"));

		assertThatThrownBy(() -> fixture.model().embed("hello")).isInstanceOf(OpenRouterTransientApiException.class)
			.satisfies(
					failure -> assertThat(((OpenRouterHttpException) failure).getStatusCode().value()).isEqualTo(503));
		fixture.server().verify();
	}

	private Fixture retryingFixture(long maxRetries) {
		RestClient.Builder restClientBuilder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
		RetryPolicy policy = RetryPolicy.builder()
			.maxRetries(maxRetries)
			.includes(TransientAiException.class)
			.delay(Duration.ZERO)
			.build();
		OpenRouterEmbeddingModel model = OpenRouterEmbeddingModel.builder()
			.openRouterApi(OpenRouterApi.builder()
				.apiKey("test-key")
				.baseUrl(BASE_URL)
				.restClientBuilder(restClientBuilder)
				.build())
			.defaultOptions(OpenRouterEmbeddingOptions.builder().model(MODEL).build())
			.retryTemplate(new RetryTemplate(policy))
			.build();
		return new Fixture(model, server);
	}

	@Test
	void defaultUserAndProviderReachTheRequest() {
		Fixture fixture = fixture(OpenRouterEmbeddingOptions.builder()
			.model(MODEL)
			.user("default-user")
			.provider(DEFAULT_PROVIDER)
			.build());
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andExpect(jsonPath("$.user").value("default-user"))
			.andExpect(jsonPath("$.provider.allow_fallbacks").value(false))
			.andExpect(jsonPath("$.provider.require_parameters").value(true))
			.andExpect(jsonPath("$.provider.data_collection").value("deny"))
			.andExpect(jsonPath("$.provider.order[0]").value("openai"))
			.andExpect(jsonPath("$.provider.order[1]").value("azure"))
			.andRespond(withSuccess(SINGLE_EMBEDDING_BODY, MediaType.APPLICATION_JSON));

		assertThat(fixture.model().embed("hello")).containsExactly(1, 2);
		fixture.server().verify();
	}

	@Test
	void perRequestUserAndProviderReplaceTheDefaults() {
		Fixture fixture = fixture(OpenRouterEmbeddingOptions.builder()
			.model(MODEL)
			.user("default-user")
			.provider(DEFAULT_PROVIDER)
			.build());
		OpenRouterProviderPreferences override = new OpenRouterProviderPreferences(null, null, null,
				List.of("anthropic"), null, null, null);
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andExpect(jsonPath("$.user").value("request-user"))
			.andExpect(jsonPath("$.provider.order[0]").value("anthropic"))
			.andExpect(jsonPath("$.provider.order[1]").doesNotExist())
			.andExpect(jsonPath("$.provider.data_collection").doesNotExist())
			.andExpect(jsonPath("$.provider.allow_fallbacks").doesNotExist())
			.andRespond(withSuccess(SINGLE_EMBEDDING_BODY, MediaType.APPLICATION_JSON));

		fixture.model()
			.call(new EmbeddingRequest(List.of("hello"),
					OpenRouterEmbeddingOptions.builder().user("request-user").provider(override).build()));

		fixture.server().verify();
	}

	@Test
	void unsetRequestFieldsAndForeignPortableOptionsKeepTheDefaultUserAndProvider() {
		Fixture fixture = fixture(OpenRouterEmbeddingOptions.builder()
			.model(MODEL)
			.user("default-user")
			.provider(DEFAULT_PROVIDER)
			.build());
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andExpect(jsonPath("$.model").value(OTHER_MODEL))
			.andExpect(jsonPath("$.dimensions").value(2))
			.andExpect(jsonPath("$.user").value("default-user"))
			.andExpect(jsonPath("$.provider.data_collection").value("deny"))
			.andRespond(withSuccess(SINGLE_EMBEDDING_BODY, MediaType.APPLICATION_JSON));

		fixture.model()
			.call(new EmbeddingRequest(List.of("hello"),
					EmbeddingOptions.builder().model(OTHER_MODEL).dimensions(2).build()));

		fixture.server().verify();
	}

	@Test
	void userAndProviderAreOmittedWhenNeitherDefaultsNorRequestSetThem() {
		Fixture fixture = fixture(OpenRouterEmbeddingOptions.builder().model(MODEL).build());
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andExpect(jsonPath("$.user").doesNotExist())
			.andExpect(jsonPath("$.provider").doesNotExist())
			.andExpect(jsonPath("$.encoding_format").doesNotExist())
			.andRespond(withSuccess(SINGLE_EMBEDDING_BODY, MediaType.APPLICATION_JSON));

		fixture.model().embed("hello");

		fixture.server().verify();
	}

	@Test
	void getOptionsReturnsACopyOfTheDefaultsAndAnEmptySetWhenNoneWereConfigured() {
		OpenRouterEmbeddingOptions defaults = OpenRouterEmbeddingOptions.builder()
			.model(MODEL)
			.dimensions(2)
			.user("default-user")
			.provider(DEFAULT_PROVIDER)
			.build();

		OpenRouterEmbeddingOptions copy = fixture(defaults).model().getOptions();

		assertThat(copy).isNotSameAs(defaults).usingRecursiveComparison().isEqualTo(defaults);
		OpenRouterEmbeddingModel unconfigured = OpenRouterEmbeddingModel.builder()
			.openRouterApi(OpenRouterApi.builder().apiKey("test-key").build())
			.build();
		assertThat(unconfigured.getOptions()).usingRecursiveComparison()
			.isEqualTo(OpenRouterEmbeddingOptions.builder().build());
	}

	private Fixture inBandFixture(int code, boolean withData) {
		RestClient.Builder restClientBuilder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
		String body = """
				{"error":{"code":%d,"message":"synthetic embedding failure",
				 "metadata":{"provider_code":"synthetic_code"}},"future_field":true%s}
				""".formatted(code, withData ? ",\"data\":[{\"index\":0,\"embedding\":[1,2]}]" : "");
		server.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
		OpenRouterEmbeddingModel model = OpenRouterEmbeddingModel.builder()
			.openRouterApi(OpenRouterApi.builder()
				.apiKey("test-key")
				.baseUrl(BASE_URL)
				.restClientBuilder(restClientBuilder)
				.build())
			.defaultOptions(OpenRouterEmbeddingOptions.builder().model(MODEL).build())
			.retryTemplate(new RetryTemplate(RetryPolicy.builder().maxRetries(0).build()))
			.build();
		return new Fixture(model, server);
	}

	private static void assertInBandDetails(OpenRouterErrorDetails details, int code,
			OpenRouterErrorCategory category) {
		assertThat(details.code()).isEqualTo(Integer.toString(code));
		assertThat(details.message()).isEqualTo("synthetic embedding failure");
		assertThat(details.providerCode()).isEqualTo("synthetic_code");
		assertThat(details.category()).isEqualTo(category);
	}

	private record Fixture(OpenRouterEmbeddingModel model, MockRestServiceServer server) {
	}

}
