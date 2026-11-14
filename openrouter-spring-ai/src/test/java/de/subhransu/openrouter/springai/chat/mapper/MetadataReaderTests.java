package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.chat.OpenRouterFinishReason;
import de.subhransu.openrouter.springai.chat.OpenRouterMetadata;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

@Tag("api")
@Tag("api.response")
class MetadataReaderTests {

	private final ObjectMapper json = new ObjectMapper();

	@Test
	void readsChatCompletionsCallMetadataWithoutStringKeys() {
		Generation generation = new OpenRouterChatResponseMapper().map(this.json.readValue("""
				{"model":"synthetic/model","choices":[{"index":0,"finish_reason":"length",
				 "native_finish_reason":"MAX_TOKENS","message":{"role":"assistant","content":"",
				 "reasoning":"thinking","refusal":"cannot help"}}]}""", ChatCompletionResponse.class)).getResult();

		assertThat(OpenRouterMetadata.reasoning(generation)).contains("thinking");
		assertThat(OpenRouterMetadata.reasoning(generation.getOutput())).contains("thinking");
		assertThat(OpenRouterMetadata.refusal(generation)).contains("cannot help");
		assertThat(OpenRouterMetadata.refusal(generation.getOutput())).contains("cannot help");
		assertThat(OpenRouterMetadata.nativeFinishReason(generation)).contains("MAX_TOKENS");
		assertThat(OpenRouterMetadata.responsesStatus(generation)).isEmpty();
		assertThat(OpenRouterMetadata.choiceIndex(generation)).isZero();
		assertThat(OpenRouterFinishReason.from(generation)).isEqualTo(OpenRouterFinishReason.LENGTH);
		assertThat(generation.getMetadata().getFinishReason()).isEqualTo("LENGTH");
		assertThat(generation.getMetadata().<String>get(OpenRouterMetadata.MODEL)).isEqualTo("synthetic/model");
	}

	@Test
	void absentValuesReadAsEmpty() {
		Generation generation = new OpenRouterChatResponseMapper().map(this.json.readValue("""
				{"choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"hi"}}]}""",
				ChatCompletionResponse.class))
			.getResult();

		assertThat(OpenRouterMetadata.reasoning(generation)).isEmpty();
		assertThat(OpenRouterMetadata.reasoning(generation.getOutput())).isEmpty();
		assertThat(OpenRouterMetadata.refusal(generation)).isEmpty();
		assertThat(OpenRouterMetadata.nativeFinishReason(generation)).isEmpty();
		assertThat(OpenRouterFinishReason.from(generation)).isEqualTo(OpenRouterFinishReason.STOP);
	}

	@Test
	void valuesOfAnotherTypeReadAsEmptyInsteadOfFailing() {
		Generation generation = new Generation(
				AssistantMessage.builder().content("").properties(Map.of(OpenRouterMetadata.REFUSAL, 1)).build(),
				ChatGenerationMetadata.builder()
					.metadata(OpenRouterMetadata.REASONING, List.of())
					.metadata(OpenRouterMetadata.CHOICE_INDEX, "2")
					.build());

		assertThat(OpenRouterMetadata.reasoning(generation)).isEmpty();
		assertThat(OpenRouterMetadata.refusal(generation.getOutput())).isEmpty();
		assertThat(OpenRouterMetadata.choiceIndex(generation)).isZero();
	}

	@Test
	void readsStreamedChoiceIndexAndReasoningDeltaVersusTextSoFar() {
		List<ChatResponse> responses = new OpenRouterStreamingResponseMapper()
			.map(Flux.just(chunk("{\"index\":2,\"delta\":{\"reasoning\":\"one \"}}"),
					chunk("{\"index\":2,\"delta\":{\"reasoning\":\"two\"},\"finish_reason\":\"end_turn\"}")))
			.collectList()
			.block();

		Generation last = responses.get(1).getResult();
		assertThat(OpenRouterMetadata.choiceIndex(responses.get(0).getResult())).isEqualTo(2);
		assertThat(OpenRouterMetadata.choiceIndex(last)).isEqualTo(2);
		assertThat(OpenRouterMetadata.reasoning(last)).contains("two");
		assertThat(OpenRouterMetadata.reasoning(last.getOutput())).contains("one two");
		assertThat(OpenRouterFinishReason.from(responses.get(0).getResult())).isEqualTo(OpenRouterFinishReason.NONE);
		assertThat(OpenRouterFinishReason.from(last)).isEqualTo(OpenRouterFinishReason.OTHER);
		assertThat(last.getMetadata().getFinishReason()).isEqualTo("end_turn");
	}

