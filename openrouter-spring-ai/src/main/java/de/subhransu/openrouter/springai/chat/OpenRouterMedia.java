package de.subhransu.openrouter.springai.chat;

import java.util.Base64;
import org.springframework.ai.content.Media;

/**
 * Reads the bytes of media that a chat model generated. Generated images arrive as a
 * base64 data URL string, and output audio as raw bytes.
 *
 * @author Subhransu De
 */
public final class OpenRouterMedia {

	private static final String DATA_URL_PREFIX = "data:";

	private OpenRouterMedia() {
	}

	/**
	 * Return the bytes of generated media.
	 * @param media media from an assistant message
	 * @return the raw bytes, or the decoded content of a base64 data URL
	 * @throws IllegalArgumentException when the media holds a remote URL, which this
	 * method does not fetch, or a data URL that is not valid base64
	 */
	public static byte[] bytes(Media media) {
		Object data = media.getData();
		if (data instanceof byte[] bytes) {
			return bytes;
		}
		if (data instanceof String text && text.startsWith(DATA_URL_PREFIX)) {
			int comma = text.indexOf(',');
			if (comma < 0 || !text.substring(0, comma).endsWith(";base64")) {
				throw new IllegalArgumentException(
						"Media data URL must be base64-encoded (data:<type>;base64,<data>); got a data URL starting with '"
								+ text.substring(0, Math.min(comma < 0 ? text.length() : comma, 40)) + "'");
			}
			try {
				return Base64.getDecoder().decode(text.substring(comma + 1));
			}
			catch (IllegalArgumentException ex) {
				throw new IllegalArgumentException("Media data URL holds invalid base64: " + ex.getMessage(), ex);
			}
		}
		throw new IllegalArgumentException("Media data must be bytes or a base64 data URL; got " + describe(data)
				+ ". Fetch a remote URL from media.getData() yourself");
	}

	private static String describe(Object data) {
		if (data instanceof String text) {
			int colon = text.indexOf(':');
			return colon > 0 ? "a '" + text.substring(0, colon) + "' URL" : "a string that is not a URL";
		}
		return "a " + data.getClass().getSimpleName();
	}

}
