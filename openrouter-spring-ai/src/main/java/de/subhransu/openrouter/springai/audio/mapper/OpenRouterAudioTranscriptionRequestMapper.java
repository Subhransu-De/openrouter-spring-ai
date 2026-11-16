package de.subhransu.openrouter.springai.audio.mapper;

import de.subhransu.openrouter.springai.api.dto.TranscriptionRequest;
import de.subhransu.openrouter.springai.api.dto.TranscriptionRequest.InputAudio;
import de.subhransu.openrouter.springai.audio.OpenRouterAudioTranscriptionModel;
import de.subhransu.openrouter.springai.audio.OpenRouterAudioTranscriptionOptions;
import de.subhransu.openrouter.springai.audio.OpenRouterTimestampGranularity;
import de.subhransu.openrouter.springai.audio.OpenRouterTranscriptionFormat;
import de.subhransu.openrouter.springai.chat.mapper.TraceMapper;
import de.subhransu.openrouter.springai.support.OptionChecks;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * Maps an audio resource and merged options to the JSON transcription request. Inline
 * audio is read up to the size limit and sent as base64.
 *
 * @author Subhransu De
 */
public final class OpenRouterAudioTranscriptionRequestMapper {

	private static final String ENDPOINT = "/audio/transcriptions";

	private static final int MAX_URL_LENGTH = 8000;

	// URI schemes are ASCII and case-insensitive; without UNICODE_CASE the flag folds
	// ASCII
	// only, so no locale-dependent case mapping is involved.
	private static final Pattern HTTP_SCHEME = Pattern.compile("https?", Pattern.CASE_INSENSITIVE);

	private final int maxInlineAudioBytes;

	public OpenRouterAudioTranscriptionRequestMapper(int maxInlineAudioBytes) {
		Assert.isTrue(maxInlineAudioBytes > 0, "Maximum inline audio size must be greater than zero");
		Assert.isTrue(maxInlineAudioBytes <= OpenRouterAudioTranscriptionModel.MAX_INLINE_AUDIO_BYTES,
				() -> "Maximum inline audio size must be at most "
						+ OpenRouterAudioTranscriptionModel.MAX_INLINE_AUDIO_BYTES + " bytes, but was "
						+ maxInlineAudioBytes);
		this.maxInlineAudioBytes = maxInlineAudioBytes;
	}

	public TranscriptionRequest map(Resource audio, OpenRouterAudioTranscriptionOptions options) {
		Assert.notNull(audio, "Audio resource must not be null");
		String model = options.getModel();
		// OpenRouter has no default transcription model; fail before reading the audio.
		Assert.hasText(model, "Transcription model must not be empty");
		checkVerboseOnlyOptions(options);
		InputAudio input = Boolean.TRUE.equals(options.getSendAudioUrl()) ? remote(audio, options.getAudioFormat())
				: inline(audio, options.getAudioFormat());
		OpenRouterTranscriptionFormat responseFormat = options.getResponseFormat();
		List<OpenRouterTimestampGranularity> granularities = options.getTimestampGranularities();
		return new TranscriptionRequest(model, input, options.getLanguage(), options.getTemperature(),
				responseFormat != null ? responseFormat.value() : null,
				granularities != null ? granularities.stream().map(OpenRouterTimestampGranularity::value).toList()
						: null,
				options.getDiarize(), options.getKeyterms(), AudioProviderMapper.map(options.getProvider(), ENDPOINT),
				options.getUser(), options.getSessionId(), TraceMapper.map(options.getTrace()));
	}

	// The API reference rejects diarize with json (HTTP 400) and ignores timestamp
	// granularities without verbose_json; reject both here so neither is lost silently.
	private static void checkVerboseOnlyOptions(OpenRouterAudioTranscriptionOptions options) {
		OpenRouterTranscriptionFormat responseFormat = options.getResponseFormat();
		if (responseFormat == OpenRouterTranscriptionFormat.VERBOSE_JSON) {
			return;
		}
		String format = responseFormat != null ? responseFormat.value() : "json (default)";
		if (Boolean.TRUE.equals(options.getDiarize())) {
			throw new IllegalArgumentException(
					"diarize requires responseFormat VERBOSE_JSON, but the response format is " + format);
		}
		if (!CollectionUtils.isEmpty(options.getTimestampGranularities())) {
			throw new IllegalArgumentException(
					"timestampGranularities requires responseFormat VERBOSE_JSON, but the response format is "
							+ format);
		}
	}

	private InputAudio inline(Resource audio, @Nullable String audioFormat) {
		String format = audioFormat != null ? audioFormat : extension(audio.getFilename());
		if (format == null) {
			throw new IllegalArgumentException("Set audioFormat: the audio format cannot be derived from "
					+ audio.getDescription() + " because its filename has no extension");
		}
		// Checked before the resource is read, so a bad extension costs no upload.
		OptionChecks.audioFormat("The audio format", format);
		return InputAudio.inline(Base64.getEncoder().encodeToString(read(audio)), format);
	}

	private byte[] read(Resource audio) {
		try (InputStream input = audio.getInputStream()) {
			// Read one byte past the limit so an oversized resource is never read in
			// full.
			byte[] bytes = input.readNBytes(this.maxInlineAudioBytes + 1);
			if (bytes.length > this.maxInlineAudioBytes) {
				throw new IllegalArgumentException(audio.getDescription() + " is larger than the inline audio limit of "
						+ this.maxInlineAudioBytes + " bytes; split the audio, raise the limit for providers that "
						+ "accept more, or send an HTTP(S) resource with sendAudioUrl");
			}
			if (bytes.length == 0) {
				throw new IllegalArgumentException(audio.getDescription() + " is empty");
			}
			return bytes;
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Failed to read " + audio.getDescription(), ex);
		}
	}

	private static InputAudio remote(Resource audio, @Nullable String audioFormat) {
		URI uri;
		try {
			uri = audio.getURI();
		}
		catch (IOException ex) {
			throw new IllegalArgumentException(
					"sendAudioUrl requires an HTTP(S) resource, but " + audio.getDescription() + " has no URL", ex);
		}
		String scheme = uri.getScheme();
		if (scheme == null || !HTTP_SCHEME.matcher(scheme).matches()) {
			throw new IllegalArgumentException("sendAudioUrl requires an HTTP(S) resource, but the URL scheme was "
					+ OptionChecks.describe(scheme));
		}
		String url = uri.toString();
		if (url.length() > MAX_URL_LENGTH) {
			throw new IllegalArgumentException(
					"The audio URL must be at most " + MAX_URL_LENGTH + " characters, but was " + url.length());
		}
		String format = audioFormat != null ? audioFormat : extension(uri.getPath());
		if (format == null) {
			throw new IllegalArgumentException(
					"Set audioFormat: the audio URL path has no extension, so OpenRouter cannot tell its format");
		}
		OptionChecks.audioFormat("The audio format", format);
		return InputAudio.remote(url, format);
	}

	private static @Nullable String extension(@Nullable String path) {
		String extension = StringUtils.getFilenameExtension(path);
		return StringUtils.hasText(extension) ? extension.toLowerCase(Locale.ROOT) : null;
	}

}
