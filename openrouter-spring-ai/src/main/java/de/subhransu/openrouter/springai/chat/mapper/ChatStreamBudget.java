package de.subhransu.openrouter.springai.chat.mapper;

import de.subhransu.openrouter.springai.errors.OpenRouterLimitExceededException;
import de.subhransu.openrouter.springai.errors.OpenRouterLimitExceededException.Limit;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

// Conservative metadata admission charges are released when a choice finishes.
final class ChatStreamBudget {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final long maxBytes;

	private final int maxChoices;

	private final Map<Integer, Long> choices = new HashMap<>();

	private long bytes;

	ChatStreamBudget(long maxBytes, int maxChoices) {
		this.maxBytes = maxBytes;
		this.maxChoices = maxChoices;
	}

	void open(int index) {
		if (!this.choices.containsKey(index)) {
			if (this.choices.size() >= this.maxChoices) {
				throw limit(Limit.CHAT_STATE_CHOICES, this.maxChoices, this.choices.size() + 1L);
			}
			// Reserve the bounded diagnostic excerpt, including supplementary text.
			long reservation = 4L * OpenRouterChoiceErrorExceptionFactory.MAX_DIAGNOSTIC_LENGTH;
			retain(reservation);
			this.choices.put(index, reservation);
		}
	}

	void append(int index, Map<String, Object> metadata) {
		long charge = size(metadata);
		retain(charge);
		this.choices.compute(index, (key, previous) -> (previous != null ? previous : 0L) + charge);
	}

	void appendResponse(Map<String, ?> extensions) {
		retain(size(extensions));
	}

	void finish(int index) {
		Long released = this.choices.remove(index);
		if (released != null) {
			this.bytes -= released;
		}
	}

	private long size(Map<String, ?> value) {
		if (value.isEmpty()) {
			return 0;
		}
		Counter counter = new Counter();
		this.objectMapper.writeValue(counter, value);
		return counter.bytes;
	}

	private void retain(long charge) {
		if (charge > this.maxBytes - this.bytes) {
			long observed = Long.MAX_VALUE - this.bytes < charge ? Long.MAX_VALUE : this.bytes + charge;
			throw limit(Limit.CHAT_STATE_BYTES, this.maxBytes, observed);
		}
		this.bytes += charge;
	}

	private static OpenRouterLimitExceededException limit(Limit limit, long configured, long observed) {
		return new OpenRouterLimitExceededException(limit, configured, observed, "/chat/completions", null, null, null);
	}

	private static final class Counter extends OutputStream {

		private long bytes;

		@Override
		public void write(int value) {
			add(1);
		}

		@Override
		public void write(byte[] value, int offset, int length) {
			add(length);
		}

		private void add(int length) {
			this.bytes = Long.MAX_VALUE - this.bytes < length ? Long.MAX_VALUE : this.bytes + length;
		}

	}

}
