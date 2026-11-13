package de.subhransu.openrouter.springai.image;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.dto.ImagesResponse;
import de.subhransu.openrouter.springai.api.dto.ImagesStreamEvent;
import de.subhransu.openrouter.springai.image.mapper.OpenRouterImageResponseMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.image.Image;
import org.springframework.ai.image.ImageGeneration;
import tools.jackson.databind.ObjectMapper;

@Tag("multimodal")
@Tag("multimodal.image-model")
class OpenRouterImageGenerationMetadataTests {

	private final ObjectMapper json = new ObjectMapper();

	private final OpenRouterImageResponseMapper mapper = new OpenRouterImageResponseMapper();

	@Test
	void readsCallMetadataWithoutACast() {
		ImageGeneration generation = this.mapper.map(this.json.readValue("""
				{"data":[{"b64_json":"aW1n","media_type":"image/webp"}]}""", ImagesResponse.class)).getResult();

		OpenRouterImageGenerationMetadata metadata = OpenRouterImageGenerationMetadata.of(generation);

		assertThat(metadata.mediaType()).isEqualTo("image/webp");
		assertThat(metadata.isPartial()).isFalse();
	}

	@Test
	void tellsStreamedPreviewsFromCompletedImages() {
		ImageGeneration preview = this.mapper.map(this.json.readValue("""
				{"type":"image_generation.partial_image","partial_image_index":0,"b64_json":"aW1n"}""",
				ImagesStreamEvent.class))
			.getResult();
		ImageGeneration completed = this.mapper.map(this.json.readValue("""
				{"type":"image_generation.completed","b64_json":"aW1n","media_type":"image/png"}""",
				ImagesStreamEvent.class))
			.getResult();

		assertThat(OpenRouterImageGenerationMetadata.of(preview).isPartial()).isTrue();
		assertThat(OpenRouterImageGenerationMetadata.of(preview).partialImageIndex()).isZero();
		assertThat(OpenRouterImageGenerationMetadata.of(completed).isPartial()).isFalse();
	}

	@Test
	void foreignOrMissingMetadataReadsAsEmpty() {
		OpenRouterImageGenerationMetadata metadata = OpenRouterImageGenerationMetadata
			.of(new ImageGeneration(new Image(null, "aW1n")));

		assertThat(metadata.mediaType()).isNull();
		assertThat(metadata.isPartial()).isFalse();
	}

}
