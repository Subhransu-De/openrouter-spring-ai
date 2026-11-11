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
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.tool.ToolCallback;

class OptionSnapshotTests {

	@ParameterizedTest(name = "map of size {0}")
	@ValueSource(ints = { 0, 1, 2, 3, 8, 9 })
	void mapSnapshotsRetainInsertionOrderAndNullableEntries(int size) {
		var input = new LinkedHashMap<String, Object>();
		if (size > 0) {
			input.put(null, null);
		}
		for (int index = 1; index < size; index++) {
			input.put("key" + index, index);
		}
		var snapshot = OptionSnapshots.map(input);
		assertThat(snapshot).isEqualTo(input);
		assertThat(snapshot.keySet()).containsExactlyElementsOf(input.keySet());
		var expectedKeys = new ArrayList<>(input.keySet());
		input.clear();
		assertThat(snapshot).hasSize(size);
		assertThat(snapshot.keySet()).containsExactlyElementsOf(expectedKeys);
		assertThat(snapshot.containsKey(null)).isEqualTo(size > 0);
		assertThat(snapshot.containsValue(null)).isEqualTo(size > 0);
		assertThatThrownBy(() -> snapshot.put("new", true)).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> snapshot.remove("absent")).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(snapshot::clear).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void nullMapSnapshotStaysNull() {
		assertThat(OptionSnapshots.map(null)).isNull();
	}

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

