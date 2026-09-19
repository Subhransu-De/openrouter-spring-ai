package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

/**
 * Robustness tests for {@link OpenRouterChatOptions}: builder/build aliasing, defensive
 * copy of mutable collections and nested objects, merge semantics across scalars,
 * collections, maps and booleans, and the deliberately lossy {@code fromOptions}
 * conversion from a generic {@link ChatOptions}. The baseline precedence and tool-field
 * tests stay in {@code OpenRouterChatOptionsTests}; this class owns the subtle branches.
 */
class OpenRouterChatOptionsRobustnessTests {

	private static final String MODEL = "openai/gpt-5.4-mini";

	private ToolCallback tool(String name) {
		return FunctionToolCallback.builder(name, (Map<String, Object> in) -> "ok")
			.description(name)
			.inputType(Map.class)
			.build();
	}

	// ---------------------------------------------------------------------
	// Builder / build aliasing
	// ---------------------------------------------------------------------

	@Test
	void mutatingBuilderAfterBuildDoesNotAffectBuiltOptions() {
		// build() hands out a detached snapshot: post-build setter calls stay in the
		// builder and only show up in the next build() result.
		OpenRouterChatOptions.Builder builder = OpenRouterChatOptions.builder().model(MODEL);
		OpenRouterChatOptions built = builder.build();

		builder.temperature(0.9);

		assertThat(built.getTemperature()).isNull();
		assertThat(builder.build().getTemperature()).isEqualTo(0.9);
	}

	@Test
	void copyIsIndependentOfTheSource() {
		OpenRouterChatOptions source = OpenRouterChatOptions.builder()
			.model(MODEL)
			.stopSequences(List.of("END"))
			.metadata(Map.of("k", "v"))
			.build();

		OpenRouterChatOptions copy = source.copy();
		copy = copy.mutate().stopSequences(List.of("END", "STOP")).build();

		// The source's mutable collections are untouched by edits to the copy.
		assertThat(source.getStopSequences()).containsExactly("END");
		assertThat(copy.getStopSequences()).containsExactly("END", "STOP");
	}

	@Test
	void copyDoesNotShareMutableMaps() {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("trace", "1");
		OpenRouterChatOptions source = OpenRouterChatOptions.builder().model(MODEL).metadata(metadata).build();

		OpenRouterChatOptions copy = source.copy();
		copy = copy.mutate().metadata(Map.of("trace", "2")).build();

		assertThat(source.getMetadata()).containsEntry("trace", "1");
		assertThat(copy.getMetadata()).containsEntry("trace", "2");
	}

	@Test
	void copyPreservesNestedProviderAndReasoning() {
		OpenRouterProviderPreferences provider = new OpenRouterProviderPreferences(true, null, null, List.of("openai"),
				null, null, "throughput");
		OpenRouterReasoningOptions reasoning = new OpenRouterReasoningOptions("high", null, false, true);
		OpenRouterChatOptions source = OpenRouterChatOptions.builder()
			.model(MODEL)
			.provider(provider)
			.reasoning(reasoning)
			.build();

		OpenRouterChatOptions copy = source.copy();

		// Nested records are immutable, so sharing the reference is safe and expected.
		assertThat(copy.getProvider()).isEqualTo(provider);
		assertThat(copy.getReasoning()).isEqualTo(reasoning);
	}

	// ---------------------------------------------------------------------
	// Exhaustive field sweep: every option must survive merge() and copy()
	// ---------------------------------------------------------------------

