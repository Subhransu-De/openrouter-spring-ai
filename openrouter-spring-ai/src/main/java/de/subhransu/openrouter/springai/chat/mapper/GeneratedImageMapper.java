package de.subhransu.openrouter.springai.chat.mapper;

import de.subhransu.openrouter.springai.api.dto.ContentPart;
import de.subhransu.openrouter.springai.api.dto.ResponsesOutputItem;
import java.util.List;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

/**
 * Maps the {@code images} array that image-generating chat models attach to assistant
 * messages (and streaming deltas) into Spring AI {@link Media}. Images arrive as
 * {@code image_url} parts whose url is normally a base64 data URL; the data URL string is
 * kept verbatim as the media data so callers can decode or pass it on unchanged.
 *
 * @author Subhransu De
 */
final class GeneratedImageMapper {

	private static final String DATA_URL_PREFIX = "data:";

	private GeneratedImageMapper() {
	}

	static List<Media> media(List<ContentPart> images) {
		if (images == null || images.isEmpty()) {
			return List.of();
		}
		return images.stream()
			.filter(part -> part != null && part.imageUrl() != null && part.imageUrl().url() != null)
			.map(part -> Media.builder().mimeType(mimeType(part.imageUrl().url())).data(part.imageUrl().url()).build())
			.toList();
	}

	// Use URL-backed Media in both modes so generated images can be reused as input.
	static List<Media> responsesMedia(List<ResponsesOutputItem> output) {
		if (output == null || output.isEmpty()) {
			return List.of();
		}
		return output.stream()
			.filter(item -> item != null && "image_generation_call".equals(item.type()) && item.result() != null)
			.map(GeneratedImageMapper::responseMedia)
			.toList();
	}

	private static Media responseMedia(ResponsesOutputItem item) {
		String result = item.result();
		String format = item.outputFormat() != null ? item.outputFormat() : "png";
		MimeType mimeType = result.startsWith(DATA_URL_PREFIX) ? mimeType(result)
				: MimeTypeUtils.parseMimeType("image/" + format);
		String url = result.startsWith(DATA_URL_PREFIX) || result.startsWith("https://") || result.startsWith("http://")
				? result : "data:" + mimeType + ";base64," + result;
		return Media.builder().mimeType(mimeType).data(url).build();
	}

	private static MimeType mimeType(String url) {
		int end = url.indexOf(';');
		if (!url.startsWith(DATA_URL_PREFIX) || end <= DATA_URL_PREFIX.length()) {
			// The docs describe generated images as "typically PNG"; plain URLs and
			// malformed data URLs carry no better signal.
			return MimeTypeUtils.IMAGE_PNG;
		}
		try {
			return MimeTypeUtils.parseMimeType(url.substring(DATA_URL_PREFIX.length(), end));
		}
		catch (RuntimeException ex) {
			return MimeTypeUtils.IMAGE_PNG;
		}
	}

}
