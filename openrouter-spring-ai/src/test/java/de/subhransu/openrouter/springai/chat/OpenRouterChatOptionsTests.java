package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

class OpenRouterChatOptionsTests {

	private static final String MODEL = "openai/gpt-5.4-mini";

	@Test
	void toolStrictRetainsTriStateAcrossCopiesAndComposition() {
		for (Boolean defaults : new Boolean[] { null, false, true }) {
			var options = OpenRouterChatOptions.builder().toolStrict(defaults).build();
			assertThat(OpenRouterChatOptions.fromOptions(options).getToolStrict()).isEqualTo(defaults);
			assertThat(options.mutate().clone().build().getToolStrict()).isEqualTo(defaults);
			for (Boolean runtime : new Boolean[] { null, false, true }) {
				Boolean expected = runtime != null ? runtime : defaults;
				var request = OpenRouterChatOptions.builder().toolStrict(runtime);
				assertThat(options.merge(request.build()).getToolStrict()).isEqualTo(expected);
				assertThat(options.mutate().combineWith(request).build().getToolStrict()).isEqualTo(expected);
				assertThat(options.getToolStrict()).isEqualTo(defaults);
			}
		}
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
		assertThat(combined.getStopSequences()).containsExactly("END", "END", "STOP");
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
	void repeatedSettersAppendOrReplaceAccordingToSpringAiContract() {
		ToolCallback first = mock(ToolCallback.class);
		ToolCallback second = mock(ToolCallback.class);
		var builder = OpenRouterChatOptions.builder()
			.toolCallbacks(first)
			.toolCallbacks(second)
			.toolCallbacks()
			.toolContext(Map.of("first", true, "shared", "old"))
			.toolContext(Map.of("second", true, "shared", "new"));
		var snapshot = builder.build();
		assertThat(snapshot.getToolCallbacks()).containsExactly(first, second);
		assertThat(snapshot.getToolContext())
			.containsExactlyInAnyOrderEntriesOf(Map.of("first", true, "second", true, "shared", "new"));
		assertThat(builder.toolCallbacks(List.of(second)).build().getToolCallbacks()).containsExactly(second);
		assertThat(builder.toolCallbacks(List.of()).build().getToolCallbacks()).isEmpty();
		assertThat(builder.toolCallbacks((List<ToolCallback>) null).build().getToolCallbacks()).isNull();
		assertThat(builder.toolCallbacks(first).build().getToolCallbacks()).containsExactly(first);
		assertThat(builder.toolContext(null).build().getToolContext()).isNull();
		assertThat(builder.toolContext(Map.of("fresh", true)).build().getToolContext()).containsOnlyKeys("fresh");
		assertThat(snapshot.getToolCallbacks()).containsExactly(first, second);
		assertThatThrownBy(() -> builder.toolCallbacks((ToolCallback[]) null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(builder.stopSequences(List.of("END")).stopSequences(List.of("STOP")).build().getStopSequences())
			.containsExactly("STOP");
	}

	@Test
	void mergeKeepsDefaultsWhenRuntimeOptionsDoNotSetOpenRouterFields() {
		OpenRouterChatOptions defaults = OpenRouterChatOptions.builder()
			.model(MODEL)
			.models(List.of("anthropic/claude-3.5-sonnet"))
			.temperature(0.2)
			.route("fallback")
			.build();

		OpenRouterChatOptions runtime = OpenRouterChatOptions.builder().requestMode(null).temperature(0.8).build();

		OpenRouterChatOptions merged = defaults.merge(runtime);

		assertThat(merged.getModel()).isEqualTo(MODEL);
		assertThat(merged.getModels()).containsExactly("anthropic/claude-3.5-sonnet");
		assertThat(merged.getTemperature()).isEqualTo(0.8);
		assertThat(merged.getRoute()).isEqualTo("fallback");
		assertThat(merged.getRequestMode()).isEqualTo(defaults.getRequestMode());
	}

	@Test
	void collectionGettersDoNotExposeMutableInternalState() {
		OpenRouterChatOptions options = OpenRouterChatOptions.builder().stopSequences(List.of("END")).build();

		OpenRouterChatOptions copy = options.copy();

		assertThatThrownBy(() -> copy.getStopSequences().add("STOP")).isInstanceOf(UnsupportedOperationException.class);
		assertThat(options.getStopSequences()).containsExactly("END");
		assertThat(copy.getStopSequences()).containsExactly("END");
	}

}
