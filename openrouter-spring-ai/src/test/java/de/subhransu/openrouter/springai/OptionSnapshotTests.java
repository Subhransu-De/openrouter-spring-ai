package de.subhransu.openrouter.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingModel;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingOptions;
import de.subhransu.openrouter.springai.image.OpenRouterImageModel;
import de.subhransu.openrouter.springai.image.OpenRouterImageOptions;
import de.subhransu.openrouter.springai.support.OptionSnapshots;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

class OptionSnapshotTests {

	@Tag("api")
	@Tag("api.options")
	@Test
	void emptyMapSnapshotsRetainNullQueriesAndRejectAllMutations() {
		var input = new LinkedHashMap<String, Object>();
		var snapshot = OptionSnapshots.map(input);
		input.put("later", true);
		assertThat(snapshot).isEmpty();
		assertThat(snapshot.containsKey(null)).isFalse();
		assertThat(snapshot.containsValue(null)).isFalse();
		assertThat(snapshot.get(null)).isNull();
		assertThatThrownBy(() -> snapshot.remove("absent")).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> snapshot.put("new", true)).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(snapshot::clear).isInstanceOf(UnsupportedOperationException.class);
	}

	@Tag("api")
	@Tag("api.options")
	@Test
	void mapSnapshotsRetainInsertionOrderAndNullableEntries() {
		for (int size : List.of(1, 2, 3, 8, 9)) {
			var input = new LinkedHashMap<String, Object>();
			input.put(null, null);
			for (int index = 1; index < size; index++) {
				input.put("key" + index, index);
			}
			var snapshot = OptionSnapshots.map(input);
			assertThat(snapshot).isEqualTo(input);
			assertThat(snapshot.keySet()).containsExactlyElementsOf(input.keySet());
			input.clear();
			assertThat(snapshot).hasSize(size).containsEntry(null, null);
			assertThatThrownBy(snapshot::clear).isInstanceOf(UnsupportedOperationException.class);
		}
		assertThat(OptionSnapshots.map(null)).isNull();
	}

	@Tag("api")
	@Tag("api.options")
	@Test
	void listSnapshotsRetainNullsOrderDuplicatesAndOpaqueIdentity() {
		Object opaque = new Object();
		var input = new ArrayList<Object>();
		input.add(opaque);
		input.add(null);
		input.add(opaque);
		var snapshot = OptionSnapshots.list(input);
		input.clear();
		assertThat(snapshot).containsExactly(opaque, null, opaque);
		assertThat(snapshot.get(0)).isSameAs(opaque);
		assertThatThrownBy(() -> snapshot.set(0, new Object())).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> snapshot.add(opaque)).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(snapshot::clear).isInstanceOf(UnsupportedOperationException.class);
		assertThat(OptionSnapshots.list(null)).isNull();
		var empty = OptionSnapshots.list(new ArrayList<>());
		assertThat(empty).isEmpty();
		assertThatThrownBy(() -> empty.add(opaque)).isInstanceOf(UnsupportedOperationException.class);
	}

	@Tag("api")
	@Tag("api.options")
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
		var empty = (List<?>) snapshot.get(2);
		assertThat(empty).isEmpty();
		assertThat(empty.contains(null)).isFalse();
		assertThatThrownBy(empty::clear).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(snapshot::clear).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(child::clear).isInstanceOf(UnsupportedOperationException.class);
	}

	@Tag("api")
	@Tag("api.options")
	@Test
	void providerListsStayDetachedAcrossModelAndRequestSnapshots() {
		var routes = new ArrayList<>(List.of("synthetic-route"));
		var provider = new OpenRouterProviderPreferences(true, false, null, routes, routes, routes, null);
		var options = OpenRouterChatOptions.builder().provider(provider).build();
		var model = OpenRouterChatModel.builder().openRouterApi(mock(OpenRouterApi.class)).options(options).build();
		routes.clear();
		for (var snapshot : List.of(options, options.mutate().build(), options.merge(null),
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

	@Tag("api")
	@Tag("api.options")
	@Test
	void modelsReturnTypedCopiesOfTheirDefaultOptions() {
		// Typed getters let callers derive per-request options with OpenRouter-only
		// fields without a cast, as the official provider models allow.
		OpenRouterApi api = mock(OpenRouterApi.class);
		var chatDefaults = OpenRouterChatOptions.builder().model("synthetic/chat").build();
		OpenRouterChatOptions chat = OpenRouterChatModel.builder()
			.openRouterApi(api)
			.options(chatDefaults)
			.build()
			.getOptions();
		var embeddingDefaults = OpenRouterEmbeddingOptions.builder().model("synthetic/embedding").build();
		OpenRouterEmbeddingOptions embedding = OpenRouterEmbeddingModel.builder()
			.openRouterApi(api)
			.options(embeddingDefaults)
			.build()
			.getOptions();
		var imageDefaults = OpenRouterImageOptions.builder().model("synthetic/image").build();
		OpenRouterImageOptions image = OpenRouterImageModel.builder()
			.openRouterApi(api)
			.options(imageDefaults)
			.build()
			.getOptions();
		assertThat(chat.getModel()).isEqualTo("synthetic/chat");
		assertThat(embedding.getModel()).isEqualTo("synthetic/embedding");
		assertThat(image.getModel()).isEqualTo("synthetic/image");
		assertThat(chat).isNotSameAs(chatDefaults);
		assertThat(embedding).isNotSameAs(embeddingDefaults);
		assertThat(image).isNotSameAs(imageDefaults);
	}

	@SuppressWarnings("removal") // The v0.1.0 names must keep working until 0.3.0.
	@Tag("api")
	@Tag("api.options")
	@Test
	void deprecatedBuilderNamesStillSetTheSameOptions() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		OpenRouterChatOptions chat = OpenRouterChatModel.builder()
			.openRouterApi(api)
			.defaultOptions(OpenRouterChatOptions.builder().model("synthetic/old-chat").build())
			.build()
			.getOptions();
		OpenRouterEmbeddingOptions embedding = OpenRouterEmbeddingModel.builder()
			.openRouterApi(api)
			.defaultOptions(OpenRouterEmbeddingOptions.builder().model("synthetic/old-embedding").build())
			.build()
			.getOptions();
		OpenRouterImageOptions image = OpenRouterImageModel.builder()
			.openRouterApi(api)
			.defaultOptions(OpenRouterImageOptions.builder().model("synthetic/old-image").build())
			.build()
			.getOptions();
		OpenRouterImageOptions imageOptions = OpenRouterImageOptions.builder()
			.providerOptions(Map.of("options", Map.of("synthetic", Map.of("quality", "low"))))
			.build();

		assertThat(chat.getModel()).isEqualTo("synthetic/old-chat");
		assertThat(embedding.getModel()).isEqualTo("synthetic/old-embedding");
		assertThat(image.getModel()).isEqualTo("synthetic/old-image");
		assertThat(imageOptions.getProviderOptions()).isEqualTo(imageOptions.getProvider())
			.isEqualTo(Map.of("options", Map.of("synthetic", Map.of("quality", "low"))));
	}

	@Tag("api")
	@Tag("api.options")
	@Test
	void nestedContainersAreOwnedAcrossChatCopyMergeMutateAndBuilderReuse() {
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
		var copy = options.mutate().build();
		var merged = OpenRouterChatOptions.builder().metadata(Map.of("default", true)).build().merge(options);
		var mutated = OpenRouterChatOptions.builder()
			.build()
			.mutate()
			.toolContext(input)
			.toolCallbacks(callbacks)
			.build();
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
		assertNestedSnapshot(mutated.getToolContext());
		assertThat(mutated.getToolCallbacks()).containsExactly(callback);
	}

	@Tag("multimodal")
	@Tag("multimodal.image-model")
	@Test
	void imageCollectionsAreOwnedAndGettersCannotMutateSnapshots() {
		var references = new ArrayList<>(List.of("https://example.com/synthetic.png"));
		var values = new ArrayList<Object>();
		values.add("original");
		values.add(null);
		var input = new LinkedHashMap<String, Object>();
		input.put("nested", new LinkedHashMap<>(Map.of("values", values)));
		var builder = OpenRouterImageOptions.builder().inputReferences(references).provider(input);
		var options = builder.build();
		references.clear();
		values.clear();
		input.clear();
		builder.inputReferences(List.of()).provider(Map.of());
		for (var snapshot : List.of(options, options.mutate().build(), options.merge(null))) {
			assertThat(snapshot.getInputReferences()).containsExactly("https://example.com/synthetic.png");
			assertThatThrownBy(() -> snapshot.getInputReferences().clear())
				.isInstanceOf(UnsupportedOperationException.class);
			assertNestedSnapshot(snapshot.getProvider());
		}
	}

	@Tag("embeddings")
	@Tag("embeddings.options")
	@Test
	void embeddingBuildsAreIndependentInBothDirections() {
		var builder = OpenRouterEmbeddingOptions.builder()
			.model("first")
			.dimensions(8)
			.encodingFormat("float")
			.user("synthetic");
		var first = builder.build();
		var copy = first.mutate().build();
		var second = builder.model("second").dimensions(16).encodingFormat("base64").user("other").build();
		var changed = first.mutate().model("changed").dimensions(32).encodingFormat("changed").user("changed").build();
		assertThat(builder.build()).usingRecursiveComparison().isEqualTo(second);
		assertThat(first).usingRecursiveComparison().isEqualTo(copy);
		assertThat(changed.getModel()).isEqualTo("changed");
		assertThat(first.getModel()).isEqualTo("first");
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
