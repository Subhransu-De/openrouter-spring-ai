package de.subhransu.openrouter.springai.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.image.ImageOptions;
import org.springframework.ai.image.ImageOptionsBuilder;

class OpenRouterImageOptionsTests {

	private static final String MODEL = "bytedance-seed/seedream-4.5";

	@Test
	void mergePrefersRuntimeValuesAndKeepsDefaultsForUnsetFields() {
		OpenRouterImageOptions defaults = OpenRouterImageOptions.builder()
			.model(MODEL)
			.quality("high")
			.outputFormat("webp")
			.aspectRatio("1:1")
			.build();

		OpenRouterImageOptions merged = defaults
			.merge(OpenRouterImageOptions.fromOptions(OpenRouterImageOptions.builder()
				.model("openai/gpt-image-1")
				.aspectRatio("16:9")
				.providerOptions(Map.of("options", Map.of("test", Map.of("watermark", false))))
				.build()));

		assertThat(merged.getModel()).isEqualTo("openai/gpt-image-1");
		assertThat(merged.getAspectRatio()).isEqualTo("16:9");
		assertThat(merged.getQuality()).isEqualTo("high");
		assertThat(merged.getOutputFormat()).isEqualTo("webp");
		assertThat(merged.getProviderOptions()).containsEntry("options", Map.of("test", Map.of("watermark", false)));
	}

	@Test
	void copyIsIndependentOfTheSource() {
		OpenRouterImageOptions source = OpenRouterImageOptions.builder()
			.model(MODEL)
			.inputReferences(List.of("https://example.test/ref.png"))
			.providerOptions(Map.of("openai", Map.of("moderation", "low")))
			.build();

		OpenRouterImageOptions copy = source.copy();

		assertThat(copy.getInputReferences()).isNotSameAs(source.getInputReferences())
			.containsExactly("https://example.test/ref.png");
		assertThat(copy.getProviderOptions()).isNotSameAs(source.getProviderOptions())
			.containsEntry("openai", Map.of("moderation", "low"));
	}

	@Test
	void fromOptionsCarriesPortableFieldsFromForeignImplementations() {
		ImageOptions portable = ImageOptionsBuilder.builder().model(MODEL).n(3).width(1024).height(1024).build();

		OpenRouterImageOptions options = OpenRouterImageOptions.fromOptions(portable);

		assertThat(options.getModel()).isEqualTo(MODEL);
		assertThat(options.getN()).isEqualTo(3);
		assertThat(options.getWidth()).isEqualTo(1024);
		assertThat(options.getHeight()).isEqualTo(1024);
	}

	@Test
	void responseFormatIsAlwaysBase64AndStyleIsUnsupported() {
		OpenRouterImageOptions options = OpenRouterImageOptions.builder().model(MODEL).build();

		assertThat(options.getResponseFormat()).isEqualTo("b64_json");
		assertThat(options.getStyle()).isNull();
	}

}
