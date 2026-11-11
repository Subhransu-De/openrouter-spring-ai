package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.dto.EmbeddingsRequest;
import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingModel;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingOptions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OpenRouterEmbeddingAutoConfigurationTests {

	private static final String PASSAGE = "synthetic passage";

	private static final String API_KEY_PROPERTY = "spring.ai.openrouter.api-key=test-key";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withConfiguration(
			AutoConfigurations.of(OpenRouterApiAutoConfiguration.class, OpenRouterEmbeddingAutoConfiguration.class));

	@Test
	void withoutPropertiesTheModelEmbedsOnlyTheDocumentTextAndSendsNoOptionalFields() {
		OpenRouterApi api = SyntheticModels.succeedingApi();
		this.contextRunner.withBean(OpenRouterApi.class, () -> api)
			.withPropertyValues(API_KEY_PROPERTY, "spring.ai.openrouter.embedding.model=synthetic/embedding")
			.run(context -> {
				OpenRouterEmbeddingModel model = context.getBean(OpenRouterEmbeddingModel.class);
				assertThat(model.getEmbeddingContent(new Document(PASSAGE, Map.of("topic", "synthetic-topic"))))
					.isEqualTo(PASSAGE);
				model.embed(PASSAGE);
				ArgumentCaptor<EmbeddingsRequest> request = ArgumentCaptor.forClass(EmbeddingsRequest.class);
				verify(api).embeddings(request.capture());
				assertThat(request.getValue()).satisfies(sent -> {
					assertThat(sent.dimensions()).isNull();
					assertThat(sent.encodingFormat()).isNull();
					assertThat(sent.user()).isNull();
					assertThat(sent.provider()).isNull();
				});
			});
	}

	@Test
	void anEncodingFormatTheDecoderCannotReadIsRejectedBeforeAnyRequest() {
		OpenRouterApi api = SyntheticModels.succeedingApi();
		this.contextRunner.withBean(OpenRouterApi.class, () -> api)
			.withPropertyValues(API_KEY_PROPERTY, "spring.ai.openrouter.embedding.model=synthetic/embedding",
					"spring.ai.openrouter.embedding.encoding-format=base64")
			.run(context -> {
				OpenRouterEmbeddingModel model = context.getBean(OpenRouterEmbeddingModel.class);

				assertThatThrownBy(() -> model.embed(PASSAGE)).isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("Unsupported encoding_format 'base64'");
				verifyNoInteractions(api);
			});
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "-256" })
	void nonPositiveConfiguredDimensionsAreRejected(String dimensions) {
		OpenRouterApi api = SyntheticModels.succeedingApi();
		this.contextRunner.withBean(OpenRouterApi.class, () -> api)
			.withPropertyValues(API_KEY_PROPERTY, "spring.ai.openrouter.embedding.model=synthetic/embedding",
					"spring.ai.openrouter.embedding.dimensions=" + dimensions)
			.run(context -> {
				OpenRouterEmbeddingModel model = context.getBean(OpenRouterEmbeddingModel.class);

				assertThatThrownBy(model::dimensions).isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("Dimensions must be positive");
			});
	}

	@Test
	void boundEmbeddingPropertiesBecomeDefaultsAndReachTheRequest() {
		OpenRouterApi api = SyntheticModels.succeedingApi();
		this.contextRunner.withBean(OpenRouterApi.class, () -> api)
			.withPropertyValues(API_KEY_PROPERTY, "spring.ai.openrouter.embedding.model=openai/text-embedding-3-small",
					"spring.ai.openrouter.embedding.dimensions=256",
					"spring.ai.openrouter.embedding.metadata-mode=embed",
					"spring.ai.openrouter.embedding.encoding-format=float",
					"spring.ai.openrouter.embedding.user=embed-user",
					"spring.ai.openrouter.embedding.provider.data-collection=deny",
					"spring.ai.openrouter.embedding.provider.order[0]=synthetic-a",
					"spring.ai.openrouter.embedding.provider.sort=price")
			.run(context -> {
				OpenRouterEmbeddingModel model = context.getBean(OpenRouterEmbeddingModel.class);
				OpenRouterEmbeddingOptions options = model.getOptions();
				assertThat(options.getModel()).isEqualTo("openai/text-embedding-3-small");
				assertThat(options.getDimensions()).isEqualTo(256);
				assertThat(options.getEncodingFormat()).isEqualTo("float");
				assertThat(options.getUser()).isEqualTo("embed-user");
				assertThat(options.getProvider()).isEqualTo(new OpenRouterProviderPreferences(null, null, "deny",
						List.of("synthetic-a"), null, null, "price"));
				assertThat(model.dimensions()).isEqualTo(256);
				assertThat(model.getEmbeddingContent(new Document(PASSAGE, Map.of("topic", "synthetic-topic"))))
					.isEqualTo("topic: synthetic-topic\n\nsynthetic passage");

				model.embed(PASSAGE);
				ArgumentCaptor<EmbeddingsRequest> request = ArgumentCaptor.forClass(EmbeddingsRequest.class);
				verify(api).embeddings(request.capture());
				EmbeddingsRequest sent = request.getValue();
				assertThat(sent.model()).isEqualTo("openai/text-embedding-3-small");
				assertThat(sent.dimensions()).isEqualTo(256);
				assertThat(sent.encodingFormat()).isEqualTo("float");
				assertThat(sent.user()).isEqualTo("embed-user");
				assertThat(sent.provider()).isNotNull();
				assertThat(sent.provider().order()).containsExactly("synthetic-a");
			});
	}

}
