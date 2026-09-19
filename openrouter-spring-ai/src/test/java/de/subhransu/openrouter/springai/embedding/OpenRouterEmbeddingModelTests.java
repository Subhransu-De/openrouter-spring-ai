package de.subhransu.openrouter.springai.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.chat.OpenRouterUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.document.DefaultContentFormatter;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenRouterEmbeddingModelTests {

	private static final String BASE_URL = "https://openrouter.test/api/v1";

	private static final String DOCUMENT_TEXT = "synthetic passage";

	private static final String SINGLE_EMBEDDING_BODY = "{\"data\":[{\"index\":0,\"embedding\":[1,2]}]}";

	private static final String MODEL = "openai/text-embedding-3-small";

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

	@Test
	void runtimeOptionsOverrideDefaults() {
		Fixture fixture = fixture(OpenRouterEmbeddingOptions.builder().model(MODEL).dimensions(2).build());
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andExpect(jsonPath("$.model").value("qwen/qwen3-embedding-8b"))
			.andExpect(jsonPath("$.dimensions").value(2))
			.andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

		fixture.model()
			.call(new EmbeddingRequest(List.of("hello", "world"),
					OpenRouterEmbeddingOptions.builder().model("qwen/qwen3-embedding-8b").build()));

		fixture.server().verify();
	}

	@Test
	void embedsSingleTextAndDocumentThroughConvenienceMethods() {
		Fixture fixture = fixture(OpenRouterEmbeddingOptions.builder().model(MODEL).build());
		fixture.server().expect(once(), requestTo(BASE_URL + "/embeddings")).andRespond(withSuccess("""
				{"data":[{"index":0,"embedding":[0.25,-0.5]}]}
				""", MediaType.APPLICATION_JSON));

		float[] embedding = fixture.model().embed(new Document("document text"));

		assertThat(embedding).containsExactly(0.25f, -0.5f);
		fixture.server().verify();
	}

	@Test
	void rejectsNonFloatEncodingFormatBeforeCallingTheApi() {
		Fixture fixture = fixture(OpenRouterEmbeddingOptions.builder().model(MODEL).encodingFormat("base64").build());
		OpenRouterEmbeddingModel model = fixture.model();

		assertThatThrownBy(() -> model.embed("hello")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("encoding_format");
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

	@ParameterizedTest
	@ValueSource(strings = { "null", "[]", "[{\"index\":0,\"embedding\":[1]}]",
			"[{\"index\":0,\"embedding\":[1]},{\"index\":1,\"embedding\":[2]},{\"index\":2,\"embedding\":[3]}]",
			"[null,{\"index\":1,\"embedding\":[2]}]",
			"[{\"index\":null,\"embedding\":[1]},{\"index\":1,\"embedding\":[2]}]",
			"[{\"embedding\":[1]},{\"index\":1,\"embedding\":[2]}]",
			"[{\"index\":-1,\"embedding\":[1]},{\"index\":1,\"embedding\":[2]}]",
			"[{\"index\":2,\"embedding\":[1]},{\"index\":1,\"embedding\":[2]}]",
			"[{\"index\":0,\"embedding\":[1]},{\"index\":0,\"embedding\":[2]}]",
			"[{\"index\":0,\"embedding\":null},{\"index\":1,\"embedding\":[2]}]",
			"[{\"index\":0,\"embedding\":[]},{\"index\":1,\"embedding\":[2]}]",
			"[{\"index\":0,\"embedding\":[1,2]},{\"index\":1,\"embedding\":[2]}]",
			"[{\"index\":0,\"embedding\":[1e100]},{\"index\":1,\"embedding\":[2]}]" })
	void rejectsMalformedResults(String data) {
		Fixture fixture = fixture(OpenRouterEmbeddingOptions.builder().model(MODEL).build());
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andRespond(withSuccess("{\"data\":" + data + "}", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> fixture.model().embed(List.of("first", "second")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Embedding response");
		fixture.server().verify();
	}

	@ParameterizedTest
	@CsvSource({ "2, , true", "3, , false", "3, 2, true", "2, 3, false" })
	void validatesMergedDimensions(int defaultDimensions, Integer runtimeDimensions, boolean valid) {
		Fixture fixture = fixture(
				OpenRouterEmbeddingOptions.builder().model(MODEL).dimensions(defaultDimensions).build());
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andExpect(
					jsonPath("$.dimensions").value(runtimeDimensions == null ? defaultDimensions : runtimeDimensions))
			.andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));
		EmbeddingRequest request = new EmbeddingRequest(List.of("first", "second"),
				OpenRouterEmbeddingOptions.builder().dimensions(runtimeDimensions).build());

		if (valid) {
			assertThat(fixture.model().call(request).getResults()).hasSize(2);
		}
		else {
			assertThatThrownBy(() -> fixture.model().call(request)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("dimensions");
		}
		fixture.server().verify();
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
			case ALL -> document.getFormattedContent(MetadataMode.ALL);
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
	void defaultMetadataPolicyPreservesRawTextEvenWithCustomFormatter() {
		OpenRouterEmbeddingModel model = OpenRouterEmbeddingModel.builder()
			.openRouterApi(OpenRouterApi.builder().apiKey("test-key").build())
			.build();
		Document document = new Document(DOCUMENT_TEXT, Map.of("topic", "synthetic-topic"));
		document.setContentFormatter((value, mode) -> "custom formatted content");
		assertThat(model.getEmbeddingContent(document)).isEqualTo(DOCUMENT_TEXT);
		assertThatThrownBy(() -> OpenRouterEmbeddingModel.builder().metadataMode(null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void configuredDimensionsUseSnapshotWithoutProbing() {
		OpenRouterEmbeddingOptions options = OpenRouterEmbeddingOptions.builder().model(MODEL).dimensions(2).build();
		Fixture first = fixture(options);
		options.setDimensions(3);
		Fixture second = fixture(options);
		assertThat(first.model().dimensions()).isEqualTo(2);
		assertThat(second.model().dimensions()).isEqualTo(3);
		first.server().verify();
		second.server().verify();
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, -1 })
	void rejectsInvalidConfiguredDimensionsWithoutProbing(int dimensions) {
		Fixture fixture = fixture(OpenRouterEmbeddingOptions.builder().model(MODEL).dimensions(dimensions).build());
		assertThatThrownBy(() -> fixture.model().dimensions()).isInstanceOf(IllegalArgumentException.class);
		fixture.server().verify();
	}

	@Test
	void dimensionDiscoveryIsIsolatedFromOtherModelsAndRequestOverrides() {
		OpenRouterEmbeddingOptions options = OpenRouterEmbeddingOptions.builder().model("synthetic/model-a").build();
		Fixture first = fixture(options);
		options.setModel("synthetic/model-b");
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

	@ParameterizedTest
	@ValueSource(strings = { "[]", "null", "http-error" })
	void failedDimensionDiscoveryCanRecover(String vector) {
		Fixture fixture = fixture(OpenRouterEmbeddingOptions.builder().model(MODEL).build());
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andRespond("http-error".equals(vector) ? withBadRequest() : withSuccess(
					"{\"data\":[{\"index\":0,\"embedding\":" + vector + "}]}", MediaType.APPLICATION_JSON));
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andRespond(withSuccess(SINGLE_EMBEDDING_BODY, MediaType.APPLICATION_JSON));
		assertThatThrownBy(() -> fixture.model().dimensions()).isInstanceOf(RuntimeException.class);
		assertThat(fixture.model().dimensions()).isEqualTo(2);
		assertThat(fixture.model().dimensions()).isEqualTo(2);
		fixture.server().verify();
	}

	@Test
	@SuppressWarnings("PMD.CloseResource") // Java 17 has no ExecutorService.close(); the
											// finally block shuts it down.
	void concurrentDimensionDiscoveryUsesOneProbe() throws Exception {
		Fixture fixture = fixture(OpenRouterEmbeddingOptions.builder().model(MODEL).build());
		CountDownLatch started = new CountDownLatch(4);
		fixture.server().expect(once(), requestTo(BASE_URL + "/embeddings")).andRespond(request -> {
			try {
				assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new AssertionError(ex);
			}
			return withSuccess(SINGLE_EMBEDDING_BODY, MediaType.APPLICATION_JSON).createResponse(request);
		});
		var executor = Executors.newFixedThreadPool(4);
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				results.add(executor.submit(() -> {
					started.countDown();
					return fixture.model().dimensions();
				}));
			}
			for (Future<Integer> result : results) {
				assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo(2);
			}
		}
		finally {
			executor.shutdownNow();
		}
		fixture.server().verify();
	}

	private record Fixture(OpenRouterEmbeddingModel model, MockRestServiceServer server) {
	}

}