	@Test
	void readsResponsesCallMetadata() {
		Generation generation = new OpenRouterResponsesResponseMapper().map(this.json.readValue("""
				{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[
				 {"type":"reasoning","id":"rs-1","content":[{"type":"reasoning_text","text":"thinking"}]},
				 {"type":"message","id":"msg-1","role":"assistant","content":[{"type":"refusal","refusal":"no"}]}]}""",
				ResponsesResult.class))
			.getResult();

		assertThat(OpenRouterMetadata.reasoning(generation)).contains("thinking");
		assertThat(OpenRouterMetadata.refusal(generation)).contains("no");
		assertThat(OpenRouterMetadata.responsesStatus(generation)).contains("incomplete");
		assertThat(OpenRouterMetadata.nativeFinishReason(generation)).contains("max_output_tokens");
		assertThat(OpenRouterFinishReason.from(generation)).isEqualTo(OpenRouterFinishReason.LENGTH);
		assertThat(OpenRouterMetadata.choiceIndex(generation)).isZero();
	}

	@Test
	void readsResponsesStreamReasoningDelta() {
		Generation generation = new OpenRouterResponsesStreamingResponseMapper()
			.map(this.json.readValue("{\"type\":\"response.reasoning_text.delta\",\"delta\":\"thinking\"}",
					ResponsesStreamEvent.class))
			.getResult();

		assertThat(OpenRouterMetadata.reasoning(generation)).contains("thinking");
		assertThat(OpenRouterMetadata.reasoning(generation.getOutput())).contains("thinking");
		assertThat(OpenRouterFinishReason.from(generation)).isEqualTo(OpenRouterFinishReason.NONE);
	}

	@ParameterizedTest
	@CsvSource({ "STOP, STOP", "stop, STOP", "completed, STOP", "LENGTH, LENGTH", "length, LENGTH",
			"max_output_tokens, LENGTH", "TOOL_CALLS, TOOL_CALLS", "tool_calls, TOOL_CALLS",
			"function_call, TOOL_CALLS", "CONTENT_FILTER, CONTENT_FILTER", "content_filter, CONTENT_FILTER",
			"error, ERROR", "ERROR, ERROR", "incomplete, OTHER", "end_turn, OTHER", "' ', NONE", "'', NONE" })
	void classifiesBothSpellings(String raw, OpenRouterFinishReason expected) {
		assertThat(OpenRouterFinishReason.from(raw)).isEqualTo(expected);
	}

	@Test
	void classifiesAMissingFinishReasonAsNone() {
		assertThat(OpenRouterFinishReason.from((String) null)).isEqualTo(OpenRouterFinishReason.NONE);
		assertThat(OpenRouterFinishReason.from(new Generation(new AssistantMessage("hi"))))
			.isEqualTo(OpenRouterFinishReason.NONE);
	}

	@Test
	void publicKeysMatchTheValuesTheMappersSet() {
		assertThat(List.of(OpenRouterMetadata.REASONING, OpenRouterMetadata.REFUSAL,
				OpenRouterMetadata.NATIVE_FINISH_REASON, OpenRouterMetadata.CHOICE_INDEX, OpenRouterMetadata.AUDIO))
			.containsExactly("openrouter.reasoning", "openrouter.refusal", "openrouter.native_finish_reason",
					"openrouter.choice_index", "openrouter.audio");
	}

	private ChatCompletionChunk chunk(String choice) {
		return this.json.readValue("{\"model\":\"synthetic/model\",\"choices\":[" + choice + "]}",
				ChatCompletionChunk.class);
	}

}
