package de.subhransu.openrouter.springai.api.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

class ChatCompletionChunkExtensionsTests {

	private static final String CHUNK = "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"synthetic\"}}]}";

	static Stream<Arguments> extensionLevels() {
		return Stream.of(Arguments.of(Named.of("chunk",
				(Function<ChatCompletionChunk, Map<String, @Nullable Object>>) ChatCompletionChunk::extensions)),
				Arguments.of(Named.of("choice",
						(Function<ChatCompletionChunk, Map<String, @Nullable Object>>) chunk -> chunk.choices()
							.get(0)
							.extensions())),
				Arguments.of(Named.of("delta",
						(Function<ChatCompletionChunk, Map<String, @Nullable Object>>) chunk -> chunk.choices()
							.get(0)
							.delta()
							.extensions())));
	}

	@ParameterizedTest
	@MethodSource("extensionLevels")
	void emptyExtensionsRetainNullQueriesAndRejectMutation(
			Function<ChatCompletionChunk, Map<String, @Nullable Object>> level) {
		Map<String, @Nullable Object> extensions = level
			.apply(new ObjectMapper().readValue(CHUNK, ChatCompletionChunk.class));

		assertThat(extensions).isEmpty();
		assertThat(extensions.containsKey(null)).isFalse();
		assertThat(extensions.get(null)).isNull();
		assertThatThrownBy(() -> extensions.remove("absent")).isInstanceOf(UnsupportedOperationException.class);
	}

}
