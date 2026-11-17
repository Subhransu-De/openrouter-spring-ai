package de.subhransu.openrouter.springai.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;

/**
 * The log probabilities of a Chat Completions choice, requested with
 * {@link OpenRouterChatOptions.Builder#logprobs(Boolean)}. They are read from the
 * {@code logprobs} entry of {@link OpenRouterMetadata#CHOICE_EXTENSIONS}, which keeps the
 * provider's raw form.
 *
 * @param content the answer tokens, in output order
 * @param refusal the refusal tokens, in output order
 * @author Subhransu De
 */
public record OpenRouterLogprobs(List<Token> content, List<Token> refusal) {

	private static final int MAX_BYTE = 255;

	public OpenRouterLogprobs {
		content = List.copyOf(content);
		refusal = List.copyOf(refusal);
	}

	/**
	 * Read the log probabilities from an assistant message. In a stream, each message
	 * holds the tokens so far. Entries without a token string or a numeric log
	 * probability are left out.
	 * @param message the assistant message to read
	 * @return the log probabilities, or empty when the provider sent neither content nor
	 * refusal tokens
	 */
	public static Optional<OpenRouterLogprobs> from(AssistantMessage message) {
		if (!(message.getMetadata().get(OpenRouterMetadata.CHOICE_EXTENSIONS) instanceof Map<?, ?> choice)
				|| !(choice.get("logprobs") instanceof Map<?, ?> logprobs)
				|| !(logprobs.get("content") instanceof List<?>) && !(logprobs.get("refusal") instanceof List<?>)) {
			return Optional.empty();
		}
		return Optional.of(new OpenRouterLogprobs(tokens(logprobs.get("content")), tokens(logprobs.get("refusal"))));
	}

	private static List<Token> tokens(@Nullable Object value) {
		List<Token> tokens = new ArrayList<>();
		if (value instanceof List<?> entries) {
			for (Object entry : entries) {
				if (entry instanceof Map<?, ?> fields && fields.get("token") instanceof String token
						&& fields.get("logprob") instanceof Number logprob) {
					tokens.add(new Token(token, logprob.doubleValue(), bytes(fields.get("bytes")),
							alternatives(fields.get("top_logprobs"))));
				}
			}
		}
		return tokens;
	}

	private static List<Alternative> alternatives(@Nullable Object value) {
		List<Alternative> alternatives = new ArrayList<>();
		if (value instanceof List<?> entries) {
			for (Object entry : entries) {
				if (entry instanceof Map<?, ?> fields && fields.get("token") instanceof String token
						&& fields.get("logprob") instanceof Number logprob) {
					alternatives.add(new Alternative(token, logprob.doubleValue(), bytes(fields.get("bytes"))));
				}
			}
		}
		return alternatives;
	}

	private static @Nullable List<Integer> bytes(@Nullable Object value) {
		if (!(value instanceof List<?> entries)) {
			return null;
		}
		List<Integer> bytes = new ArrayList<>(entries.size());
		for (Object entry : entries) {
			// A fractional or out-of-range value would become a made-up byte.
			if (!(entry instanceof Number number) || number.doubleValue() != number.intValue() || number.intValue() < 0
					|| number.intValue() > MAX_BYTE) {
				return null;
			}
			bytes.add(number.intValue());
		}
		return bytes;
	}

	/**
	 * One output token.
	 *
	 * @param token the token text
	 * @param logprob the natural-log probability of the token
	 * @param bytes the UTF-8 bytes of the token, or {@code null} when not sent
	 * @param topLogprobs the most likely alternatives at this position, empty unless
	 * {@code topLogprobs} was requested
	 */
	public record Token(String token, double logprob, @Nullable List<Integer> bytes, List<Alternative> topLogprobs) {

		public Token {
			bytes = bytes == null ? null : List.copyOf(bytes);
			topLogprobs = List.copyOf(topLogprobs);
		}

	}

	/**
	 * One alternative token at an output position.
	 *
	 * @param token the token text
	 * @param logprob the natural-log probability of the token
	 * @param bytes the UTF-8 bytes of the token, or {@code null} when not sent
	 */
	public record Alternative(String token, double logprob, @Nullable List<Integer> bytes) {

		public Alternative {
			bytes = bytes == null ? null : List.copyOf(bytes);
		}

	}

}
