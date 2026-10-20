package de.subhransu.openrouter.springai.chat.mapper;

import java.util.Base64;
import java.nio.charset.StandardCharsets;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeType;

/**
 * Derives the OpenRouter {@code image_url} value from Spring AI {@link Media}: URI-backed
 * media pass through as plain URLs, byte-backed media become {@code data:} base64 URLs.
 *
 * @author Subhransu De
 */
final class MediaUrlMapper {

	private MediaUrlMapper() {
	}

	static String imageUrl(Media media) {
		MimeType mimeType = media.getMimeType();
		if (mimeType == null || !"image".equals(mimeType.getType())) {
			throw new IllegalArgumentException(
					"Unsupported media mime type '" + mimeType + "': this content position accepts only image media");
		}
		Object data = media.getData();
		if (data instanceof String url) {
			return url;
		}
		if (data instanceof java.net.URI || data instanceof java.net.URL) {
			return data.toString();
		}
		return dataUrl("data:" + mimeType + ";base64,", media.getDataAsByteArray());
	}

	static String dataUrl(String prefix, byte[] data) {
		long encodedLength = 4L * ((data.length + 2L) / 3);
		if (encodedLength > Integer.MAX_VALUE - prefix.length()) {
			return prefix + Base64.getEncoder().encodeToString(data);
		}
		for (int i = 0; i < prefix.length(); i++) {
			if (prefix.charAt(i) > 255) {
				return prefix + Base64.getEncoder().encodeToString(data);
			}
		}
		byte[] encoded = new byte[(int) encodedLength + prefix.length()];
		int length = Base64.getEncoder().encode(data, encoded);
		System.arraycopy(encoded, 0, encoded, prefix.length(), length);
		for (int i = 0; i < prefix.length(); i++) {
			encoded[i] = (byte) prefix.charAt(i);
		}
		return new String(encoded, StandardCharsets.ISO_8859_1);
	}

}