	@Test
	void nestedListValuesAreDetachedImmutableAndKeepOpaqueIdentity() {
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

	static Stream<Arguments> providerListSnapshots() {
		List<Named<Function<ProviderFixture, OpenRouterChatOptions>>> snapshots = List.of(
				Named.of("built options", ProviderFixture::options),
				Named.of("mutate().build()", fixture -> fixture.options().mutate().build()),
				Named.of("merge(null)", fixture -> fixture.options().merge(null)),
				Named.of("model default options", fixture -> (OpenRouterChatOptions) fixture.model().getOptions()));
		List<Named<Function<OpenRouterProviderPreferences, List<String>>>> lists = List.of(
				Named.of("order", OpenRouterProviderPreferences::order),
				Named.of("ignore", OpenRouterProviderPreferences::ignore),
				Named.of("quantizations", OpenRouterProviderPreferences::quantizations));
		return snapshots.stream().flatMap(snapshot -> lists.stream().map(list -> Arguments.of(snapshot, list)));
	}

	@ParameterizedTest(name = "{0}: {1}")
	@MethodSource("providerListSnapshots")
	void providerListsStayDetachedAndImmutableAcrossModelAndRequestSnapshots(
			Function<ProviderFixture, OpenRouterChatOptions> snapshot,
			Function<OpenRouterProviderPreferences, List<String>> list) {
		var fixture = ProviderFixture.withClearedSource();

		List<String> values = list.apply(snapshot.apply(fixture).getProvider());

		assertThat(values).containsExactly("synthetic-route");
		assertThatThrownBy(values::clear).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void absentProviderListsStayNullInsteadOfBecomingEmptySnapshots() {
		var preferences = new OpenRouterProviderPreferences(null, null, null, null, List.of(), null, null);

		assertThat(preferences.order()).isNull();
		assertThat(preferences.quantizations()).isNull();
		assertThat(preferences.ignore()).isEmpty();
		assertThatThrownBy(() -> preferences.ignore().add("late")).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void modelsReturnTypedCopiesOfTheirDefaultOptions() {
		// Typed getters let callers derive per-request options with OpenRouter-only
		// fields without a cast, as the official provider models allow.
		OpenRouterApi api = mock(OpenRouterApi.class);
		var chatDefaults = OpenRouterChatOptions.builder().model("synthetic/chat").build();
		OpenRouterChatOptions chat = OpenRouterChatModel.builder()
			.openRouterApi(api)
			.defaultOptions(chatDefaults)
			.build()
			.getOptions();
		var embeddingDefaults = OpenRouterEmbeddingOptions.builder().model("synthetic/embedding").build();
		OpenRouterEmbeddingOptions embedding = OpenRouterEmbeddingModel.builder()
			.openRouterApi(api)
			.defaultOptions(embeddingDefaults)
			.build()
			.getOptions();
		var imageDefaults = OpenRouterImageOptions.builder().model("synthetic/image").build();
		OpenRouterImageOptions image = OpenRouterImageModel.builder()
			.openRouterApi(api)
			.defaultOptions(imageDefaults)
			.build()
			.getOptions();
		assertThat(chat.getModel()).isEqualTo("synthetic/chat");
		assertThat(embedding.getModel()).isEqualTo("synthetic/embedding");
		assertThat(image.getModel()).isEqualTo("synthetic/image");
		assertThat(chat).isNotSameAs(chatDefaults).usingRecursiveComparison().isEqualTo(chatDefaults);
		assertThat(embedding).isNotSameAs(embeddingDefaults).usingRecursiveComparison().isEqualTo(embeddingDefaults);
		assertThat(image).isNotSameAs(imageDefaults).usingRecursiveComparison().isEqualTo(imageDefaults);
	}

	static Stream<Named<Function<NestedFixture, OpenRouterChatOptions>>> nestedChatSnapshots() {
		return Stream.of(Named.of("built options", NestedFixture::options),
				Named.of("mutate().build()", NestedFixture::copy), Named.of("merge(options)", NestedFixture::merged));
	}

	@ParameterizedTest
	@MethodSource("nestedChatSnapshots")
	void nestedContainersAreOwnedAcrossChatCopyMergeAndBuilderReuse(
			Function<NestedFixture, OpenRouterChatOptions> variant) {
		var fixture = NestedFixture.withClearedSources();
		var snapshot = variant.apply(fixture);

		assertNestedSnapshot(snapshot.getMetadata());
		assertNestedSnapshot(snapshot.getImageConfig());
		assertNestedSnapshot((Map<?, ?>) snapshot.getToolChoice());
		assertNestedSnapshot(snapshot.getToolContext());
		assertThat(snapshot.getToolContext().get("callback")).isSameAs(fixture.callback());
		assertThat(snapshot.getToolCallbacks()).containsExactly(fixture.callback());
	}

	@Test
	void nestedContainersAreOwnedWhenSetOnMutatedOptions() {
		var fixture = NestedFixture.withClearedSources();

		assertNestedSnapshot(fixture.mutated().getToolContext());
		assertThat(fixture.mutated().getToolCallbacks()).containsExactly(fixture.callback());
	}

	@Test
	void editsAfterBuildOrMutateStayInTheirOwnSnapshot() {
		ToolCallback original = mock(ToolCallback.class);
		ToolCallback replacement = mock(ToolCallback.class);
		var builder = OpenRouterChatOptions.builder()
			.model("synthetic/model")
			.stopSequences(new ArrayList<>(List.of("END")))
			.toolCallbacks(original)
			.toolContext(Map.of("first", true));
		var source = builder.build();
		var callbacks = source.getToolCallbacks();
		var context = source.getToolContext();
		var stops = source.getStopSequences();
		builder.temperature(0.9).toolContext("builderOnly", true);
		var copy = source.mutate().build();
		var changed = copy.mutate()
			.stopSequences(List.of("END", "STOP"))
			.toolCallbacks(List.of(replacement))
			.toolContext(null)
			.toolContext("copyOnly", true)
			.build();
		assertThat(source.getTemperature()).isNull();
		assertThat(builder.build().getTemperature()).isEqualTo(0.9);
		assertThat(source.getToolContext()).containsOnlyKeys("first");
		assertThat(builder.build().getToolContext()).containsOnlyKeys("first", "builderOnly");
		assertThat(copy).usingRecursiveComparison().ignoringFields("toolCallbacks").isEqualTo(source);
		assertThat(copy.getToolCallbacks()).containsExactly(original);
		assertThat(changed.getStopSequences()).containsExactly("END", "STOP");
		assertThat(changed.getToolCallbacks()).containsExactly(replacement);
		assertThat(changed.getToolContext()).containsOnlyKeys("copyOnly");
		assertThat(callbacks).containsExactly(original);
		assertThat(context).containsOnlyKeys("first");
		assertThat(stops).containsExactly("END");
		assertThat(source.getStopSequences()).containsExactly("END");
		assertThatThrownBy(() -> stops.add("STOP")).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> callbacks.add(replacement)).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> context.put("later", true)).isInstanceOf(UnsupportedOperationException.class);
	}

	static Stream<Named<Function<ImageFixture, OpenRouterImageOptions>>> imageSnapshots() {
		return Stream.of(Named.of("built options", ImageFixture::options),
				Named.of("mutate().build()", ImageFixture::copy), Named.of("merge(null)", ImageFixture::merged));
	}

	@ParameterizedTest
	@MethodSource("imageSnapshots")
	void imageCollectionsAreOwnedAndGettersCannotMutateSnapshots(
			Function<ImageFixture, OpenRouterImageOptions> variant) {
		var snapshot = variant.apply(ImageFixture.withClearedSources());

		assertThat(snapshot.getInputReferences()).containsExactly("https://example.com/synthetic.png");
		assertThatThrownBy(() -> snapshot.getInputReferences().clear())
			.isInstanceOf(UnsupportedOperationException.class);
		assertNestedSnapshot(snapshot.getProviderOptions());
	}

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
		var expectedFirst = OpenRouterEmbeddingOptions.builder()
			.model("first")
			.dimensions(8)
			.encodingFormat("float")
			.user("synthetic")
			.build();
		assertThat(first).usingRecursiveComparison().isEqualTo(expectedFirst);
		assertThat(copy).usingRecursiveComparison().isEqualTo(expectedFirst);
		assertThat(second).usingRecursiveComparison()
			.isEqualTo(OpenRouterEmbeddingOptions.builder()
				.model("second")
				.dimensions(16)
				.encodingFormat("base64")
				.user("other")
				.build());
		assertThat(changed).usingRecursiveComparison()
			.isEqualTo(OpenRouterEmbeddingOptions.builder()
				.model("changed")
				.dimensions(32)
				.encodingFormat("changed")
				.user("changed")
				.build());
	}

