package de.subhransu.openrouter.springai.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.image.ImageOptions;
import org.springframework.ai.image.ImageOptionsBuilder;

/**
 * Merge, copy and conversion rules of {@link OpenRouterImageOptions}. How the merged
 * values reach the wire is pinned in {@code OpenRouterImageModelTests}.
 */
class OpenRouterImageOptionsTests {

	private static final String MODEL = "bytedance-seed/seedream-4.5";

	private static OpenRouterImageOptions.Builder defaults() {
		return OpenRouterImageOptions.builder()
			.model(MODEL)
			.n(1)
			.width(512)
			.height(512)
			.resolution("1K")
			.aspectRatio("1:1")
			.quality("high")
			.outputFormat("webp")
			.background("opaque")
			.outputCompression(80)
			.seed(7)
			.inputReferences(List.of("https://example.com/default.png"))
			.providerOptions(Map.of("options", Map.of("test", Map.of("watermark", true))));
	}

	static Stream<Arguments> singleFieldOverrides() {
		return Stream.of(
				argumentSet("model",
						(UnaryOperator<OpenRouterImageOptions.Builder>) b -> b.model("openai/gpt-image-1")),
				argumentSet("n", (UnaryOperator<OpenRouterImageOptions.Builder>) b -> b.n(3)),
				argumentSet("width", (UnaryOperator<OpenRouterImageOptions.Builder>) b -> b.width(1024)),
				argumentSet("height", (UnaryOperator<OpenRouterImageOptions.Builder>) b -> b.height(768)),
				argumentSet("resolution", (UnaryOperator<OpenRouterImageOptions.Builder>) b -> b.resolution("4K")),
				argumentSet("aspect ratio", (UnaryOperator<OpenRouterImageOptions.Builder>) b -> b.aspectRatio("16:9")),
				argumentSet("quality", (UnaryOperator<OpenRouterImageOptions.Builder>) b -> b.quality("low")),
				argumentSet("output format",
						(UnaryOperator<OpenRouterImageOptions.Builder>) b -> b.outputFormat("png")),
				argumentSet("background",
						(UnaryOperator<OpenRouterImageOptions.Builder>) b -> b.background("transparent")),
				argumentSet("output compression",
						(UnaryOperator<OpenRouterImageOptions.Builder>) b -> b.outputCompression(20)),
				argumentSet("seed", (UnaryOperator<OpenRouterImageOptions.Builder>) b -> b.seed(99)),
				argumentSet("input references",
						(UnaryOperator<OpenRouterImageOptions.Builder>) b -> b
							.inputReferences(List.of("https://example.com/request.png"))),
				argumentSet("provider options", (UnaryOperator<OpenRouterImageOptions.Builder>) b -> b
					.providerOptions(Map.of("options", Map.of("test", Map.of("watermark", false))))));
	}

	@ParameterizedTest
	@MethodSource("singleFieldOverrides")
	void mergeOverridesOnlyTheFieldTheRuntimeOptionsSet(UnaryOperator<OpenRouterImageOptions.Builder> change) {
		OpenRouterImageOptions runtime = change.apply(OpenRouterImageOptions.builder()).build();

		OpenRouterImageOptions merged = defaults().build().merge(runtime);

		assertThat(merged).usingRecursiveComparison().isEqualTo(change.apply(defaults()).build());
		assertThat(merged).usingRecursiveComparison().isNotEqualTo(defaults().build());
	}

	@Test
	void mergeWithNothingOrEmptyRuntimeOptionsReturnsAnEqualCopyOfTheDefaults() {
		OpenRouterImageOptions base = defaults().build();

		OpenRouterImageOptions mergedWithNull = base.merge(null);
		OpenRouterImageOptions mergedWithEmpty = base.merge(OpenRouterImageOptions.builder().build());

		assertThat(mergedWithNull).isNotSameAs(base).usingRecursiveComparison().isEqualTo(base);
		assertThat(mergedWithEmpty).isNotSameAs(base).usingRecursiveComparison().isEqualTo(base);
	}

	@Test
	void mergeDoesNotChangeEitherInput() {
		OpenRouterImageOptions base = defaults().build();
		OpenRouterImageOptions runtime = OpenRouterImageOptions.builder().seed(99).build();

		base.merge(runtime);

		assertThat(base).usingRecursiveComparison().isEqualTo(defaults().build());
		assertThat(runtime).usingRecursiveComparison().isEqualTo(OpenRouterImageOptions.builder().seed(99).build());
	}

	@Test
	void fromOptionsOfAForeignImplementationCarriesOnlyThePortableFields() {
		ImageOptions portable = ImageOptionsBuilder.builder()
			.model("openai/gpt-image-1")
			.n(2)
			.width(256)
			.height(128)
			.responseFormat("b64_json")
			.build();

		OpenRouterImageOptions options = OpenRouterImageOptions.fromOptions(portable);

		assertThat(options).usingRecursiveComparison()
			.isEqualTo(
					OpenRouterImageOptions.builder().model("openai/gpt-image-1").n(2).width(256).height(128).build());
	}

	@Test
	void fromOptionsOfAnOpenRouterInstanceCopiesEveryFieldIntoANewInstance() {
		OpenRouterImageOptions source = defaults().build();

		OpenRouterImageOptions copy = OpenRouterImageOptions.fromOptions(source);

		assertThat(copy).isNotSameAs(source).usingRecursiveComparison().isEqualTo(source);
		assertThat(OpenRouterImageOptions.fromOptions(null)).isNull();
	}

}
