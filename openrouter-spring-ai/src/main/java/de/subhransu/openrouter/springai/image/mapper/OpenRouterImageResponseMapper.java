package de.subhransu.openrouter.springai.image.mapper;

import de.subhransu.openrouter.springai.api.dto.ImagesResponse;
import de.subhransu.openrouter.springai.api.dto.ImagesStreamEvent;
import de.subhransu.openrouter.springai.api.dto.Usage;
import de.subhransu.openrouter.springai.api.errors.OpenRouterApiExceptionFactory;
import de.subhransu.openrouter.springai.chat.mapper.UsageMapper;
import de.subhransu.openrouter.springai.image.OpenRouterImageGenerationMetadata;
import java.util.List;
import org.springframework.ai.image.Image;
import org.springframework.ai.image.ImageGeneration;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.image.ImageResponseMetadata;
import org.springframework.util.CollectionUtils;

public final class OpenRouterImageResponseMapper {

	public ImageResponse map(ImagesResponse response) {
		List<ImageGeneration> generations = CollectionUtils.isEmpty(response.data()) ? List.of()
				: response.data()
					.stream()
					.map(data -> new ImageGeneration(new Image(data.url(), data.b64Json()),
							new OpenRouterImageGenerationMetadata(data.mediaType(), null)))
					.toList();
		return new ImageResponse(generations, metadata(response.created(), response.usage()));
	}

	public ImageResponse map(ImagesStreamEvent event) {
		if (ImagesStreamEvent.ERROR_EVENT.equals(event.type())) {
			throw OpenRouterApiExceptionFactory.create("OpenRouter image generation stream failed",
					event.error() != null ? event.error().toString() : null, event.error(), null);
		}
		ImageGeneration generation = new ImageGeneration(new Image(null, event.b64Json()),
				new OpenRouterImageGenerationMetadata(event.mediaType(), event.partialImageIndex()));
		ImageResponseMetadata metadata = metadata(event.created(), event.usage());
		metadata.put("openrouter.event_type", event.type());
		return new ImageResponse(List.of(generation), metadata);
	}

	private ImageResponseMetadata metadata(Long created, Usage usage) {
		ImageResponseMetadata metadata = created != null ? new ImageResponseMetadata(created)
				: new ImageResponseMetadata();
		if (usage != null) {
			metadata.put("openrouter.usage", UsageMapper.map(usage));
		}
		return metadata;
	}

}