	private static void assertNestedSnapshot(Map<?, ?> snapshot) {
		var nested = (Map<?, ?>) snapshot.get("nested");
		var values = (List<?>) nested.get("values");
		assertThat(values).isEqualTo(java.util.Arrays.asList("original", null));
		assertThatThrownBy(snapshot::clear).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(nested::clear).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(values::clear).isInstanceOf(UnsupportedOperationException.class);
	}

	private record ProviderFixture(OpenRouterChatOptions options, OpenRouterChatModel model) {

		static ProviderFixture withClearedSource() {
			var routes = new ArrayList<>(List.of("synthetic-route"));
			var provider = new OpenRouterProviderPreferences(true, false, null, routes, routes, routes, null);
			var options = OpenRouterChatOptions.builder().provider(provider).build();
			var model = OpenRouterChatModel.builder()
				.openRouterApi(mock(OpenRouterApi.class))
				.defaultOptions(options)
				.build();
			routes.clear();
			return new ProviderFixture(options, model);
		}

	}

	private record NestedFixture(OpenRouterChatOptions options, OpenRouterChatOptions copy,
			OpenRouterChatOptions merged, OpenRouterChatOptions mutated, ToolCallback callback) {

		static NestedFixture withClearedSources() {
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
			builder.metadata(Map.of())
				.imageConfig(Map.of())
				.toolChoice("none")
				.toolContext(null)
				.toolCallbacks(List.of());
			return new NestedFixture(options, copy, merged, mutated, callback);
		}

	}

	private record ImageFixture(OpenRouterImageOptions options, OpenRouterImageOptions copy,
			OpenRouterImageOptions merged) {

		static ImageFixture withClearedSources() {
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
			return new ImageFixture(options, options.mutate().build(), options.merge(null));
		}

	}

}
