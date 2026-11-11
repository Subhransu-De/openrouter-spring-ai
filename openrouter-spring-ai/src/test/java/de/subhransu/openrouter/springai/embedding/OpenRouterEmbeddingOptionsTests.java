package de.subhransu.openrouter.springai.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.embedding.EmbeddingOptions;

/**
 * Merge, copy and conversion rules of {@link OpenRouterEmbeddingOptions}. How the merged
 * values reach the wire, including defaults and per-request overrides of {@code user} and
 * {@code provider}, is pinned in {@code OpenRouterEmbeddingModelTests}.
 */
class OpenRouterEmbeddingOptionsTests {

	private static final String MODEL = "openai/text-embedding-3-small";

	private static final OpenRouterProviderPreferences DEFAULT_PROVIDER = new OpenRouterProviderPreferences(true, null,
			"deny", List.of("openai"), null, null, null);

	private static final OpenRouterProviderPreferences OTHER_PROVIDER = new OpenRouterProviderPreferences(false, true,
			"allow", List.of("azure"), null, null, null);

	private static OpenRouterEmbeddingOptions.Builder defaults() {
		return OpenRouterEmbeddingOptions.builder()
			.model(MODEL)
			.dimensions(256)
			.encodingFormat("float")
			.user("default-user")
			.provider(DEFAULT_PROVIDER);
	}

	static Stream<Arguments> singleFieldOverrides() {
		return Stream.of(
				argumentSet("model", OpenRouterEmbeddingOptions.builder().model("qwen/qwen3-embedding-8b").build(),
						(UnaryOperator<OpenRouterEmbeddingOptions.Builder>) b -> b.model("qwen/qwen3-embedding-8b")),
				argumentSet("dimensions", OpenRouterEmbeddingOptions.builder().dimensions(512).build(),
						(UnaryOperator<OpenRouterEmbeddingOptions.Builder>) b -> b.dimensions(512)),
				argumentSet("encoding format", OpenRouterEmbeddingOptions.builder().encodingFormat("base64").build(),
						(UnaryOperator<OpenRouterEmbeddingOptions.Builder>) b -> b.encodingFormat("base64")),
				argumentSet("user", OpenRouterEmbeddingOptions.builder().user("request-user").build(),
						(UnaryOperator<OpenRouterEmbeddingOptions.Builder>) b -> b.user("request-user")),
				argumentSet("provider", OpenRouterEmbeddingOptions.builder().provider(OTHER_PROVIDER).build(),
						(UnaryOperator<OpenRouterEmbeddingOptions.Builder>) b -> b.provider(OTHER_PROVIDER)));
	}

	@ParameterizedTest
	@MethodSource("singleFieldOverrides")
	void mergeOverridesOnlyTheFieldTheRuntimeOptionsSet(OpenRouterEmbeddingOptions runtime,
			UnaryOperator<OpenRouterEmbeddingOptions.Builder> expectedChange) {
		OpenRouterEmbeddingOptions merged = defaults().build().merge(runtime);

		assertThat(merged).usingRecursiveComparison().isEqualTo(expectedChange.apply(defaults()).build());
	}

	@Test
	void mergeWithNothingOrEmptyRuntimeOptionsReturnsAnEqualCopyOfTheDefaults() {
		OpenRouterEmbeddingOptions base = defaults().build();

		OpenRouterEmbeddingOptions mergedWithNull = base.merge(null);
		OpenRouterEmbeddingOptions mergedWithEmpty = base.merge(OpenRouterEmbeddingOptions.builder().build());

		assertThat(mergedWithNull).isNotSameAs(base).usingRecursiveComparison().isEqualTo(base);
		assertThat(mergedWithEmpty).isNotSameAs(base).usingRecursiveComparison().isEqualTo(base);
	}

	@Test
	void mergeDoesNotChangeEitherInput() {
		OpenRouterEmbeddingOptions base = defaults().build();
		OpenRouterEmbeddingOptions runtime = OpenRouterEmbeddingOptions.builder().user("request-user").build();

		base.merge(runtime);

		assertThat(base).usingRecursiveComparison().isEqualTo(defaults().build());
		assertThat(runtime).usingRecursiveComparison()
			.isEqualTo(OpenRouterEmbeddingOptions.builder().user("request-user").build());
	}

	@Test
	void fromOptionsOfAForeignImplementationCarriesOnlyThePortableFields() {
		EmbeddingOptions portable = EmbeddingOptions.builder()
			.model("openai/text-embedding-3-large")
			.dimensions(512)
			.build();

		OpenRouterEmbeddingOptions options = OpenRouterEmbeddingOptions.fromOptions(portable);

		assertThat(options).usingRecursiveComparison()
			.isEqualTo(OpenRouterEmbeddingOptions.builder()
				.model("openai/text-embedding-3-large")
				.dimensions(512)
				.build());
	}

	@Test
	void fromOptionsOfAnOpenRouterInstanceCopiesEveryFieldIntoANewInstance() {
		OpenRouterEmbeddingOptions source = defaults().build();

		OpenRouterEmbeddingOptions copy = OpenRouterEmbeddingOptions.fromOptions(source);

		assertThat(copy).isNotSameAs(source).usingRecursiveComparison().isEqualTo(source);
		assertThat(OpenRouterEmbeddingOptions.fromOptions(null)).isNull();
	}

}
