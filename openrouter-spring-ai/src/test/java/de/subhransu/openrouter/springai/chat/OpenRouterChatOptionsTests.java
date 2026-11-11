package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterResponsesRequestMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.ObjectMapper;

class OpenRouterChatOptionsTests {

	static Stream<Arguments> toolStrictCompositions() {
		return Stream.of(Arguments.of(Named.of("unset defaults, unset request", null), null, null),
				Arguments.of(Named.of("unset defaults, strict request", null), true, true),
				Arguments.of(Named.of("unset defaults, lenient request", null), false, false),
				Arguments.of(Named.of("strict defaults, unset request", true), null, true),
				Arguments.of(Named.of("strict defaults, strict request", true), true, true),
				Arguments.of(Named.of("strict defaults, lenient request", true), false, false),
				Arguments.of(Named.of("lenient defaults, unset request", false), null, false),
				Arguments.of(Named.of("lenient defaults, strict request", false), true, true),
				Arguments.of(Named.of("lenient defaults, lenient request", false), false, false));
	}

	@ParameterizedTest
	@MethodSource("toolStrictCompositions")
	void toolStrictRetainsTriStateAcrossCopiesAndComposition(Boolean defaults, Boolean runtime, Boolean expected) {
		var options = OpenRouterChatOptions.builder().toolStrict(defaults).build();
		var request = OpenRouterChatOptions.builder().toolStrict(runtime);

		assertThat(OpenRouterChatOptions.fromOptions(options).getToolStrict()).isEqualTo(defaults);
		assertThat(options.mutate().clone().build().getToolStrict()).isEqualTo(defaults);
		assertThat(options.merge(request.build()).getToolStrict()).isEqualTo(expected);
		assertThat(options.mutate().combineWith(request).build().getToolStrict()).isEqualTo(expected);
		assertThat(options.getToolStrict()).as("sources stay unchanged by composition").isEqualTo(defaults);
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void combineAppendsCollectionsAndOverridesScalarsWithoutMutatingSources(boolean portable) {
		ToolCallback first = mock(ToolCallback.class);
		ToolCallback second = mock(ToolCallback.class);
		var defaults = OpenRouterChatOptions.builder()
			.model("synthetic-default")
			.temperature(0.2)
			.route("fallback")
			.toolCallbacks(first)
			.stopSequences(List.of("END"))
			.toolContext(Map.of("shared", "default", "defaultOnly", true));
		ToolCallingChatOptions.Builder<?> request = portable ? ToolCallingChatOptions.builder()
				: OpenRouterChatOptions.builder();
		request.model("synthetic-request")
			.temperature(0.8)
			.toolCallbacks(second)
			.stopSequences(List.of("END", "STOP"))
			.toolContext(Map.of("shared", "request", "requestOnly", true));
		var snapshot = defaults.build();
		var combined = defaults.clone().combineWith(request).build();

		assertThat(combined.getToolCallbacks()).containsExactly(first, second);
		assertThat(combined.getStopSequences()).contains("STOP");
		assertThat(combined.getToolContext())
			.containsExactlyInAnyOrderEntriesOf(Map.of("shared", "request", "defaultOnly", true, "requestOnly", true));
		assertThat(combined.getModel()).isEqualTo("synthetic-request");
		assertThat(combined.getTemperature()).isEqualTo(0.8);
		assertThat(combined.getRoute()).isEqualTo("fallback");
		request.toolCallbacks(first).stopSequences(List.of("LATER")).toolContext("later", true);
		assertThat(combined.getToolCallbacks()).containsExactly(first, second);
		assertThat(combined.getToolContext()).doesNotContainKey("later");
		assertThat(defaults.build()).usingRecursiveComparison().isEqualTo(snapshot);
	}

	@Test
	void combineKeepsDuplicateStopSequencesBecauseSpringAiDoesNotDeduplicate() {
		// Pins Spring AI's non-deduplicating combineWith; a failure after a Spring AI
		// upgrade means that upstream semantics changed, not that this library broke.
		var defaults = OpenRouterChatOptions.builder().stopSequences(List.of("END"));
		var request = OpenRouterChatOptions.builder().stopSequences(List.of("END", "STOP"));

		assertThat(defaults.combineWith(request).build().getStopSequences()).containsExactly("END", "END", "STOP");
	}

	@ParameterizedTest
	@NullAndEmptySource
	void combinePreservesDefaultsForNullOrEmptyCollections(List<String> stops) {
		ToolCallback tool = mock(ToolCallback.class);
		var defaults = OpenRouterChatOptions.builder()
			.toolCallbacks(tool)
			.stopSequences(List.of("END"))
			.toolContext(Map.of("default", true));
		var empty = OpenRouterChatOptions.builder()
			.stopSequences(stops)
			.toolCallbacks(stops == null ? null : List.<ToolCallback>of())
			.toolContext(stops == null ? null : Map.of());
		var combined = defaults.clone().combineWith(empty).build();
		assertThat(combined.getToolCallbacks()).containsExactly(tool);
		assertThat(combined.getStopSequences()).containsExactly("END");
		assertThat(combined.getToolContext()).containsEntry("default", true);
		assertThat(empty.combineWith(defaults).build()).usingRecursiveComparison().isEqualTo(combined);
	}

	@Test
	void repeatedToolCallbackSettersAppendAndEmptyVarargsKeepThem() {
		ToolCallback first = mock(ToolCallback.class);
		ToolCallback second = mock(ToolCallback.class);
		var builder = OpenRouterChatOptions.builder().toolCallbacks(first).toolCallbacks(second).toolCallbacks();
		var snapshot = builder.build();
		assertThat(snapshot.getToolCallbacks()).containsExactly(first, second);
		assertThat(builder.toolCallbacks(first).build().getToolCallbacks()).containsExactly(first, second, first);
		assertThat(snapshot.getToolCallbacks()).containsExactly(first, second);
		assertThatThrownBy(() -> builder.toolCallbacks((ToolCallback[]) null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void toolCallbackListSetterReplacesAndNullClears() {
		ToolCallback first = mock(ToolCallback.class);
		ToolCallback second = mock(ToolCallback.class);
		var builder = OpenRouterChatOptions.builder().toolCallbacks(first);
		assertThat(builder.toolCallbacks(List.of(second)).build().getToolCallbacks()).containsExactly(second);
		assertThat(builder.toolCallbacks(List.of()).build().getToolCallbacks()).isEmpty();
		assertThat(builder.toolCallbacks((List<ToolCallback>) null).build().getToolCallbacks()).isNull();
	}

	@Test
	void toolContextMapSettersMergeWithLaterValuesWinningAndNullClears() {
		var builder = OpenRouterChatOptions.builder()
			.toolContext(Map.of("first", true, "shared", "old"))
			.toolContext(Map.of("second", true, "shared", "new"));
		assertThat(builder.build().getToolContext())
			.containsExactlyInAnyOrderEntriesOf(Map.of("first", true, "second", true, "shared", "new"));
		assertThat(builder.toolContext(null).build().getToolContext()).isNull();
		assertThat(builder.toolContext(Map.of("fresh", true)).build().getToolContext()).containsOnlyKeys("fresh");
	}

	@Test
	void nullRuntimeOptionInheritsEvenWhenSwitchingRequestMode() {
		var defaults = OpenRouterChatOptions.builder().seed(7).build();
		var runtime = OpenRouterChatOptions.builder()
			.requestMode(OpenRouterRequestMode.OPENAI_RESPONSES)
			.seed(null)
			.build();
		var merged = defaults.merge(runtime);
		assertThat(merged.getSeed()).isEqualTo(7);
		assertThat(merged.getRequestMode()).isEqualTo(OpenRouterRequestMode.OPENAI_RESPONSES);
		assertThatThrownBy(
				() -> new OpenRouterResponsesRequestMapper(new ObjectMapper()).map(List.of(), merged, false, List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("seed");
	}

}
