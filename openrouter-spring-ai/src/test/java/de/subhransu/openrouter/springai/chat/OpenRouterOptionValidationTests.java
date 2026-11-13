package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.subhransu.openrouter.springai.chat.mapper.OpenRouterChatRequestMapper;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterResponsesRequestMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Regression cases for option values and combinations that were sent unchanged, dropped
 * without notice, rejected late, or rejected with a vague message. Each case runs through
 * both request mappers. Warning texts use values unique to one test, because each
 * distinct warning is logged once per JVM.
 */
@ExtendWith(OutputCaptureExtension.class)
class OpenRouterOptionValidationTests {

	private static final String EFFORT = "/reasoning/effort";

	private static final List<Message> MESSAGES = List.of(new UserMessage("Synthetic input"));

	private final ObjectMapper mapper = new ObjectMapper();

	private final OpenRouterChatRequestMapper chat = new OpenRouterChatRequestMapper(this.mapper);

	private final OpenRouterResponsesRequestMapper responses = new OpenRouterResponsesRequestMapper(this.mapper);

	private JsonNode chat(OpenRouterChatOptions options, List<ToolDefinition> tools) {
		return this.mapper.valueToTree(this.chat.map(MESSAGES, options, false, tools));
	}

	private JsonNode responses(OpenRouterChatOptions options, List<ToolDefinition> tools) {
		return this.mapper.valueToTree(this.responses.map(MESSAGES, options, false, tools));
	}

	private static OpenRouterChatOptions.Builder base() {
		return OpenRouterChatOptions.builder().model("test/model");
	}

	private static ToolDefinition tool(String name) {
		return ToolDefinition.builder()
			.name(name)
			.description("Synthetic tool")
			.inputSchema("{\"type\":\"object\",\"properties\":{}}")
			.build();
	}

	private static int occurrences(String text, String part) {
		return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
	}

	// ---- enum-strings ---------------------------------------------------------------

	@Tag("api")
	@Tag("api.reasoning")
	@Test
	void typedReasoningEffortsSendTheDocumentedWireValues() {
		for (OpenRouterReasoningEffort effort : OpenRouterReasoningEffort.values()) {
			var options = base().reasoning(OpenRouterReasoningOptions.builder().effort(effort).build()).build();
			assertThat(chat(options, List.of()).at(EFFORT).stringValue()).isEqualTo(effort.value());
			assertThat(responses(options, List.of()).at(EFFORT).stringValue()).isEqualTo(effort.value());
		}
		assertThat(Arrays.stream(OpenRouterReasoningEffort.values()).map(OpenRouterReasoningEffort::value))
			.containsExactly("none", "minimal", "low", "medium", "high", "xhigh", "max");
	}

	@Tag("api")
	@Tag("api.reasoning")
	@Test
	void unknownEffortIsSentUnchangedAndWarnedAboutOnce(CapturedOutput output) {
		var options = base().reasoning(OpenRouterReasoningOptions.builder().effort("hgih-synthetic").build()).build();
		new OpenRouterReasoningOptions("hgih-synthetic", null, null, null);

		assertThat(chat(options, List.of()).at(EFFORT).stringValue()).isEqualTo("hgih-synthetic");
		assertThat(responses(options, List.of()).at(EFFORT).stringValue()).isEqualTo("hgih-synthetic");
		assertThat(occurrences(output.getAll(), "reasoning effort \"hgih-synthetic\" is not a value OpenRouter"))
			.isEqualTo(1);
		assertThat(output.getAll()).contains("none, minimal, low, medium, high, xhigh, max");
	}

	@Tag("api")
	@Tag("api.options")
	@Test
	void typedProviderValuesMatchTheStringForms() {
		var typed = OpenRouterProviderPreferences.builder()
			.sort(OpenRouterProviderSort.THROUGHPUT)
			.dataCollection(OpenRouterDataCollection.DENY)
			.quantizations(OpenRouterQuantization.FP8, OpenRouterQuantization.BF16)
			.build();

		assertThat(typed).isEqualTo(OpenRouterProviderPreferences.builder()
			.sort("throughput")
			.dataCollection("deny")
			.quantizations(List.of("fp8", "bf16"))
			.build());
		var options = base().provider(typed).build();
		for (JsonNode request : List.of(chat(options, List.of()), responses(options, List.of()))) {
			assertThat(request.at("/provider/sort").stringValue()).isEqualTo("throughput");
			assertThat(request.at("/provider/data_collection").stringValue()).isEqualTo("deny");
			assertThat(request.at("/provider/quantizations").toString()).isEqualTo("[\"fp8\",\"bf16\"]");
		}
	}

