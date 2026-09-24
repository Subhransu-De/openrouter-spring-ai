package de.subhransu.openrouter.springai.image.mapper;

import de.subhransu.openrouter.springai.api.dto.ImagesResponse;
import de.subhransu.openrouter.springai.api.OpenRouterImageResponseValidator;
import de.subhransu.openrouter.springai.api.dto.ImagesStreamEvent;
import de.subhransu.openrouter.springai.api.dto.Usage;
import de.subhransu.openrouter.springai.api.errors.OpenRouterApiExceptionFactory;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import de.subhransu.openrouter.springai.chat.mapper.UsageMapper;
import de.subhransu.openrouter.springai.image.OpenRouterImageGenerationMetadata;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.image.Image;
import org.springframework.ai.image.ImageGeneration;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.image.ImageResponseMetadata;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

public final class OpenRouterImageResponseMapper {

	public ImageResponse map(ImagesResponse response) {
		OpenRouterImageResponseValidator.validate(response);
		var images = response.data();
		List<ImageGeneration> generations = CollectionUtils.isEmpty(images) ? List.of()
				: images.stream()
					.map(Objects::requireNonNull)
					.map(data -> new ImageGeneration(new Image(data.url(), data.b64Json()),
							new OpenRouterImageGenerationMetadata(data.mediaType(), null)))
					.toList();
		return new ImageResponse(generations, metadata(response.created(), response.usage()));
	}

	/**
	 * Map a supported image event, returning {@code null} for unknown additive types.
	 * @param event the decoded image stream event
	 * @return the image response, or {@code null} for an ignored event
	 */
	public @Nullable ImageResponse map(ImagesStreamEvent event) {
		var error = event.error();
		if (error != null || ImagesStreamEvent.ERROR_EVENT.equals(event.type())) {
			throw OpenRouterApiExceptionFactory.create("OpenRouter image generation stream failed",
					error != null ? error.toString() : null, error, null);
		}
		if (!StringUtils.hasText(event.type())) {
			throw new OpenRouterProtocolException("OpenRouter image event requires a type");
		}
		// Ignore additive lifecycle/metadata events without manufacturing an image.
		if (!ImagesStreamEvent.PARTIAL_IMAGE.equals(event.type())
				&& !ImagesStreamEvent.COMPLETED.equals(event.type())) {
			return null;
		}
		if (ImagesStreamEvent.COMPLETED.equals(event.type()) && !StringUtils.hasText(event.b64Json())
				&& !StringUtils.hasText(event.url())) {
			throw new OpenRouterProtocolException("Completed OpenRouter image event requires image content");
		}
		ImageGeneration generation = new ImageGeneration(new Image(event.url(), event.b64Json()),
				new OpenRouterImageGenerationMetadata(event.mediaType(), event.partialImageIndex()));
		ImageResponseMetadata metadata = metadata(event.created(), event.usage());
		if (event.type() != null) {
			metadata.put("openrouter.event_type", event.type());
		}
		return new ImageResponse(List.of(generation), metadata);
	}

	private ImageResponseMetadata metadata(@Nullable Long created, @Nullable Usage usage) {
		ImageResponseMetadata metadata = created != null ? new ImageResponseMetadata(created)
				: new ImageResponseMetadata();
		if (usage != null) {
			metadata.put("openrouter.usage", UsageMapper.map(usage));
		}
		return metadata;
	}

}
