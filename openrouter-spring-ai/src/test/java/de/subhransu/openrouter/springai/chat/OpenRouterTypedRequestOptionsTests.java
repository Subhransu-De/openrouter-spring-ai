package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterChatRequestMapper;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterChatResponseMapper;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterResponsesRequestMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("api")
@Tag("api.options")
class OpenRouterTypedRequestOptionsTests {

	private static final List<Message> MESSAGES = List.of(new UserMessage("Synthetic input"));

	private static final String CACHE_KEY = "prompt_cache_key";

	private final ObjectMapper json = new ObjectMapper();

	private final OpenRouterChatRequestMapper chat = new OpenRouterChatRequestMapper(this.json);

	private final OpenRouterResponsesRequestMapper responses = new OpenRouterResponsesRequestMapper(this.json);

	private JsonNode chat(OpenRouterChatOptions options, List<Message> messages) {
		return this.json.valueToTree(this.chat.map(messages, options, false, List.of()));
	}

	private JsonNode responses(OpenRouterChatOptions options) {
		return this.json.valueToTree(this.responses.map(MESSAGES, options, false, List.of()));
	}

	@Test
	void requestValuesOverrideDefaultsAndUnsetValuesInheritThem() {
		OpenRouterChatOptions defaults = OpenRouterChatOptions.builder()
			.logprobs(true)
			.topLogprobs(2)
			.logitBias(Map.of("50256", -100, "13", 5))
			.verbosity("low")
			.promptCacheKey("default-key")
			.build();

		JsonNode inherited = chat(defaults.merge(null), MESSAGES);
		JsonNode overridden = chat(defaults.merge(OpenRouterChatOptions.builder()
			.topLogprobs(0)
			.logitBias(Map.of("42", 1))
			.verbosity("max")
			.prediction("Synthetic answer")
			.build()), MESSAGES);

		assertThat(inherited.path("top_logprobs").asInt()).isEqualTo(2);
		assertThat(inherited.path("logit_bias")).isEqualTo(this.json.readTree("{\"50256\":-100,\"13\":5}"));
		assertThat(inherited.path("verbosity").asString()).isEqualTo("low");
		assertThat(inherited.has("prediction")).isFalse();
		assertThat(overridden.path("logprobs").asBoolean()).isTrue();
		assertThat(overridden.path("top_logprobs").asInt()).isZero();
		// A request bias map replaces the default map; it does not merge by token.
		assertThat(overridden.path("logit_bias")).isEqualTo(this.json.readTree("{\"42\":1}"));
		assertThat(overridden.path("verbosity").asString()).isEqualTo("max");
		assertThat(overridden.path(CACHE_KEY).asString()).isEqualTo("default-key");
		assertThat(overridden.path("prediction"))
			.isEqualTo(this.json.readTree("{\"type\":\"content\",\"content\":\"Synthetic answer\"}"));
	}

	@Test
	void builderRejectsValuesOutsideTheDocumentedRanges() {
		assertThatIllegalArgumentException().isThrownBy(() -> OpenRouterChatOptions.builder().topLogprobs(21))
			.withMessage("topLogprobs must be an integer from 0 to 20, but was 21");
		assertThatIllegalArgumentException().isThrownBy(() -> OpenRouterChatOptions.builder().verbosity("loud"))
			.withMessage("verbosity \"loud\" is not supported; use low, medium, high, xhigh, max");
		assertThatIllegalArgumentException().isThrownBy(() -> OpenRouterChatOptions.builder().promptCacheKey(""))
			.withMessageContaining("promptCacheKey must not be empty");
		Map<String, Integer> withNull = new LinkedHashMap<>();
		withNull.put("50256", null);
		assertThatIllegalArgumentException().isThrownBy(() -> OpenRouterChatOptions.builder().logitBias(withNull))
			.withMessageContaining("logitBias must not contain null");
	}

	@Test
	void logitBiasIsSnapshottedAndReadOnly() {
		Map<String, Integer> bias = new LinkedHashMap<>(Map.of("50256", -100));
		OpenRouterChatOptions options = OpenRouterChatOptions.builder().logitBias(bias).build();
		bias.put("13", 5);

		assertThat(options.getLogitBias()).containsExactly(Map.entry("50256", -100));
		assertThatThrownBy(() -> options.getLogitBias().put("13", 5)).isInstanceOf(UnsupportedOperationException.class);
	}