	private OpenRouterChatOptions fullyPopulatedOptions() {
		return OpenRouterChatOptions.builder()
			.model(MODEL)
			.models(List.of("openai/gpt-5.4"))
			.requestMode(OpenRouterRequestMode.OPENAI_RESPONSES)
			.frequencyPenalty(0.1)
			.maxTokens(256)
			.maxCompletionTokens(512)
			.presencePenalty(0.2)
			.stopSequences(List.of("END"))
			.temperature(0.7)
			.topK(40)
			.topP(0.9)
			.repetitionPenalty(1.1)
			.minP(0.05)
			.topA(0.8)
			.seed(42)
			.user("user-7")
			.responseFormat(OpenRouterResponseFormat.jsonObject())
			.parallelToolCalls(true)
			.toolStrict(true)
			.toolChoice("auto")
			.provider(new OpenRouterProviderPreferences(true, false, "deny", List.of("openai"), List.of("azure"),
					List.of("fp16"), "throughput"))
			.reasoning(new OpenRouterReasoningOptions("high", null, false, true))
			.serviceTier(OpenRouterServiceTier.FLEX)
			.metadata(Map.of("trace", "abc"))
			.route("fallback")
			.includeUsage(true)
			.modalities(List.of("image", "text"))
			.imageConfig(Map.of("aspect_ratio", "16:9"))
			.outputSchema("""
					{
					  "type": "object"
					}
					""")
			.toolCallbacks(List.of(tool("get_weather")))
			.toolContext(Map.of("tenant", "acme"))
			.build();
	}

	@Test
	void fullyPopulatedFixtureCoversEveryDeclaredField() throws IllegalAccessException {
		// Guard for the sweep tests below: a newly added option field that the fixture
		// does not populate fails here, so it cannot silently escape merge()/copy()
		// coverage.
		OpenRouterChatOptions options = fullyPopulatedOptions();
		for (Field field : OpenRouterChatOptions.class.getDeclaredFields()) {
			field.setAccessible(true);
			assertThat(field.get(options))
				.as("field '%s' must be populated in fullyPopulatedOptions() so the merge/copy sweeps cover it",
						field.getName())
				.isNotNull();
		}
	}

	@Test
	void mergePreservesEveryDefaultFieldWhenRuntimeIsEmpty() {
		OpenRouterChatOptions defaults = fullyPopulatedOptions();

		OpenRouterChatOptions merged = defaults.merge(OpenRouterChatOptions.builder().build());

		assertThat(merged).usingRecursiveComparison().ignoringFields("toolCallbacks").isEqualTo(defaults);
		assertThat(merged.getToolCallbacks()).containsExactlyElementsOf(defaults.getToolCallbacks());
	}

	@Test
	void mergeTakesEveryRuntimeFieldWhenDefaultsAreEmpty() {
		OpenRouterChatOptions runtime = fullyPopulatedOptions();

		OpenRouterChatOptions merged = OpenRouterChatOptions.builder().build().merge(runtime);

		assertThat(merged).usingRecursiveComparison().ignoringFields("toolCallbacks").isEqualTo(runtime);
		assertThat(merged.getToolCallbacks()).containsExactlyElementsOf(runtime.getToolCallbacks());
	}

	@Test
	void copyPreservesEveryField() {
		OpenRouterChatOptions source = fullyPopulatedOptions();

		OpenRouterChatOptions copy = source.copy();

		assertThat((Object) copy).isNotSameAs(source);
		assertThat((Object) copy).usingRecursiveComparison().ignoringFields("toolCallbacks").isEqualTo(source);
		assertThat(copy.getToolCallbacks()).containsExactlyElementsOf(source.getToolCallbacks());
	}

	@Test
	void copyDoesNotShareToolCallbacksListOrToolContextMap() {
		// These are the collections mutated most at runtime (by the tool-calling
		// advisor loop), so aliasing between copy and source would be an
		// action-at-a-distance bug.
		OpenRouterChatOptions source = fullyPopulatedOptions();

		OpenRouterChatOptions copy = source.copy();
		copy.setToolCallbacks(List.of(tool("extra")));
		copy.setToolContext(Map.of("region", "eu"));

		assertThat(source.getToolCallbacks()).hasSize(1);
		assertThat(source.getToolContext()).doesNotContainKey("region");
		assertThat(copy.getToolCallbacks()).hasSize(1);
		assertThat(copy.getToolContext()).containsEntry("region", "eu");
	}

	// ---------------------------------------------------------------------
	// Merge semantics
	// ---------------------------------------------------------------------

