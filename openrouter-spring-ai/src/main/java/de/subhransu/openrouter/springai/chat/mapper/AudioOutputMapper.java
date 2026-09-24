package de.subhransu.openrouter.springai.chat.mapper;

import org.springframework.util.CollectionUtils;
import java.util.Objects;
import de.subhransu.openrouter.springai.api.dto.AudioOutput;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.chat.OpenRouterAudioOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.content.Media;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.util.Assert;
import org.springframework.util.MimeTypeUtils;

/**
 * Bounded audio assembly. Each wire data value encodes one independent byte fragment.
 *
 * @author OpenRouter Spring AI contributors
 */
final class AudioOutputMapper {

	static final String METADATA = "openrouter.audio";

	static final int MAX_BYTES = 16 * 1024 * 1024;

	private final @Nullable OpenRouterAudioOptions options;

	private final Map<Integer, Assembly> pending = new HashMap<>();

	private final Set<Integer> finished = new HashSet<>();

	private int retainedBytes;

	AudioOutputMapper(@Nullable OpenRouterAudioOptions options) {
		this.options = options;
	}

	static void validateRequest(List<Message> messages, OpenRouterChatOptions options, boolean stream,
			boolean responses) {
		var modalities = options.getModalities();
		boolean requested = modalities != null && modalities.contains("audio");
		if (requested || options.getAudio() != null) {
			Assert.isTrue(stream && !responses, "Audio output requires streaming Chat Completions");
			Assert.isTrue(requested && options.getAudio() != null,
					"Audio output requires both audio options and the audio modality");
		}
		for (Message message : messages) {
			if (message instanceof AssistantMessage assistant) {
				Assert.isTrue(
						!assistant.getMetadata().containsKey(METADATA) && assistant.getMedia()
							.stream()
							.noneMatch(media -> "audio".equals(media.getMimeType().getType())),
						"Assistant audio replay is unsupported; create a text-only message explicitly to continue with the transcript");
			}
		}
	}

	synchronized boolean finished(Choice choice) {
		return this.finished.contains(index(choice));
	}

	synchronized void append(Choice choice, Map<String, Object> metadata, List<Media> media) {
		var message = choice.message();
		var delta = choice.delta();
		AudioOutput audio = delta != null ? delta.audio() : null;
		int index = index(choice);
		if (message != null && message.extensions().containsKey("audio")) {
			throw new IllegalArgumentException(
					"Audio output requires delta.audio fragments, not message.audio snapshots");
		}
		Assembly assembly = this.pending.get(index);
		if (audio != null) {
			Assert.state(this.options != null, "Received audio without configured output audio options");
			if (assembly == null) {
				if (this.pending.size() + this.finished.size() >= 128) {
					throw new NonTransientAiException("Audio output exceeds the 128-choice limit");
				}
				assembly = new Assembly();
				this.pending.put(index, assembly);
			}
			append(assembly, audio, this.options);
		}
		if (assembly == null) {
			return;
		}
		Assert.state(delta == null || CollectionUtils.isEmpty(delta.toolCalls()),
				"Audio and tool calls in the same choice are unsupported");
		if (choice.finishReason() != null) {
			finish(choice, assembly, index, metadata, media);
		}
	}

	private void finish(Choice choice, Assembly assembly, int index, Map<String, Object> metadata, List<Media> media) {
		Assert.state(this.options != null, "Received audio without configured output audio options");
		byte[] completedAudio = assembly.bytes.toByteArray();
		if (!"stop".equals(choice.finishReason()) || completedAudio.length == 0) {
			throw new OpenRouterTruncatedResponseException(
					"Audio choice ended without complete audio and a stop finish reason");
		}
		Map<String, Object> snapshot = new HashMap<>();
		snapshot.put("format", this.options.format());
		snapshot.put("transcript", assembly.transcript.toString());
		if (assembly.id != null) {
			snapshot.put("id", assembly.id);
		}
		if (assembly.expiresAt != null) {
			snapshot.put("expires_at", assembly.expiresAt);
		}
		metadata.put(METADATA, Map.copyOf(snapshot));
		media.add(Media.builder()
			.mimeType(MimeTypeUtils.parseMimeType(mimeType(this.options.format())))
			.data(completedAudio)
			.build());
		this.retainedBytes -= assembly.retainedBytes;
		this.pending.remove(index);
		this.finished.add(index);
	}

	private void append(Assembly assembly, AudioOutput audio, OpenRouterAudioOptions options) {
		var data = audio.data();
		var transcript = audio.transcript();
		var id = audio.id();
		Assert.state(audio.format() == null || options.format().equals(audio.format()),
				"Conflicting audio output format");
		Assert.state(id == null || assembly.id == null || assembly.id.equals(id),
				"Conflicting audio identifiers in one choice");
		if (id != null) {
			if (assembly.id == null) {
				retain(assembly, id.length() * 2L);
			}
			assembly.id = id;
		}
		if (audio.expiresAt() != null) {
			assembly.expiresAt = audio.expiresAt();
		}
		if (transcript != null) {
			retain(assembly, transcript.length() * 2L);
			assembly.transcript.append(transcript);
		}
		if (data != null && !data.isEmpty()) {
			// Bound allocation before decoding. Padding can reduce the result by at most
			// two bytes.
			long upperBound = (data.length() + 3L) / 4 * 3;
			if (upperBound > MAX_BYTES - this.retainedBytes + 2L) {
				throw limit();
			}
			byte[] bytes = Base64.getDecoder().decode(data);
			retain(assembly, bytes.length);
			assembly.bytes.writeBytes(bytes);
		}
	}

	private void retain(Assembly assembly, long bytes) {
		if (bytes > MAX_BYTES - this.retainedBytes) {
			throw limit();
		}
		this.retainedBytes += (int) bytes;
		assembly.retainedBytes += (int) bytes;
	}

	private static NonTransientAiException limit() {
		return new NonTransientAiException("Retained audio and transcript exceed the 16 MiB stream limit");
	}

	synchronized void complete() {
		if (!this.pending.isEmpty()) {
			throw new OpenRouterTruncatedResponseException("Stream ended with unfinished audio choices");
		}
	}

	synchronized void clear() {
		this.pending.clear();
		this.finished.clear();
		this.retainedBytes = 0;
	}

	private static int index(Choice choice) {
		return Objects.requireNonNullElse(choice.index(), 0);
	}

	private static String mimeType(String format) {
		return switch (format) {
			case "mp3" -> "audio/mpeg";
			case "pcm16" -> "audio/pcm";
			case "opus" -> "audio/ogg;codecs=opus";
			default -> "audio/" + format;
		};
	}

	private static final class Assembly {

		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		private final StringBuilder transcript = new StringBuilder();

		private @Nullable String id;

		private @Nullable Long expiresAt;

		private int retainedBytes;

	}

}