	@Tag("api.extensions")
	@Test
	void aTypedOptionAndTheSameExtraBodyKeyAreRejected() {
		String conflict = "extraBody prompt_cache_key \"extension-key\" conflicts with the typed option \"typed-key\"; "
				+ "set only one of them";
		OpenRouterChatOptions defaults = OpenRouterChatOptions.builder()
			.extraBody(Map.of(CACHE_KEY, "extension-key"))
			.build();

		// One options object, such as Boot defaults, fails when it is built.
		assertThatIllegalArgumentException().isThrownBy(
				() -> OpenRouterChatOptions.builder().verbosity("low").extraBody(Map.of("verbosity", "high")).build())
			.withMessage("extraBody verbosity \"high\" conflicts with the typed option \"low\"; set only one of them");
		// Defaults and request options merge field by field, so a default extension can
		// meet a request option.
		assertThatIllegalArgumentException()
			.isThrownBy(() -> defaults.merge(OpenRouterChatOptions.builder().promptCacheKey("typed-key").build()))
			.withMessage(conflict);
		// Low-level requests apply the same rule in both modes.
		assertThatIllegalArgumentException()
			.isThrownBy(() -> ChatCompletionRequest.builder()
				.messages(List.of())
				.promptCacheKey("typed-key")
				.extraBody(Map.of(CACHE_KEY, "extension-key"))
				.build())
			.withMessage(conflict);
		assertThatIllegalArgumentException()
			.isThrownBy(() -> ResponsesRequest.builder()
				.input("Synthetic input")
				.promptCacheKey("typed-key")
				.extraBody(Map.of(CACHE_KEY, "extension-key"))
				.build())
			.withMessage(conflict);
	}

	@Tag("api.extensions")
	@Test
	void topLogprobsNeedsLogprobsTrueFromEitherSourceInChatCompletions() {
		OpenRouterChatOptions typedTop = OpenRouterChatOptions.builder().topLogprobs(3).build();

		assertThatIllegalArgumentException().isThrownBy(() -> chat(typedTop, MESSAGES))
			.withMessage("top_logprobs requires logprobs=true in CHAT_COMPLETIONS, but logprobs was null");
		assertThatIllegalArgumentException().isThrownBy(() -> chat(typedTop.mutate().logprobs(false).build(), MESSAGES))
			.withMessageContaining("but logprobs was false");
		JsonNode mixed = chat(typedTop.mutate().extraBody(Map.of("logprobs", true)).build(), MESSAGES);
		JsonNode reversed = chat(
				OpenRouterChatOptions.builder().logprobs(true).extraBody(Map.of("top_logprobs", 1)).build(), MESSAGES);

		assertThat(mixed.path("logprobs").asBoolean()).isTrue();
		assertThat(mixed.path("top_logprobs").asInt()).isEqualTo(3);
		assertThat(reversed.path("top_logprobs").asInt()).isEqualTo(1);
		// Responses has no logprobs flag, so top_logprobs stands alone there.
		assertThat(responses(typedTop).path("top_logprobs").asInt()).isEqualTo(3);
	}

	@Tag("api.request")
	@Test
	void responsesRejectsTheChatCompletionsOnlyOptions() {
		List<OpenRouterChatOptions.Builder> chatOnly = List.of(OpenRouterChatOptions.builder().logprobs(true),
				OpenRouterChatOptions.builder().logitBias(Map.of("50256", -100)),
				OpenRouterChatOptions.builder().verbosity("low"), OpenRouterChatOptions.builder().prediction("text"));
		List<String> names = List.of("logprobs", "logitBias", "verbosity", "prediction");

		for (int index = 0; index < chatOnly.size(); index++) {
			OpenRouterChatOptions options = chatOnly.get(index).build();
			assertThatIllegalArgumentException().isThrownBy(() -> responses(options))
				.withMessage("RESPONSES does not support " + names.get(index) + "; unset it or use CHAT_COMPLETIONS");
		}
	}

	@Tag("api.request")
	@Test
	void promptCacheOptionsTravelWithCacheBreakpointsInBothModes() {
		String reference = "Synthetic reference.\n";
		UserMessage marked = UserMessage.builder()
			.text(reference + "Synthetic question.")
			.metadata(Map.of(OpenRouterCacheBreakpoint.METADATA_KEY,
					List.of(new OpenRouterCacheBreakpoint(reference.length()))))
			.build();
		OpenRouterChatOptions options = OpenRouterChatOptions.builder()
			.promptCacheOptions(OpenRouterPromptCacheOptions.explicit())
			.build();

		JsonNode body = chat(options, List.of(marked));

		assertThat(body.path("prompt_cache_options")).isEqualTo(this.json.readTree("{\"mode\":\"explicit\"}"));
		// Explicit mode caches only this marked block; OpenRouter converts the marker.
		assertThat(body.at("/messages/0/content/0/cache_control/type").asString()).isEqualTo("ephemeral");
		assertThat(responses(options).path("prompt_cache_options"))
			.isEqualTo(this.json.readTree("{\"mode\":\"explicit\"}"));
	}