	@Tag("api")
	@Tag("api.options")
	@Test
	void unknownProviderValuesAreSentAndWarnedAbout(CapturedOutput output) {
		var provider = OpenRouterProviderPreferences.builder()
			.sort("cheapest-synthetic")
			.dataCollection("maybe-synthetic")
			.quantizations(List.of("fp7-synthetic"))
			.build();

		JsonNode request = chat(base().provider(provider).build(), List.of());
		assertThat(request.at("/provider/sort").stringValue()).isEqualTo("cheapest-synthetic");
		assertThat(output.getAll()).contains("provider sort \"cheapest-synthetic\"",
				"price, throughput, latency, exacto", "provider dataCollection \"maybe-synthetic\"",
				"provider quantization \"fp7-synthetic\"");
	}

	@Tag("multimodal")
	@Tag("multimodal.output")
	@Test
	void modalitiesAreLowerCasedForBothModes() {
		var options = base().modalities(List.of("Image", "TEXT")).build();

		assertThat(options.getModalities()).containsExactly("image", "text");
		assertThat(chat(options, List.of()).path("modalities").toString()).isEqualTo("[\"image\",\"text\"]");
		assertThat(responses(options, List.of()).path("modalities").toString()).isEqualTo("[\"image\",\"text\"]");
		assertThat(base().modalities(OpenRouterModality.IMAGE, OpenRouterModality.TEXT).build().getModalities())
			.containsExactly("image", "text");
	}

	@Tag("multimodal")
	@Tag("multimodal.output")
	@Test
	void audioModalityIsRecognizedInAnyCase() {
		var options = base().modalities(List.of("Text", "AUDIO"))
			.audio(new OpenRouterAudioOptions("alloy", "pcm16"))
			.build();

		assertThat(this.mapper.valueToTree(this.chat.map(MESSAGES, options, true, List.of()))
			.path("modalities")
			.toString()).isEqualTo("[\"text\",\"audio\"]");
		assertThatIllegalArgumentException().isThrownBy(() -> this.chat.map(MESSAGES, options, false, List.of()))
			.withMessageContaining("requires streaming Chat Completions");
	}

	@Tag("multimodal")
	@Tag("multimodal.output")
	@Test
	void unknownModalityIsWarnedAbout(CapturedOutput output) {
		base().modalities(List.of("hologram-synthetic")).build();

		assertThat(output.getAll()).contains("modality \"hologram-synthetic\"", "text, image, audio");
	}

	// ---- schema-dropped -------------------------------------------------------------

	@Tag("api")
	@Tag("api.request")
	@Test
	void outputSchemaWinsOverResponseFormatInBothModesWithAWarning(CapturedOutput output) {
		var options = base().responseFormat(OpenRouterResponseFormat.text())
			.outputSchema("{\"type\":\"object\"}")
			.build();

		assertThat(chat(options, List.of()).at("/response_format/json_schema/schema/type").stringValue())
			.isEqualTo("object");
		assertThat(responses(options, List.of()).at("/text/format/schema/type").stringValue()).isEqualTo("object");
		assertThat(occurrences(output.getAll(), "Both responseFormat (TEXT) and outputSchema are set")).isEqualTo(1);
	}

	@Tag("api")
	@Tag("api.options")
	@Test
	void aRequestFormatReplacesTheDefaultFormatPair() {
		var schemaDefault = base().outputSchema("{\"type\":\"object\"}").build();
		var formatDefault = base().responseFormat(OpenRouterResponseFormat.jsonObject()).build();

		var requestFormat = schemaDefault
			.merge(OpenRouterChatOptions.builder().responseFormat(OpenRouterResponseFormat.text()).build());
		assertThat(requestFormat.getResponseFormat()).isEqualTo(OpenRouterResponseFormat.text());
		assertThat(requestFormat.getOutputSchema()).isNull();

		var requestSchema = formatDefault.merge(OpenRouterChatOptions.builder().outputSchema("{}").build());
		assertThat(requestSchema.getResponseFormat()).isNull();
		assertThat(requestSchema.getOutputSchema()).isEqualTo("{}");

		var unrelated = formatDefault.merge(OpenRouterChatOptions.builder().temperature(0.0).build());
		assertThat(unrelated.getResponseFormat()).isEqualTo(OpenRouterResponseFormat.jsonObject());
	}

