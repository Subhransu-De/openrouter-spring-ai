package de.subhransu.openrouter.springai.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingOptions;

@Tag("embeddings")
@Tag("embeddings.options")
class OpenRouterEmbeddingOptionsTests {

	private static final String MODEL = "openai/text-embedding-3-small";

	@Test
	void mergePrefersRuntimeValuesAndKeepsDefaultsForUnsetFields() {
		OpenRouterEmbeddingOptions defaults = OpenRouterEmbeddingOptions.builder()
			.model(MODEL)
			.dimensions(256)
			.user("default-user")
			.build();

		OpenRouterEmbeddingOptions merged = defaults
			.merge(OpenRouterEmbeddingOptions.builder().model("qwen/qwen3-embedding-8b").build());

		assertThat(merged.getModel()).isEqualTo("qwen/qwen3-embedding-8b");
		assertThat(merged.getDimensions()).isEqualTo(256);
		assertThat(merged.getUser()).isEqualTo("default-user");
	}

	@Test
	void copyIsIndependentOfTheSource() {
		OpenRouterEmbeddingOptions source = OpenRouterEmbeddingOptions.builder().model(MODEL).build();

		OpenRouterEmbeddingOptions changed = source.mutate().model("changed").build();

		assertThat(source.getModel()).isEqualTo(MODEL);
		assertThat(changed.getModel()).isEqualTo("changed");
	}

	@Test
	void fromOptionsCarriesPortableFieldsFromForeignImplementations() {
		EmbeddingOptions portable = EmbeddingOptions.builder()
			.model("openai/text-embedding-3-large")
			.dimensions(512)
			.build();

		OpenRouterEmbeddingOptions options = OpenRouterEmbeddingOptions.fromOptions(portable);

		assertThat(options.getModel()).isEqualTo("openai/text-embedding-3-large");
		assertThat(options.getDimensions()).isEqualTo(512);
	}

	@Test
	void fromOptionsCopiesOpenRouterSpecificFields() {
		OpenRouterProviderPreferences provider = new OpenRouterProviderPreferences(true, null, "deny",
				List.of("openai"), null, null, null);
		OpenRouterEmbeddingOptions source = OpenRouterEmbeddingOptions.builder()
			.model(MODEL)
			.encodingFormat("float")
			.provider(provider)
			.build();

		OpenRouterEmbeddingOptions options = OpenRouterEmbeddingOptions.fromOptions(source);

		assertThat(options.getEncodingFormat()).isEqualTo("float");
		assertThat(options.getProvider()).isEqualTo(provider);
	}

}
