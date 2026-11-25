package de.subhransu.openrouter.springai.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.chat.OpenRouterUsage;
import de.subhransu.openrouter.springai.errors.OpenRouterHttpFailure;
import de.subhransu.openrouter.springai.errors.OpenRouterNonTransientApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.content.Media;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.DocumentEmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResultMetadata.ModalityType;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.client.RestClient;

@Tag("embeddings")
@Tag("embeddings.model")
@Tag("multimodal")
class OpenRouterDocumentEmbeddingModelTests {

	private static final String BASE_URL = "https://openrouter.test/api/v1";

	private static final String MODEL = "synthetic/multimodal-embed";

	private static final String IMAGE_URL = "https://images.test/boardwalk.png";

	private static final String TWO_VECTORS = """
			{
			  "data": [
			    {"index": 1, "embedding": [0.75, 1.0], "future_field": true},
			    {"index": 0, "embedding": [0.25, -0.5]}
			  ],
			  "model": "synthetic/multimodal-embed",
			  "usage": {"prompt_tokens": 7, "total_tokens": 7}
			}
			""";

	private Fixture fixture() {
		return fixture(MetadataMode.NONE);
	}

	private Fixture fixture(MetadataMode metadataMode) {
		RestClient.Builder restClientBuilder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey("test-key")
			.baseUrl(BASE_URL)
			.restClientBuilder(restClientBuilder)
			.build();
		OpenRouterDocumentEmbeddingModel model = OpenRouterDocumentEmbeddingModel.builder()
			.openRouterApi(api)
			.options(OpenRouterEmbeddingOptions.builder().model(MODEL).build())
			.metadataMode(metadataMode)
			.retryTemplate(new RetryTemplate(RetryPolicy.builder().maxRetries(0).build()))
			.build();
		return new Fixture(model, server);
	}

	@Test
	void embedsATextDocumentAsATextPart() {
		Fixture fixture = fixture(MetadataMode.EMBED);
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andExpect(jsonPath("$.model").value(MODEL))
			.andExpect(jsonPath("$.input", Matchers.hasSize(1)))
			.andExpect(jsonPath("$.input[0].content", Matchers.hasSize(1)))
			.andExpect(jsonPath("$.input[0].content[0].type").value("text"))
			.andExpect(jsonPath("$.input[0].content[0].text").value("topic: synthetic\n\nsynthetic passage"))
			.andRespond(withSuccess("{\"data\":[{\"index\":0,\"embedding\":[1,2]}]}", MediaType.APPLICATION_JSON));
		Document document = new Document("doc-text", "synthetic passage", Map.of("topic", "synthetic"));

		EmbeddingResponse response = fixture.model().call(new DocumentEmbeddingRequest(document));

		assertThat(response.getResult().getOutput()).containsExactly(1f, 2f);
		assertThat(response.getResult().getMetadata().getDocumentId()).isEqualTo("doc-text");
		assertThat(response.getResult().getMetadata().getModalityType()).isEqualTo(ModalityType.TEXT);
		assertThat(response.getResult().getMetadata().getMimeType()).isEqualTo(MimeTypeUtils.TEXT_PLAIN);
		fixture.server().verify();
	}

	@Test
	void embedsImageUrlsBytesAndDataUrlsAsImageParts() {
		Fixture fixture = fixture();
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andExpect(jsonPath("$.input", Matchers.hasSize(3)))
			.andExpect(jsonPath("$.input[0].content[0].type").value("image_url"))
			.andExpect(jsonPath("$.input[0].content[0].text").doesNotExist())
			.andExpect(jsonPath("$.input[0].content[0].image_url.url").value(IMAGE_URL))
			.andExpect(jsonPath("$.input[1].content[0].image_url.url").value("data:image/png;base64,AQID"))
			.andExpect(jsonPath("$.input[2].content[0].image_url.url").value("data:image/jpeg;base64,BAUG"))
			.andRespond(withSuccess("""
					{"data":[{"index":0,"embedding":[1,0]},{"index":1,"embedding":[0,1]},
					{"index":2,"embedding":[1,1]}]}
					""", MediaType.APPLICATION_JSON));
		Media url = new Media(MimeTypeUtils.IMAGE_PNG, URI.create(IMAGE_URL));
		Media bytes = Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(new byte[] { 1, 2, 3 }).build();
		Media dataUrl = Media.builder().mimeType(MimeTypeUtils.IMAGE_JPEG).data("data:image/jpeg;base64,BAUG").build();

		EmbeddingResponse response = fixture.model()
			.call(new DocumentEmbeddingRequest(new Document("doc-url", url, Map.of()),
					new Document("doc-bytes", bytes, Map.of()), new Document("doc-data", dataUrl, Map.of())));

		assertThat(response.getResults()).hasSize(3);
		assertThat(response.getResults().get(1).getMetadata().getDocumentId()).isEqualTo("doc-bytes");
		assertThat(response.getResults().get(1).getMetadata().getModalityType()).isEqualTo(ModalityType.IMAGE);
		assertThat(response.getResults().get(1).getMetadata().getMimeType()).isEqualTo(MimeTypeUtils.IMAGE_PNG);
		fixture.server().verify();
	}

