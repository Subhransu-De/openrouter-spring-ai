package de.subhransu.openrouter.springai.api;

import de.subhransu.openrouter.springai.errors.OpenRouterLimitExceededException;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import java.io.IOException;
import java.io.InputStream;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatusCode;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.ObjectMapper;

final class BlockingResponseDecoder {

	private final ObjectMapper objectMapper;

	private final int limit;

	BlockingResponseDecoder(ObjectMapper objectMapper, int limit) {
		this.objectMapper = objectMapper;
		this.limit = limit;
	}

	static boolean supports(ObjectMapper objectMapper) {
		return !objectMapper.isEnabled(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
				&& !objectMapper.tokenStreamFactory().streamReadConstraints().hasMaxDocumentLength();
	}

	<T> T decode(String uri, HttpStatusCode statusCode, InputStream body, Class<T> responseType) throws IOException {
		BoundedInputStream input = new BoundedInputStream(body, this.limit);
		@Nullable T decoded = null;
		@Nullable RuntimeException failure = null;
		try {
			decoded = this.objectMapper.readValue(input, responseType);
		}
		catch (RuntimeException ex) {
			failure = ex;
		}
		input.finish();
		if (input.exceeded) {
			throw new OpenRouterLimitExceededException(
					OpenRouterLimitExceededException.Limit.BLOCKING_RESPONSE_BODY_BYTES, this.limit, this.limit + 1L,
					uri, statusCode, null, null);
		}
		if (failure instanceof JacksonException) {
			if (input.size == 0) {
				throw new OpenRouterProtocolException("Empty OpenRouter " + uri + " response");
			}
			throw new OpenRouterProtocolException("Failed to decode OpenRouter " + uri + " response", failure);
		}
		if (failure != null) {
			throw failure;
		}
		if (decoded == null) {
			throw new OpenRouterProtocolException("Null OpenRouter " + uri + " response");
		}
		return decoded;
	}

	private static final class BoundedInputStream extends InputStream {

		private final InputStream input;

		private final int limit;

		private final byte[] single = new byte[1];

		private int size;

		private boolean exceeded;

		private @Nullable Exception failure;

		private BoundedInputStream(InputStream input, int limit) {
			this.input = input;
			this.limit = limit;
		}

		@Override
		public int read() throws IOException {
			return read(this.single, 0, 1) < 0 ? -1 : this.single[0] & 0xFF;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			if (length == 0) {
				return 0;
			}
			if (this.size == this.limit) {
				this.exceeded = this.exceeded || readInput(this.single, 0, 1) > 0;
				return -1;
			}
			int count = readInput(buffer, offset, Math.min(length, this.limit - this.size));
			if (count > 0) {
				this.size += count;
			}
			return count;
		}

		private int readInput(byte[] buffer, int offset, int length) throws IOException {
			try {
				int count;
				do {
					count = this.input.read(buffer, offset, length);
				}
				while (count == 0);
				return count;
			}
			catch (IOException | RuntimeException ex) {
				this.failure = ex;
				throw ex;
			}
		}

		@Override
		public void close() {
		}

		private void finish() throws IOException {
			if (this.failure instanceof IOException ex) {
				throw ex;
			}
			if (this.failure instanceof RuntimeException ex) {
				throw ex;
			}
			if (this.exceeded || read() < 0) {
				return;
			}
			byte[] buffer = new byte[8192];
			int count;
			do {
				count = read(buffer, 0, buffer.length);
			}
			while (count >= 0);
		}

	}

}
