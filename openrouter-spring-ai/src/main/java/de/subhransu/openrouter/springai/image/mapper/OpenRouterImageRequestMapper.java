package de.subhransu.openrouter.springai.image.mapper;

import org.jspecify.annotations.Nullable;
import de.subhransu.openrouter.springai.api.dto.ContentPart;
import de.subhransu.openrouter.springai.api.dto.ImagesRequest;
import de.subhransu.openrouter.springai.image.OpenRouterImageOptions;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.image.ImageMessage;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

public final class OpenRouterImageRequestMapper {

	public ImagesRequest map(ImagePrompt prompt, OpenRouterImageOptions options, boolean stream) {
		String model = options.getModel();
		Assert.hasText(model, "Image model must not be empty");
		return new ImagesRequest(model, promptText(prompt), options.getN(), size(options), options.getResolution(),
				options.getAspectRatio(), options.getQuality(), options.getOutputFormat(), options.getBackground(),
				options.getOutputCompression(), options.getSeed(), stream ? true : null, inputReferences(options),
				options.getProviderOptions());
	}

	// OpenRouter expects each reference as an image content object ({"type": "image_url",
	// "image_url": {"url": ...}}), the same shape as multimodal chat inputs; the options
	// surface stays a plain list of HTTP(S) or base64 data URLs.
	private @Nullable List<ContentPart> inputReferences(OpenRouterImageOptions options) {
		List<String> references = options.getInputReferences();
		if (references == null) {
			return null;
		}
		return references.stream().map(ContentPart::image).toList();
	}

	private String promptText(ImagePrompt prompt) {
		return prompt.getInstructions()
			.stream()
			.map(ImageMessage::getText)
			.filter(StringUtils::hasText)
			.collect(Collectors.joining("\n"));
	}

	// The portable width/height pair maps onto OpenRouter's explicit-pixels size
	// shorthand; half a dimension cannot be expressed, so reject it rather than
	// silently dropping it.
	private @Nullable String size(OpenRouterImageOptions options) {
		Integer width = options.getWidth();
		Integer height = options.getHeight();
		if (width == null && height == null) {
			return null;
		}
		if (width == null || height == null) {
			throw new IllegalArgumentException("Both width and height must be set to derive the image size (got width="
					+ width + ", height=" + height + ")");
		}
		return width + "x" + height;
	}

}