	@Test
	void batchesTextAndImageDocumentsInInputOrderWhateverTheResponseOrder() {
		Fixture fixture = fixture();
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andExpect(jsonPath("$.input[0].content[0].text").value("synthetic caption"))
			.andExpect(jsonPath("$.input[1].content[0].image_url.url").value(IMAGE_URL))
			.andRespond(withSuccess(TWO_VECTORS, MediaType.APPLICATION_JSON));
		Document text = new Document("doc-text", "synthetic caption", Map.of());
		Document image = new Document("doc-image", new Media(MimeTypeUtils.IMAGE_PNG, URI.create(IMAGE_URL)), Map.of());

		EmbeddingResponse response = fixture.model().call(new DocumentEmbeddingRequest(List.of(text, image)));

		assertThat(response.getResults()).extracting(result -> result.getMetadata().getDocumentId())
			.containsExactly("doc-text", "doc-image");
		assertThat(response.getResults().get(0).getOutput()).containsExactly(0.25f, -0.5f);
		assertThat(response.getResults().get(1).getOutput()).containsExactly(0.75f, 1.0f);
		assertThat(response.getResults().get(1).getIndex()).isEqualTo(1);
		assertThat(response.getMetadata().getModel()).isEqualTo(MODEL);
		assertThat(response.getMetadata().getUsage()).isInstanceOf(OpenRouterUsage.class);
		assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(7);
		fixture.server().verify();
	}

	@Test
	void requestOptionsOverrideTheDefaults() {
		Fixture fixture = fixture();
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andExpect(jsonPath("$.model").value("synthetic/other-embed"))
			.andExpect(jsonPath("$.dimensions").value(2))
			.andExpect(jsonPath("$.input_type").value("document"))
			.andRespond(withSuccess("{\"data\":[{\"index\":0,\"embedding\":[1,2]}]}", MediaType.APPLICATION_JSON));

		fixture.model()
			.call(new DocumentEmbeddingRequest(List.of(new Document("synthetic passage")),
					OpenRouterEmbeddingOptions.builder()
						.model("synthetic/other-embed")
						.dimensions(2)
						.inputType("document")
						.build()));

		fixture.server().verify();
	}

	@Test
	void rejectsNonImageMediaBeforeCallingTheApi() {
		Fixture fixture = fixture();
		fixture.server().expect(never(), requestTo(BASE_URL + "/embeddings"));
		Document pdf = new Document(
				Media.builder().mimeType(MimeTypeUtils.parseMimeType("application/pdf")).data(new byte[] { 1 }).build(),
				Map.of());

		assertThatThrownBy(() -> fixture.model().call(new DocumentEmbeddingRequest(new Document("text"), pdf)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("application/pdf")
			.hasMessageContaining("only image media");
		fixture.server().verify();
	}

	@Test
	void rejectsNullDocumentsBeforeCallingTheApi() {
		Fixture fixture = fixture();
		fixture.server().expect(never(), requestTo(BASE_URL + "/embeddings"));
		List<Document> documents = new ArrayList<>();
		documents.add(null);

		assertThatThrownBy(() -> fixture.model().call(new DocumentEmbeddingRequest(documents)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("null elements");
		fixture.server().verify();
	}

	@Test
	void surfacesAProviderRejectionOfImageInput() {
		Fixture fixture = fixture();
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
				.body("{\"error\":{\"code\":400,\"message\":\"synthetic: model does not accept image input\"}}"));
		Document image = new Document(new Media(MimeTypeUtils.IMAGE_PNG, URI.create(IMAGE_URL)), Map.of());

		assertThatThrownBy(() -> fixture.model().call(new DocumentEmbeddingRequest(image)))
			.isInstanceOf(OpenRouterNonTransientApiException.class)
			.satisfies(failure -> assertThat(((OpenRouterHttpFailure) failure).getErrorDetails().message())
				.isEqualTo("synthetic: model does not accept image input"));
		fixture.server().verify();
	}

	@Test
	void rejectsAResponseWhoseCountDoesNotMatchTheDocuments() {
		Fixture fixture = fixture();
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andRespond(withSuccess("{\"data\":[{\"index\":0,\"embedding\":[1,2]}]}", MediaType.APPLICATION_JSON));
		Document image = new Document(new Media(MimeTypeUtils.IMAGE_PNG, URI.create(IMAGE_URL)), Map.of());

		assertThatThrownBy(
				() -> fixture.model().call(new DocumentEmbeddingRequest(new Document("synthetic caption"), image)))
			.isInstanceOf(OpenRouterProtocolException.class)
			.hasMessageContaining("count");
		fixture.server().verify();
	}

	@Test
	void dimensionsUseTheConfiguredValueOrOneTextProbe() {
		Fixture fixture = fixture();
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andExpect(jsonPath("$.input[0]").value("Test String"))
			.andRespond(withSuccess("{\"data\":[{\"index\":0,\"embedding\":[1,2,3]}]}", MediaType.APPLICATION_JSON));

		assertThat(fixture.model().dimensions()).isEqualTo(3);
		assertThat(fixture.model().dimensions()).isEqualTo(3);
		assertThat(OpenRouterDocumentEmbeddingModel.builder()
			.openRouterApi(OpenRouterApi.builder().apiKey("test-key").baseUrl(BASE_URL).build())
			.options(OpenRouterEmbeddingOptions.builder().model(MODEL).dimensions(64).build())
			.build()
			.dimensions()).isEqualTo(64);
		fixture.server().verify();
	}

	private record Fixture(OpenRouterDocumentEmbeddingModel model, MockRestServiceServer server) {
	}

}
