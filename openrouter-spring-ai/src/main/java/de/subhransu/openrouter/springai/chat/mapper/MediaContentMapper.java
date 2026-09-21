package de.subhransu.openrouter.springai.chat.mapper;

import de.subhransu.openrouter.springai.api.dto.ContentPart;
import de.subhransu.openrouter.springai.api.dto.ResponsesContent;
import java.net.URI;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;
import org.springframework.ai.content.Media;

/**
 * Maps user attachments without fetching URLs or modifying caller-owned media.
 *
 * @author Subhransu De
 */
final class MediaContentMapper {

	static final int MAX_INLINE_BYTES = 20 * 1024 * 1024;

	private static final Set<String> VIDEO_TYPES = Set.of("video/mp4", "video/mpeg", "video/mov", "video/webm");

	private MediaContentMapper() {
	}

	static ContentPart chat(Media media) {
		String mime = media.getMimeType().toString();
		if ("image".equals(media.getMimeType().getType())) {
			return ContentPart.image(MediaUrlMapper.imageUrl(media));
		}
		if ("application/pdf".equals(mime)) {
			return new ContentPart("file", null, null, null,
					new ContentPart.FileInput(media.getName(), dataUrl(media, false)), null, null);
		}
		if ("audio/wav".equals(mime) || "audio/mpeg".equals(mime)) {
			String data = dataUrl(media, true);
			return new ContentPart("input_audio", null, null, null, null, new ContentPart.AudioInput(
					data.substring(data.indexOf(',') + 1), "audio/wav".equals(mime) ? "wav" : "mp3"), null);
		}
		if (VIDEO_TYPES.contains(mime)) {
			return new ContentPart("video_url", null, null, null, null, null,
					new ContentPart.VideoUrl(dataUrl(media, false)));
		}
		throw new IllegalArgumentException("Unsupported input media MIME type: " + mime);
	}

	static ResponsesContent responses(Media media) {
		ContentPart part = chat(media);
		return switch (Objects.requireNonNull(part.type())) {
			case "image_url" ->
				new ResponsesContent("input_image", null, Objects.requireNonNull(part.imageUrl()).url());
			case "file" -> {
				ContentPart.FileInput file = Objects.requireNonNull(part.file());
				String data = Objects.requireNonNull(file.fileData());
				boolean inline = data.startsWith("data:");
				yield new ResponsesContent("input_file", null, null, null, file.filename(), inline ? data : null,
						inline ? null : data, null, null);
			}
			case "input_audio" ->
				new ResponsesContent("input_audio", null, null, null, null, null, null, part.inputAudio(), null);
			case "video_url" -> new ResponsesContent("input_video", null, null, null, null, null, null, null,
					Objects.requireNonNull(part.videoUrl()).url());
			default -> throw new IllegalArgumentException("Unsupported input media content type");
		};
	}

	private static String dataUrl(Media media, boolean audio) {
		String prefix = "data:" + media.getMimeType() + ";base64,";
		Object data = media.getData();
		if (data instanceof byte[] bytes) {
			checkSize(bytes.length);
			return prefix + Base64.getEncoder().encodeToString(bytes);
		}
		String value = data.toString();
		if (value.startsWith("data:")) {
			if (!value.startsWith(prefix)) {
				throw new IllegalArgumentException(
						"Media data URL must use its declared MIME type and base64 encoding");
			}
			int length = value.length() - prefix.length();
			if (length > 4 * ((MAX_INLINE_BYTES + 2) / 3)) {
				throw new IllegalArgumentException("Inline media exceeds 20 MiB");
			}
			byte[] decoded;
			try {
				decoded = Base64.getDecoder().decode(value.substring(prefix.length()));
			}
			catch (IllegalArgumentException ex) {
				throw new IllegalArgumentException("Invalid base64 media content");
			}
			checkSize(decoded.length);
			return value;
		}
		if (audio) {
			throw new IllegalArgumentException(
					"Audio input requires bytes or a base64 data URL; audio URLs are unsupported");
		}
		URI uri = URI.create(value);
		if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
				|| uri.getHost() == null) {
			throw new IllegalArgumentException("Media URL must be an absolute HTTP(S) URL");
		}
		return value;
	}

	private static void checkSize(int size) {
		if (size == 0 || size > MAX_INLINE_BYTES) {
			throw new IllegalArgumentException("Inline media must contain between 1 byte and 20 MiB");
		}
	}

}