	@Test
	void scalarMergeIsLastNonNullWins() {
		OpenRouterChatOptions defaults = OpenRouterChatOptions.builder()
			.model(MODEL)
			.temperature(0.2)
			.topP(0.5)
			.build();
		OpenRouterChatOptions runtime = OpenRouterChatOptions.builder().temperature(0.9).build();

		OpenRouterChatOptions merged = defaults.merge(runtime);

		// runtime temperature wins; runtime topP is null so the default survives.
		assertThat(merged.getTemperature()).isEqualTo(0.9);
		assertThat(merged.getTopP()).isEqualTo(0.5);
		assertThat(merged.getModel()).isEqualTo(MODEL);
	}

	@Test
	void nullRuntimeOptionLeavesConfiguredDefaultInPlace() {
		OpenRouterChatOptions defaults = OpenRouterChatOptions.builder().model(MODEL).route("fallback").build();
		OpenRouterChatOptions runtime = OpenRouterChatOptions.builder().temperature(0.1).build();

		OpenRouterChatOptions merged = defaults.merge(runtime);

		assertThat(merged.getRoute()).isEqualTo("fallback");
	}

	@Test
	void falseBooleanRuntimeOptionOverridesTrueDefault() {
		OpenRouterChatOptions defaults = OpenRouterChatOptions.builder().model(MODEL).parallelToolCalls(true).build();
		OpenRouterChatOptions runtime = OpenRouterChatOptions.builder().parallelToolCalls(false).build();

		OpenRouterChatOptions merged = defaults.merge(runtime);

		// Boolean.FALSE is non-null, so it wins over the default TRUE.
		assertThat(merged.getParallelToolCalls()).isFalse();
	}

	@Test
	void runtimeToolCallbacksReplaceDefaultsAndEmptyRuntimeFallsBackToDefaults() {
		// Framework semantics (ToolCallingChatOptions.mergeToolCallbacks): runtime tool
		// callbacks replace the defaults wholesale; only an empty runtime list falls
		// back to the defaults. Accumulating both would advertise tools the executing
		// advisor never saw on the runtime options.
		ToolCallback defaultTool = tool("a");
		ToolCallback runtimeTool = tool("b");
		OpenRouterChatOptions defaults = OpenRouterChatOptions.builder()
			.model(MODEL)
			.toolCallbacks(List.of(defaultTool))
			.build();
		OpenRouterChatOptions runtime = OpenRouterChatOptions.builder().toolCallbacks(List.of(runtimeTool)).build();

		assertThat(defaults.merge(runtime).getToolCallbacks()).containsExactly(runtimeTool);
		assertThat(defaults.merge(OpenRouterChatOptions.builder().build()).getToolCallbacks())
			.containsExactly(defaultTool);
	}

	@Test
	void metadataAndToolContextMergeAdditivelyWithRuntimeWinningOnKeyClash() {
		OpenRouterChatOptions defaults = OpenRouterChatOptions.builder()
			.model(MODEL)
			.metadata(new LinkedHashMap<>(Map.of("a", "1", "shared", "default")))
			.toolContext(new LinkedHashMap<>(Map.of("tenant", "acme")))
			.build();
		OpenRouterChatOptions runtime = OpenRouterChatOptions.builder()
			.metadata(new LinkedHashMap<>(Map.of("b", "2", "shared", "runtime")))
			.toolContext(new LinkedHashMap<>(Map.of("region", "eu")))
			.build();

		OpenRouterChatOptions merged = defaults.merge(runtime);

		assertThat(merged.getMetadata()).containsEntry("a", "1")
			.containsEntry("b", "2")
			.containsEntry("shared", "runtime");
		assertThat(merged.getToolContext()).containsEntry("tenant", "acme").containsEntry("region", "eu");
	}

