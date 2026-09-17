package de.subhransu.openrouter.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingOptions;
import de.subhransu.openrouter.springai.image.OpenRouterImageOptions;
import de.subhransu.openrouter.springai.support.OptionSnapshots;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

class OptionSnapshotTests {

	@Test
	void recursiveListsRetainNullsOrderAndOpaqueIdentity() {
		Object opaque = new Object();
		var nested = new ArrayList<>(List.of(opaque));
		var input = new ArrayList<Object>();
		input.add(null);
		input.add(nested);
		input.add(List.of());
		var snapshot = (List<?>) OptionSnapshots.value(input);
		input.clear();
		nested.clear();
		assertThat(snapshot).hasSize(3);
		assertThat(snapshot.get(0)).isNull();
		var child = (List<?>) snapshot.get(1);
		assertThat(child).hasSize(1);
		assertThat(child.get(0)).isSameAs(opaque);
		assertThat((List<?>) snapshot.get(2)).isEmpty();
		assertThatThrownBy(snapshot::clear).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(child::clear).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void providerListsStayDetachedAcrossModelAndRequestSnapshots() {
		var routes = new ArrayList<>(List.of("synthetic-route"));
		var provider = new OpenRouterProviderPreferences(true, false, null, routes, routes, routes, null);
		var options = OpenRouterChatOptions.builder().provider(provider).build();
		var model = OpenRouterChatModel.builder()
			.openRouterApi(mock(OpenRouterApi.class))
			.defaultOptions(options)
			.build();
		routes.clear();
		for (var snapshot : List.of(options, options.<OpenRouterChatOptions>copy(), options.merge(null),
				(OpenRouterChatOptions) model.getOptions())) {
			for (var values : List.of(snapshot.getProvider().order(), snapshot.getProvider().ignore(),
					snapshot.getProvider().quantizations())) {
				assertThat(values).containsExactly("synthetic-route");
				assertThatThrownBy(values::clear).isInstanceOf(UnsupportedOperationException.class);
			}
		}
		assertThat(((OpenRouterChatOptions) model.getOptions()).getProvider().order())
			.containsExactly("synthetic-route");
		assertThat(new OpenRouterProviderPreferences(null, null, null, null, List.of(), null, null).order()).isNull();
	}

	@Test
	void nestedContainersAreOwnedAcrossChatCopyMergeSettersAndBuilderReuse() {
		var values = new ArrayList<Object>();
		values.add("original");
		values.add(null);
		var nested = new LinkedHashMap<String, Object>();
		nested.put("values", values);
		var input = new LinkedHashMap<String, Object>();
		input.put("nested", nested);
		ToolCallback callback = mock(ToolCallback.class);
		var callbacks = new ArrayList<>(List.of(callback));
		var builder = OpenRouterChatOptions.builder()
			.metadata(input)
			.imageConfig(input)
			.toolChoice(input)
			.toolContext(input)
			.toolContext("callback", callback)
			.toolCallbacks(callbacks);
		var options = builder.build();
		var copy = options.<OpenRouterChatOptions>copy();
		var merged = OpenRouterChatOptions.builder().metadata(Map.of("default", true)).build().merge(options);
		var setterOptions = OpenRouterChatOptions.builder().build();
		setterOptions.setToolContext(input);
		setterOptions.setToolCallbacks(callbacks);
		values.clear();
		nested.clear();
		input.clear();
		callbacks.clear();
		builder.metadata(Map.of()).imageConfig(Map.of()).toolChoice("none").toolContext(null).toolCallbacks(List.of());
		for (var snapshot : List.of(options, copy, merged)) {
			assertNestedSnapshot(snapshot.getMetadata());
			assertNestedSnapshot(snapshot.getImageConfig());
			assertNestedSnapshot((Map<?, ?>) snapshot.getToolChoice());
			assertNestedSnapshot(snapshot.getToolContext());
			assertThat(snapshot.getToolContext().get("callback")).isSameAs(callback);
			assertThat(snapshot.getToolCallbacks()).containsExactly(callback);
		}
		assertNestedSnapshot(setterOptions.getToolContext());
		assertThat(setterOptions.getToolCallbacks()).containsExactly(callback);
	}

	@Test
	void imageCollectionsAreOwnedAndGettersCannotMutateSnapshots() {
		var references = new ArrayList<>(List.of("https://example.com/synthetic.png"));
		var values = new ArrayList<Object>();
		values.add("original");
		values.add(null);
		var input = new LinkedHashMap<String, Object>();
		input.put("nested", new LinkedHashMap<>(Map.of("values", values)));
		var builder = OpenRouterImageOptions.builder().inputReferences(references).providerOptions(input);
		var options = builder.build();
		references.clear();
		values.clear();
		input.clear();
		builder.inputReferences(List.of()).providerOptions(Map.of());
		for (var snapshot : List.of(options, options.copy(), options.merge(null))) {
			assertThat(snapshot.getInputReferences()).containsExactly("https://example.com/synthetic.png");
			assertThatThrownBy(() -> snapshot.getInputReferences().clear())
				.isInstanceOf(UnsupportedOperationException.class);
			assertNestedSnapshot(snapshot.getProviderOptions());
		}
	}

	@Test
	void embeddingBuildsAreIndependentInBothDirections() {
		var builder = OpenRouterEmbeddingOptions.builder()
			.model("first")
			.dimensions(8)
			.encodingFormat("float")
			.user("synthetic");
		var first = builder.build();
		var copy = first.copy();
		var second = builder.model("second").dimensions(16).encodingFormat("base64").user("other").build();
		assertThat(first).usingRecursiveComparison().isEqualTo(copy);
		first.setModel("changed");
		first.setDimensions(32);
		first.setEncodingFormat("changed");
		first.setUser("changed");
		assertThat(builder.build()).usingRecursiveComparison().isEqualTo(second);
		assertThat(copy.getModel()).isEqualTo("first");
	}

	private static void assertNestedSnapshot(Map<?, ?> snapshot) {
		var nested = (Map<?, ?>) snapshot.get("nested");
		var values = (List<?>) nested.get("values");
		assertThat(values).isEqualTo(java.util.Arrays.asList("original", null));
		assertThatThrownBy(snapshot::clear).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(nested::clear).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(values::clear).isInstanceOf(UnsupportedOperationException.class);
	}

}