	@Tag("api.options")
	@Test
	void promptCacheOptionsRequireAModeAndSendUndocumentedValuesUnchanged() {
		OpenRouterChatOptions future = OpenRouterChatOptions.builder()
			.promptCacheOptions(new OpenRouterPromptCacheOptions("future", "24h"))
			.build();

		// Explicit mode turns automatic caching off, so it is never inferred from a TTL.
		assertThatIllegalArgumentException().isThrownBy(() -> new OpenRouterPromptCacheOptions(null, "12h"))
			.withMessageStartingWith("promptCacheOptions mode must be set");
		assertThatIllegalArgumentException().isThrownBy(() -> new OpenRouterPromptCacheOptions("", null))
			.withMessageStartingWith("promptCacheOptions mode must be set");
		assertThatIllegalArgumentException().isThrownBy(() -> OpenRouterPromptCacheOptions.explicit(""))
			.withMessageContaining("promptCacheOptions ttl must not be empty");
		assertThat(chat(future, MESSAGES).path("prompt_cache_options"))
			.isEqualTo(this.json.readTree("{\"mode\":\"future\",\"ttl\":\"24h\"}"));
	}

	@Tag("api.response")
	@Test
	void logprobsReaderReturnsTypedTokensAndSkipsMalformedEntries() {
		ChatCompletionResponse wire = this.json.readValue("""
				{"id":"gen-1","choices":[{"index":0,"finish_reason":"stop",
				  "message":{"role":"assistant","content":"Hi"},
				  "logprobs":{"content":[
				    {"token":"Hi","logprob":-0.5,"bytes":[72,105],
				     "top_logprobs":[{"token":"Hi","logprob":-0.5,"bytes":[72,105]},{"token":"Yo","logprob":-2}]},
				    {"logprob":-1},
				    {"token":"!","logprob":"low","bytes":[33]},
				    {"token":"?","logprob":-3,"bytes":["x"]},
				    {"token":"a","logprob":-5,"bytes":[72.5]},
				    {"token":"b","logprob":-6,"bytes":[256]},
				    {"token":"c","logprob":-7,"bytes":[-1]},
				    {"token":"d","logprob":-8,"bytes":[4294967368]}],
				   "refusal":[{"token":"No","logprob":-4,"bytes":null,"top_logprobs":[]}]}}]}
				""", ChatCompletionResponse.class);

		AssistantMessage output = new OpenRouterChatResponseMapper().map(wire).getResult().getOutput();
		OpenRouterLogprobs logprobs = OpenRouterLogprobs.from(output).orElseThrow();

		assertThat(logprobs.content()).extracting(OpenRouterLogprobs.Token::token)
			.containsExactly("Hi", "?", "a", "b", "c", "d");
		assertThat(logprobs.content().get(0).bytes()).containsExactly(72, 105);
		// Non-numeric, fractional and out-of-range bytes are not coerced into bytes.
		assertThat(logprobs.content().subList(1, 6)).allSatisfy(token -> assertThat(token.bytes()).isNull());
		assertThat(logprobs.content().get(0).topLogprobs()).containsExactly(
				new OpenRouterLogprobs.Alternative("Hi", -0.5, List.of(72, 105)),
				new OpenRouterLogprobs.Alternative("Yo", -2.0, null));
		assertThat(logprobs.refusal()).containsExactly(new OpenRouterLogprobs.Token("No", -4.0, null, List.of()));
		// The raw extension metadata is kept next to the typed reader.
		assertThat(output.getMetadata()).containsKey(OpenRouterMetadata.CHOICE_EXTENSIONS);
	}

	@Tag("api.response")
	@Test
	void logprobsReaderIsEmptyWhenTheProviderSentNone() {
		for (String logprobs : List.of("null", "{\"content\":null,\"refusal\":null}")) {
			ChatCompletionResponse wire = this.json.readValue("""
					{"id":"gen-2","choices":[{"index":0,"finish_reason":"stop","logprobs":%s,
					  "message":{"role":"assistant","content":"Hi"}}]}
					""".formatted(logprobs), ChatCompletionResponse.class);

			assertThat(OpenRouterLogprobs.from(new OpenRouterChatResponseMapper().map(wire).getResult().getOutput()))
				.as(logprobs)
				.isEmpty();
		}
		assertThat(OpenRouterLogprobs.from(new AssistantMessage("Hi"))).isEmpty();
	}

}