	@Test
	void mergeWithNullRuntimeReturnsIndependentCopy() {
		OpenRouterChatOptions defaults = OpenRouterChatOptions.builder()
			.model(MODEL)
			.stopSequences(List.of("END"))
			.build();

		OpenRouterChatOptions merged = defaults.merge(null);

		assertThat(merged).isNotSameAs(defaults);
		assertThat(defaults.getStopSequences()).containsExactly("END");
		assertThat(merged.getStopSequences()).containsExactly("END");
	}

	// ---------------------------------------------------------------------
	// Generic ChatOptions conversion (lossy)
	// ---------------------------------------------------------------------

	@Test
	void fromOptionsPreservesPortableFields() {
		ChatOptions portable = ChatOptions.builder()
			.model(MODEL)
			.temperature(0.4)
			.topP(0.8)
			.topK(20)
			.maxTokens(256)
			.frequencyPenalty(0.1)
			.presencePenalty(0.2)
			.stopSequences(List.of("X"))
			.build();

		OpenRouterChatOptions converted = OpenRouterChatOptions.fromOptions(portable);

		assertThat(converted.getModel()).isEqualTo(MODEL);
		assertThat(converted.getTemperature()).isEqualTo(0.4);
		assertThat(converted.getTopP()).isEqualTo(0.8);
		assertThat(converted.getTopK()).isEqualTo(20);
		assertThat(converted.getMaxTokens()).isEqualTo(256);
		assertThat(converted.getFrequencyPenalty()).isEqualTo(0.1);
		assertThat(converted.getPresencePenalty()).isEqualTo(0.2);
		assertThat(converted.getStopSequences()).containsExactly("X");
	}

	@Test
	void fromOptionsLeavesOpenRouterSpecificFieldsUnavailable() {
		// A generic ChatOptions cannot carry OpenRouter-only concepts, so the conversion
		// is
		// deliberately lossy: these stay null, and request mode is explicitly cleared so
		// the merge target's configured mode is not overridden by a generic runtime call.
		ChatOptions portable = ChatOptions.builder().model(MODEL).temperature(0.4).build();

		OpenRouterChatOptions converted = OpenRouterChatOptions.fromOptions(portable);

		assertThat(converted.getModels()).isNull();
		assertThat(converted.getProvider()).isNull();
		assertThat(converted.getReasoning()).isNull();
		assertThat(converted.getServiceTier()).isNull();
		assertThat(converted.getRoute()).isNull();
		assertThat(converted.getRequestMode()).isNull();
	}

	@Test
	void fromOptionsWithOpenRouterOptionsReturnsAnIndependentCopy() {
		OpenRouterChatOptions original = OpenRouterChatOptions.builder()
			.model(MODEL)
			.requestMode(OpenRouterRequestMode.OPENAI_RESPONSES)
			.stopSequences(List.of("END"))
			.build();

		OpenRouterChatOptions converted = OpenRouterChatOptions.fromOptions(original);
		converted = converted.mutate().stopSequences(List.of("END", "STOP")).build();

		// Passing OpenRouter options through fromOptions preserves everything (no loss)
		// and
		// returns a copy, not the same instance.
		assertThat(converted.getRequestMode()).isEqualTo(OpenRouterRequestMode.OPENAI_RESPONSES);
		assertThat(converted).isNotSameAs(original);
		assertThat(original.getStopSequences()).containsExactly("END");
		assertThat(converted.getStopSequences()).containsExactly("END", "STOP");
	}

	@Test
	void fromOptionsPreservesPortableToolFields() {
		ToolCallback weather = tool("get_weather");
		ToolCallingChatOptions portable = ToolCallingChatOptions.builder()
			.model(MODEL)
			.toolCallbacks(weather)
			.toolContext(Map.of("tenant", "acme"))
			.build();

		OpenRouterChatOptions converted = OpenRouterChatOptions.fromOptions(portable);

		// The portable ToolCallingChatOptions interface only carries toolCallbacks and
		// toolContext, so those two are the entire preservation contract.
		assertThat(converted.getToolCallbacks()).containsExactly(weather);
		assertThat(converted.getToolContext()).containsEntry("tenant", "acme");
	}

}