	// ---- silent-drops ---------------------------------------------------------------

	@Tag("api")
	@Tag("api.request")
	@Test
	void responsesUsesMaxCompletionTokensAndWarnsWhenBothLimitsDiffer(CapturedOutput output) {
		var options = base().maxTokens(256).maxCompletionTokens(513).build();

		assertThat(responses(options, List.of()).path("max_output_tokens").asInt()).isEqualTo(513);
		assertThat(output.getAll()).contains("OPENAI_RESPONSES sends one output limit",
				"using maxCompletionTokens 513 and ignoring maxTokens 256");
		JsonNode chat = chat(options, List.of());
		assertThat(chat.path("max_tokens").asInt()).isEqualTo(256);
		assertThat(chat.path("max_completion_tokens").asInt()).isEqualTo(513);
	}

	@Tag("tools")
	@Tag("tools.definition")
	@ParameterizedTest
	@ValueSource(strings = { "required", "{\"type\":\"required\"}", "{\"type\":\"function\",\"name\":\"lookup\"}" })
	void toolChoicesThatNeedToolsAreRejectedWithoutTools(String choice) {
		var options = base().toolChoice(choice.startsWith("{") ? this.mapper.readTree(choice) : choice).build();

		assertThatIllegalArgumentException().isThrownBy(() -> chat(options, List.of()))
			.withMessageContaining("toolChoice")
			.withMessageContaining("unset toolChoice");
		assertThatIllegalArgumentException().isThrownBy(() -> responses(options, List.of()))
			.withMessageContaining("toolChoice")
			.withMessageContaining("unset toolChoice");
	}

	@Tag("tools")
	@Tag("tools.definition")
	@Test
	void namedToolChoiceMustNameAnAdvertisedTool() {
		var options = base().toolChoice(Map.of("type", "function", "name", "lookup")).build();

		assertThatIllegalArgumentException().isThrownBy(() -> chat(options, List.of(tool("other"))))
			.withMessageContaining("\"lookup\"")
			.withMessageContaining("[other]");
		assertThatIllegalArgumentException().isThrownBy(() -> responses(options, List.of(tool("other"))))
			.withMessageContaining("\"lookup\"");
		assertThat(chat(options, List.of(tool("lookup"))).at("/tool_choice/function/name").stringValue())
			.isEqualTo("lookup");
	}

	@Tag("tools")
	@Tag("tools.definition")
	@ParameterizedTest
	@ValueSource(strings = { "auto", "none" })
	void autoAndNoneAreLeftOutWithoutTools(String choice) {
		var options = base().toolChoice(choice).build();

		assertThat(chat(options, List.of()).has("tool_choice")).isFalse();
		assertThat(responses(options, List.of()).has("tool_choice")).isFalse();
		assertThat(chat(options, List.of(tool("lookup"))).path("tool_choice").stringValue()).isEqualTo(choice);
	}

	// ---- late -----------------------------------------------------------------------

