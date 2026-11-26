package de.subhransu.openrouter.springai.starter.integration;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.autoconfigure.OpenRouterApiAutoConfiguration;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterDocumentEmbeddingAutoConfiguration;
import de.subhransu.openrouter.springai.chat.OpenRouterUsage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.content.Media;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.DocumentEmbeddingModel;
import org.springframework.ai.embedding.DocumentEmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResultMetadata.ModalityType;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.databind.JsonNode;

/**
 * Text and image documents on the wire, sent by the auto-configured document embedding
 * model to the OpenRouter mock. The stub answers only a request with an image part and
 * returns its vectors out of order.
 */
@Tag("http")
@Tag("api.wire")
class OpenRouterDocumentEmbeddingIntegrationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(OpenRouterApiAutoConfiguration.class,
				OpenRouterDocumentEmbeddingAutoConfiguration.class))
		.withPropertyValues("spring.ai.openrouter.api-key=garage-mock-key",
				"spring.ai.openrouter.base-url=" + OpenRouterMock.baseUrl(),
				"spring.ai.model.embedding.multimodal=openrouter",
				"spring.ai.openrouter.embedding.model=synthetic/multimodal-embedding",
				"spring.ai.openrouter.embedding.dimensions=3");

	@BeforeEach
	void resetMock() {
		OpenRouterMock.reset();
	}

	@Test
	void aTextAndAnImageDocumentAreSentAsPartsAndMappedBackByIndex() {
		Document text = new Document("doc-text", "Brake pads worn to 2 mm.", Map.of());
		Document image = new Document("doc-image",
				Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(new byte[] { 1, 2, 3 }).build(), Map.of());

		this.contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			EmbeddingResponse response = context.getBean(DocumentEmbeddingModel.class)
				.call(new DocumentEmbeddingRequest(text, image));

			assertThat(response.getResults()).hasSize(2);
			assertThat(response.getResults().get(0).getOutput()).containsExactly(0.64f, 0.2f, 0.47f);
			assertThat(response.getResults().get(0).getMetadata().getDocumentId()).isEqualTo("doc-text");
			assertThat(response.getResults().get(0).getMetadata().getModalityType()).isEqualTo(ModalityType.TEXT);
			assertThat(response.getResults().get(1).getOutput()).containsExactly(0.11f, 0.72f, 0.31f);
			assertThat(response.getResults().get(1).getMetadata().getDocumentId()).isEqualTo("doc-image");
			assertThat(response.getResults().get(1).getMetadata().getModalityType()).isEqualTo(ModalityType.IMAGE);
			assertThat(response.getMetadata().getModel()).isEqualTo("synthetic/multimodal-embedding");
			assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(21);
			assertThat(OpenRouterUsage.from(response).getCost()).isEqualTo(4.2e-07);
		});

		List<JsonNode> requests = OpenRouterMock.requests("/embeddings");
		assertThat(requests).hasSize(1);
		JsonNode body = requests.get(0);
		assertThat(body.path("model").asString()).isEqualTo("synthetic/multimodal-embedding");
		assertThat(body.path("dimensions").asInt()).isEqualTo(3);
		assertThat(body.path("input")).hasSize(2);
		JsonNode textPart = body.path("input").get(0).path("content").get(0);
		assertThat(textPart.path("type").asString()).isEqualTo("text");
		assertThat(textPart.path("text").asString()).isEqualTo("Brake pads worn to 2 mm.");
		JsonNode imagePart = body.path("input").get(1).path("content").get(0);
		assertThat(imagePart.path("type").asString()).isEqualTo("image_url");
		assertThat(imagePart.path("image_url").path("url").asString()).isEqualTo("data:image/png;base64,AQID");
		assertThat(OpenRouterMock.unmatched()).isEmpty();
	}

}
