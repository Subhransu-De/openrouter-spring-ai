package de.subhransu.openrouter.springai.chat.mapper;

import de.subhransu.openrouter.springai.api.dto.ContentPart;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.content.Media;

/**
 * Normalizes the two shapes allowed for assistant message content: a plain string or an
 * ordered array of typed content parts.
 *
 * @author Subhransu De
 */
final class AssistantContentMapper {

	private AssistantContentMapper() {
	}

	static MappedContent map(@Nullable Object content) {
		if (content == null) {
			return new MappedContent("", List.of());
		}
		if (content instanceof String text) {
			return new MappedContent(text, List.of());
		}
		if (!(content instanceof List<?> values)) {
			return new MappedContent(content.toString(), List.of());
		}

		List<ContentPart> parts = values.stream()
			.map(AssistantContentMapper::contentPart)
			.filter(Objects::nonNull)
			.toList();
		String text = parts.stream().map(ContentPart::text).filter(Objects::nonNull).collect(Collectors.joining());
		return new MappedContent(text, GeneratedImageMapper.media(parts));
	}

	private static @Nullable ContentPart contentPart(@Nullable Object value) {
		if (value instanceof ContentPart part) {
			return part;
		}
		if (value instanceof String text) {
			return ContentPart.text(text);
		}
		if (!(value instanceof Map<?, ?> attributes)) {
			return null;
		}
		return new ContentPart(stringValue(attributes.get("type")), stringValue(attributes.get("text")),
				imageUrl(attributes.get("image_url")));
	}

	private static ContentPart.@Nullable ImageUrl imageUrl(@Nullable Object value) {
		if (value instanceof ContentPart.ImageUrl imageUrl) {
			return imageUrl;
		}
		if (value instanceof String url) {
			return new ContentPart.ImageUrl(url);
		}
		if (value instanceof Map<?, ?> attributes) {
			String url = stringValue(attributes.get("url"));
			return url != null ? new ContentPart.ImageUrl(url) : null;
		}
		return null;
	}

	private static @Nullable String stringValue(@Nullable Object value) {
		return value instanceof String text ? text : null;
	}

	record MappedContent(String text, List<Media> media) {

		MappedContent {
			media = List.copyOf(media);
		}

	}

}