	@Tag("api")
	@Tag("api.options")
	@Test
	void nullListElementsAreRejectedWhenOptionsAreBuilt() {
		List<String> withNull = new ArrayList<>(Arrays.asList("test/fallback", null));

		assertThatIllegalArgumentException().isThrownBy(() -> base().models(withNull))
			.withMessageContaining("models must not contain null elements")
			.withMessageContaining("element 1");
		assertThatIllegalArgumentException().isThrownBy(() -> base().stopSequences(withNull))
			.withMessageContaining("stopSequences");
		assertThatIllegalArgumentException().isThrownBy(() -> base().modalities(withNull))
			.withMessageContaining("modalities");
		assertThatIllegalArgumentException().isThrownBy(() -> base().modalities(OpenRouterModality.TEXT, null))
			.withMessageContaining("modalities");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> OpenRouterProviderPreferences.builder().order(withNull).build())
			.withMessageContaining("provider order");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> OpenRouterProviderPreferences.builder().ignore(withNull).build())
			.withMessageContaining("provider ignore");
		assertThatIllegalArgumentException().isThrownBy(
				() -> OpenRouterProviderPreferences.builder().quantizations(OpenRouterQuantization.FP8, null).build())
			.withMessageContaining("provider quantizations");
	}

	@Tag("api")
	@Tag("api.extensions")
	@Test
	void responsesExtraBodyErrorNamesTheModeAndTheAcceptedKeys() {
		var options = base().extraBody(Map.of("verbosity", "low")).build();

		assertThat(chat(options, List.of()).path("verbosity").stringValue()).isEqualTo("low");
		assertThatIllegalArgumentException().isThrownBy(() -> responses(options, List.of()))
			.withMessage("OPENAI_RESPONSES does not support extraBody key \"verbosity\"; remove it or use "
					+ "OPENAI_CHAT_COMPLETIONS. Accepted keys in OPENAI_RESPONSES: prompt_cache_key, top_logprobs");
	}

	@Tag("api")
	@Tag("api.extensions")
	@Test
	void unknownExtraBodyKeyListsTheAcceptedKeys() {
		assertThatIllegalArgumentException().isThrownBy(() -> base().extraBody(Map.of("model", "x")))
			.withMessage("OPENAI_CHAT_COMPLETIONS does not support extraBody key \"model\". Accepted keys in "
					+ "OPENAI_CHAT_COMPLETIONS: logit_bias, logprobs, prompt_cache_key, top_logprobs, transforms, verbosity");
		assertThatIllegalArgumentException().isThrownBy(() -> base().providerExtraBody(Map.of("speed", "x")))
			.withMessageContaining("providerExtraBody does not support key \"speed\"")
			.withMessageContaining("max_price, only, preferred_max_latency");
	}

	// ---- vague ----------------------------------------------------------------------

	@Tag("tools")
	@Tag("tools.definition")
	@Test
	void toolChoiceErrorsEchoTheValue() {
		assertThatIllegalArgumentException().isThrownBy(() -> base().toolChoice("AUTO"))
			.withMessageStartingWith("toolChoice \"AUTO\" is not supported; use auto, none, required");
		var options = base().toolChoice(Map.of("type", "AUTO")).build();
		assertThatIllegalArgumentException().isThrownBy(() -> chat(options, List.of(tool("lookup"))))
			.withMessageStartingWith("toolChoice {\"type\":\"AUTO\"} is not supported");
		assertThatIllegalArgumentException().isThrownBy(() -> responses(options, List.of(tool("lookup"))))
			.withMessageStartingWith("toolChoice {\"type\":\"AUTO\"} is not supported");
	}

	@Tag("api")
	@Tag("api.extensions")
	@Test
	void extensionValueErrorsNameTheKeyEchoTheValueAndListTheChoices() {
		assertThatIllegalArgumentException().isThrownBy(() -> base().extraBody(Map.of("verbosity", "extreme")))
			.withMessage("extraBody verbosity \"extreme\" is not supported; use low, medium, high, xhigh, max");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> base().extraBody(Map.of("logprobs", true, "top_logprobs", "abc")))
			.withMessage("extraBody top_logprobs must be a number, but was \"abc\"");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> base().extraBody(Map.of("logprobs", true, "top_logprobs", 25)))
			.withMessage("extraBody top_logprobs must be an integer from 0 to 20, but was 25");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> base().providerExtraBody(Map.of("preferred_max_latency", "soon")))
			.withMessage("providerExtraBody preferred_max_latency must be a number, but was \"soon\"");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> base().providerExtraBody(Map.of("max_price", Map.of("prompt", "cheap"))))
			.withMessage("providerExtraBody max_price.prompt must be a number, but was \"cheap\"");
	}

	@Tag("multimodal")
	@Tag("multimodal.input")
	@Test
	void unsupportedInputMediaListsTheAcceptedTypes() {
		var media = Media.builder().mimeType(MimeTypeUtils.parseMimeType("audio/mp3")).data(new byte[] { 1 }).build();
		List<Message> messages = List.of(UserMessage.builder().text("Synthetic input").media(media).build());

		for (boolean responsesMode : List.of(false, true)) {
			assertThatThrownBy(() -> {
				if (responsesMode) {
					this.responses.map(messages, base().build(), false, List.of());
				}
				else {
					this.chat.map(messages, base().build(), false, List.of());
				}
			}).isInstanceOf(IllegalArgumentException.class)
				.hasMessageStartingWith("Input media type audio/mp3 is not supported; use image/*, application/pdf, "
						+ "audio/wav, audio/mpeg");
		}
	}

}
