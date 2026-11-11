package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.dto.ContentPart;
import de.subhransu.openrouter.springai.api.dto.ImagesRequest;
import de.subhransu.openrouter.springai.image.OpenRouterImageModel;
import de.subhransu.openrouter.springai.image.OpenRouterImageOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OpenRouterImageAutoConfigurationTests {

	private static final String API_KEY_PROPERTY = "spring.ai.openrouter.api-key=test-key";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withConfiguration(
			AutoConfigurations.of(OpenRouterApiAutoConfiguration.class, OpenRouterImageAutoConfiguration.class));

	@Test
	void withoutPropertiesTheImageModelSendsNoOptionalFields() {
		OpenRouterApi api = SyntheticModels.succeedingApi();
		this.contextRunner.withBean(OpenRouterApi.class, () -> api)
			.withPropertyValues(API_KEY_PROPERTY, "spring.ai.openrouter.image.model=synthetic/image")
			.run(context -> {
				context.getBean(OpenRouterImageModel.class).call(new ImagePrompt("a synthetic prompt"));

				ArgumentCaptor<ImagesRequest> request = ArgumentCaptor.forClass(ImagesRequest.class);
				verify(api).images(request.capture());
				ImagesRequest sent = request.getValue();
				assertThat(sent.model()).isEqualTo("synthetic/image");
				assertThat(sent)
					.extracting(ImagesRequest::n, ImagesRequest::aspectRatio, ImagesRequest::resolution,
							ImagesRequest::quality, ImagesRequest::outputFormat, ImagesRequest::background,
							ImagesRequest::outputCompression, ImagesRequest::seed, ImagesRequest::size,
							ImagesRequest::inputReferences, ImagesRequest::provider)
					.containsOnlyNulls();
			});
	}

	@ParameterizedTest
	@ValueSource(strings = { "width=1024", "height=576" })
	void aSizeWithOnlyOneDimensionIsRejectedBeforeAnyRequest(String property) {
		OpenRouterApi api = SyntheticModels.succeedingApi();
		this.contextRunner.withBean(OpenRouterApi.class, () -> api)
			.withPropertyValues(API_KEY_PROPERTY, "spring.ai.openrouter.image.model=synthetic/image",
					"spring.ai.openrouter.image." + property)
			.run(context -> {
				OpenRouterImageModel model = context.getBean(OpenRouterImageModel.class);

				assertThatThrownBy(() -> model.call(new ImagePrompt("a synthetic prompt")))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("Both width and height must be set");
				verifyNoInteractions(api);
			});
	}

	@Test
	void boundImagePropertiesBecomeDefaultsAndReachTheRequest() {
		OpenRouterApi api = SyntheticModels.succeedingApi();
		this.contextRunner.withBean(OpenRouterApi.class, () -> api)
			.withPropertyValues(API_KEY_PROPERTY, "spring.ai.openrouter.image.model=bytedance-seed/seedream-4.5",
					"spring.ai.openrouter.image.n=2", "spring.ai.openrouter.image.aspect-ratio=16:9",
					"spring.ai.openrouter.image.resolution=2K", "spring.ai.openrouter.image.quality=high",
					"spring.ai.openrouter.image.output-format=webp",
					"spring.ai.openrouter.image.background=transparent",
					"spring.ai.openrouter.image.output-compression=80", "spring.ai.openrouter.image.seed=42",
					"spring.ai.openrouter.image.width=1024", "spring.ai.openrouter.image.height=576",
					"spring.ai.openrouter.image.input-references[0]=https://example.com/input.png",
					"spring.ai.openrouter.image.provider-options.style=natural")
			.run(context -> {
				OpenRouterImageModel imageModel = context.getBean(OpenRouterImageModel.class);
				OpenRouterImageOptions options = imageModel.getOptions();
				assertThat(options.getModel()).isEqualTo("bytedance-seed/seedream-4.5");
				assertThat(options.getN()).isEqualTo(2);
				assertThat(options.getAspectRatio()).isEqualTo("16:9");
				assertThat(options.getResolution()).isEqualTo("2K");
				assertThat(options.getQuality()).isEqualTo("high");
				assertThat(options.getOutputFormat()).isEqualTo("webp");
				assertThat(options.getBackground()).isEqualTo("transparent");
				assertThat(options.getOutputCompression()).isEqualTo(80);
				assertThat(options.getSeed()).isEqualTo(42);
				assertThat(options.getWidth()).isEqualTo(1024);
				assertThat(options.getHeight()).isEqualTo(576);
				assertThat(options.getInputReferences()).containsExactly("https://example.com/input.png");
				assertThat(options.getProviderOptions()).containsEntry("style", "natural");

				imageModel.call(new ImagePrompt("a synthetic prompt"));
				ArgumentCaptor<ImagesRequest> request = ArgumentCaptor.forClass(ImagesRequest.class);
				verify(api).images(request.capture());
				ImagesRequest sent = request.getValue();
				assertThat(sent.model()).isEqualTo("bytedance-seed/seedream-4.5");
				assertThat(sent.n()).isEqualTo(2);
				assertThat(sent.aspectRatio()).isEqualTo("16:9");
				assertThat(sent.resolution()).isEqualTo("2K");
				assertThat(sent.quality()).isEqualTo("high");
				assertThat(sent.outputFormat()).isEqualTo("webp");
				assertThat(sent.background()).isEqualTo("transparent");
				assertThat(sent.outputCompression()).isEqualTo(80);
				assertThat(sent.seed()).isEqualTo(42);
				assertThat(sent.size()).isEqualTo("1024x576");
				assertThat(sent.inputReferences()).containsExactly(ContentPart.image("https://example.com/input.png"));
				assertThat(sent.provider()).containsEntry("style", "natural");
			});
	}

}
