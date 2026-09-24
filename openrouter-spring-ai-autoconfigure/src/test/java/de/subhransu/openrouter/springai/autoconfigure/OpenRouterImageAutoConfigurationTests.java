package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.image.OpenRouterImageModel;
import de.subhransu.openrouter.springai.image.OpenRouterImageOptions;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;

class OpenRouterImageAutoConfigurationTests {

	private static final String API_KEY_PROPERTY = "spring.ai.openrouter.api-key=test-key";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withConfiguration(
			AutoConfigurations.of(OpenRouterApiAutoConfiguration.class, OpenRouterImageAutoConfiguration.class));

	@Test
	void createsImageModelWhenApiKeyIsConfigured() {
		this.contextRunner.withPropertyValues(API_KEY_PROPERTY).run(context -> {
			assertThat(context).hasSingleBean(OpenRouterImageModel.class);
			assertThat(context).hasSingleBean(ImageModel.class);
		});
	}

	@Test
	void bindsImageOptionsFromProperties() {
		this.contextRunner
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
				OpenRouterImageOptions options = (OpenRouterImageOptions) ReflectionTestUtils.getField(imageModel,
						"defaultOptions");
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
			});
	}

	@Test
	void disablesImageModelWhenAnotherProviderIsSelected() {
		this.contextRunner.withPropertyValues(API_KEY_PROPERTY, "spring.ai.model.image=other")
			.run(context -> assertThat(context).doesNotHaveBean(OpenRouterImageModel.class));
	}

	@Test
	void backsOffWhenUserDefinesOwnImageModel() {
		this.contextRunner.withUserConfiguration(CustomImageModelConfiguration.class)
			.withPropertyValues(API_KEY_PROPERTY)
			.run(context -> {
				assertThat(context).doesNotHaveBean(OpenRouterImageModel.class);
				assertThat(context).hasSingleBean(ImageModel.class);
			});
	}

	@Test
	void injectsUserDefinedObservationRegistryIntoImageModel() {
		this.contextRunner.withUserConfiguration(ObservationRegistryConfiguration.class)
			.withPropertyValues(API_KEY_PROPERTY)
			.run(context -> {
				OpenRouterImageModel imageModel = context.getBean(OpenRouterImageModel.class);
				assertThat(ReflectionTestUtils.getField(imageModel, "observationRegistry"))
					.isSameAs(context.getBean(ObservationRegistry.class));
			});
	}

	@Configuration(proxyBeanMethods = false)
	static class CustomImageModelConfiguration {

		@Bean
		ImageModel customImageModel() {
			return new ImageModel() {

				@Override
				public ImageResponse call(ImagePrompt prompt) {
					throw new UnsupportedOperationException();
				}
			};
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class ObservationRegistryConfiguration {

		@Bean
		ObservationRegistry observationRegistry() {
			return ObservationRegistry.create();
		}

	}

}
