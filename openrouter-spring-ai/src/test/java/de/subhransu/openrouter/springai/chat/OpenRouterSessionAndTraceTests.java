package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingOptions;
import de.subhransu.openrouter.springai.embedding.mapper.OpenRouterEmbeddingRequestMapper;
import de.subhransu.openrouter.springai.image.OpenRouterImageOptions;
import de.subhransu.openrouter.springai.image.mapper.OpenRouterImageRequestMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.image.ImagePrompt;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("api")
@Tag("api.options")
class OpenRouterSessionAndTraceTests {

	private static final String DEFAULT_SESSION = "default-session";

	private static final String CONVERSATION = "conversation-2";

	private final ObjectMapper objectMapper = new ObjectMapper();

	private static OpenRouterTrace defaultTrace() {
		return OpenRouterTrace.builder().traceName("synthetic-app").metadata(Map.of("tenant", "synthetic")).build();
	}

	@Test
	void unsetSessionAndTraceAreLeftOutOfTheRequest() {
		JsonNode body = this.objectMapper.valueToTree(new OpenRouterEmbeddingRequestMapper().map(List.of("hello"),
				OpenRouterEmbeddingOptions.builder().model("synthetic/embedding").build()));

		assertThat(body.has("session_id")).isFalse();
		assertThat(body.has("trace")).isFalse();
	}

	@Test
	void sessionIdAllowsAtMost256CharactersCountedAsCodePoints() {
		// Each emoji is one character but two UTF-16 units, so a length() check would
		// reject a valid 256-character value.
		String longest = "😀".repeat(256);

		assertThat(OpenRouterChatOptions.builder().sessionId(longest).build().getSessionId()).isEqualTo(longest);
		assertThatThrownBy(() -> OpenRouterChatOptions.builder().sessionId(longest + "x"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("sessionId must be at most 256 characters, but was 257");
		assertThatThrownBy(() -> OpenRouterEmbeddingOptions.builder().sessionId("x".repeat(257)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("sessionId");
		assertThatThrownBy(() -> OpenRouterImageOptions.builder().sessionId("x".repeat(257)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("sessionId");
	}

	@Test
	void traceMetadataCannotRepeatANamedFieldAndIsSnapshotted() {
		assertThatThrownBy(() -> OpenRouterTrace.builder().metadata(Map.of("trace_id", "duplicate")).build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("trace metadata key \"trace_id\"");
		Map<String, Object> metadata = new LinkedHashMap<>(Map.of("tenant", "synthetic"));
		OpenRouterTrace trace = OpenRouterTrace.builder().metadata(metadata).build();
		metadata.put("trace_id", "added later");

		assertThat(trace.metadata()).containsExactlyEntriesOf(Map.of("tenant", "synthetic"));
	}

	@Tag("embeddings")
	@Tag("embeddings.options")
	@Test
	void embeddingsSendDefaultsAndPerRequestOverrides() {
		OpenRouterEmbeddingOptions defaults = OpenRouterEmbeddingOptions.builder()
			.model("synthetic/embedding")
			.sessionId(DEFAULT_SESSION)
			.trace(defaultTrace())
			.build();
		OpenRouterEmbeddingRequestMapper mapper = new OpenRouterEmbeddingRequestMapper();

		JsonNode inherited = this.objectMapper.valueToTree(mapper.map(List.of("hello"), defaults.merge(null)));
		JsonNode overridden = this.objectMapper.valueToTree(mapper.map(List.of("hello"),
				defaults.merge(OpenRouterEmbeddingOptions.builder()
					.sessionId(CONVERSATION)
					.trace(OpenRouterTrace.builder().traceId("trace-1").build())
					.build())));

		assertThat(inherited.path("session_id").stringValue()).isEqualTo(DEFAULT_SESSION);
		assertThat(inherited.path("trace").path("trace_name").stringValue()).isEqualTo("synthetic-app");
		assertThat(inherited.path("trace").path("tenant").stringValue()).isEqualTo("synthetic");
		assertThat(overridden.path("session_id").stringValue()).isEqualTo(CONVERSATION);
		assertThat(overridden.path("trace")).isEqualTo(this.objectMapper.valueToTree(Map.of("trace_id", "trace-1")));
	}

	@Tag("multimodal")
	@Tag("multimodal.image-model")
	@Test
	void imagesSendDefaultsAndPerRequestOverridesForCallsAndStreams() {
		OpenRouterImageOptions defaults = OpenRouterImageOptions.builder()
			.model("synthetic/image")
			.sessionId(DEFAULT_SESSION)
			.trace(defaultTrace())
			.build();
		OpenRouterImageOptions merged = defaults
			.merge(OpenRouterImageOptions.builder().sessionId(CONVERSATION).build());
		OpenRouterImageRequestMapper mapper = new OpenRouterImageRequestMapper();

		for (boolean stream : List.of(false, true)) {
			JsonNode body = this.objectMapper.valueToTree(mapper.map(new ImagePrompt("a circle"), merged, stream));
			assertThat(body.path("session_id").stringValue()).isEqualTo(CONVERSATION);
			assertThat(body.path("trace").path("trace_name").stringValue()).isEqualTo("synthetic-app");
			assertThat(body.path("trace").path("tenant").stringValue()).isEqualTo("synthetic");
		}
	}

}
