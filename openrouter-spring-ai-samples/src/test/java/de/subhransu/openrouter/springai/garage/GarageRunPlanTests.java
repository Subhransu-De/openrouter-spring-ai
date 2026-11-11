package de.subhransu.openrouter.springai.garage;

import static de.subhransu.openrouter.springai.garage.GarageRunRequests.plan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.garage.run.GarageRunPlan;
import de.subhransu.openrouter.springai.garage.run.GarageRunPlan.ImageSurface;
import de.subhransu.openrouter.springai.garage.run.GarageSweepRequest;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

// Keep request bodies and expected scene names explicit in each test.
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class GarageRunPlanTests {

  private final GarageProperties properties = new GarageProperties();

  static Stream<Arguments> streamProperty() {
    return Stream.of(
        Arguments.of(Named.of("garage.stream=false", false), List.of("service-story")),
        Arguments.of(Named.of("garage.stream=true", true), List.of("service-story", "streaming-dispatch")));
  }

  @ParameterizedTest
  @MethodSource("streamProperty")
  void streamPropertyOnlyAddsToTheDefaultSceneSelection(boolean stream, List<String> defaultScenes) {
    this.properties.setStream(stream);

    assertThat(select("").sceneIds()).containsExactlyElementsOf(defaultScenes);
  }

  private static Arguments explicit(boolean stream, String name, String json, List<String> scenes) {
    return Arguments.of(Named.of("garage.stream=" + stream + ", " + name, stream), json, scenes);
  }

  static Stream<Arguments> explicitSelections() {
    return Stream.of(false, true).flatMap(stream -> Stream.of(
        explicit(stream, "one scene", "{\"scenes\":[\"dyno-tuning\"]}", List.of("dyno-tuning")),
        explicit(stream, "text scene", "{\"capabilities\":[\"text\"],\"scenes\":[\"service-story\"]}",
            List.of("service-story")),
        explicit(stream, "offline contracts", "{\"offlineContracts\":true}",
            List.of("recovery-road-test", "dyno-tuning"))));
  }

  @ParameterizedTest
  @MethodSource("explicitSelections")
  void anExplicitSelectionAlwaysWinsOverTheStreamProperty(boolean stream, String json, List<String> scenes) {
    this.properties.setStream(stream);

    assertThat(select(json).sceneIds()).containsExactlyElementsOf(scenes);
  }

  @ParameterizedTest
  @ValueSource(strings = {"embedding", "vision", "image"})
  void aModalityMayNameItsOwnSceneExplicitly(String capability) {
    assertThat(select("{\"capabilities\":[\"" + capability + "\"],\"scenes\":[\"modality-bays\"]}")
        .sceneIds()).containsExactly("modality-bays");
  }

  @ParameterizedTest
  @ValueSource(strings = {"{\"capabilities\":[\"txt\"]}", "{\"requestModes\":[\"chats\"]}",
      "{\"capabilities\":[\"image\"],\"image\":{\"surface\":\"poster\"}}"})
  void rejectsUnknownSelectionValues(String json) {
    assertThatThrownBy(() -> plan(json)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown");
  }

  @Test
  void fullCannotBeNarrowedToASubsetOfScenes() {
    assertThatThrownBy(() -> plan("{\"full\":true,\"scenes\":[\"service-story\"]}"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("full cannot be narrowed");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "{\"capabilities\":[\"image\"],\"image\":{\"surface\":\"chat\"},\"requestModes\":[\"responses\"]}",
      "{\"full\":true,\"requestModes\":[\"responses\"]}"})
  void rejectsChatImagesWhenOnlyResponsesIsSelected(String json) {
    assertThatThrownBy(() -> plan(json)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Chat image generation requires");
  }

  static Stream<Arguments> acceptedImageSurfaces() {
    return Stream.of(
        Arguments.of(Named.of("chat images in both modes",
            "{\"capabilities\":[\"image\"],\"image\":{\"surface\":\"chat\"},\"requestModes\":[\"both\"]}"),
            ImageSurface.CHAT),
        Arguments.of(Named.of("sync images in Responses only",
            "{\"capabilities\":[\"image\"],\"image\":{\"surface\":\"sync\"},\"requestModes\":[\"responses\"]}"),
            ImageSurface.SYNC));
  }

  @ParameterizedTest
  @MethodSource("acceptedImageSurfaces")
  void keepsTheImageSurfaceWhenTheModesSupportIt(String json, ImageSurface expected) {
    assertThat(plan(json).imageSurface()).isEqualTo(expected);
  }

  @Test
  void textAndEmbeddingCapabilitiesSelectTheirScenesInBothModes() {
    GarageRunPlan selected = plan("{\"capabilities\":[\"text\",\"embedding\"]}");

    assertThat(selected.capabilities()).containsExactly("text", "embedding");
    assertThat(selected.sceneIds()).contains("service-story", "streaming-dispatch", "modality-bays")
        .doesNotHaveDuplicates()
        .doesNotContain("routing-lane");
    assertThat(selected.requestModes()).containsExactly(
        OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS, OpenRouterRequestMode.OPENAI_RESPONSES);
  }

  @Test
  void textAndEmbeddingCapabilitiesAddNeitherImagesNorModelChanges() {
    GarageRunPlan selected = plan("{\"capabilities\":[\"text\",\"embedding\"]}", this.properties);

    assertThat(selected.runsImageInput()).isFalse();
    assertThat(selected.runsImageGeneration()).isFalse();
    assertThat(selected.foremanModel()).isEqualTo(this.properties.getForemanModel());
    assertThat(this.properties.getMaxCompletionTokens()).isEqualTo(4096);
  }

  @Test
  void theOrderOfTheCapabilitiesDoesNotChangeThePlan() {
    assertThat(plan("{\"capabilities\":[\"embedding\",\"text\"]}"))
        .isEqualTo(plan("{\"capabilities\":[\"text\",\"embedding\"]}"));
  }

  @Test
  void textOnlySelectsNoModalityScene() {
    GarageRunPlan textOnly = plan("{\"capabilities\":[\"text\"]}");

    assertThat(textOnly.capabilities()).containsExactly("text");
    assertThat(textOnly.sceneIds()).doesNotContain("modality-bays");
    assertThat(textOnly.imageSurface()).isEqualTo(ImageSurface.NONE);
  }

  static Stream<Arguments> modalities() {
    return Stream.of(
        Arguments.of(Named.of("embedding", "embedding"), true, false, false),
        Arguments.of(Named.of("vision", "vision"), false, true, false),
        Arguments.of(Named.of("image", "image"), false, false, true));
  }

  @ParameterizedTest
  @MethodSource("modalities")
  void individualModalitiesNeverSelectTextScenes(String capability, boolean embeddings, boolean imageInput,
      boolean imageGeneration) {
    GarageRunPlan selected = plan("{\"capabilities\":[\"" + capability + "\"]}");
    assertThat(selected.capabilities()).containsExactly(capability);
    assertThat(selected.sceneIds()).containsExactly("modality-bays");
    assertThat(selected.runsEmbeddings()).isEqualTo(embeddings);
    assertThat(selected.runsImageInput()).isEqualTo(imageInput);
    assertThat(selected.runsImageGeneration()).isEqualTo(imageGeneration);
    assertThat(selected.requiresApiKey()).isTrue();
  }

  @Test
  void fullSelectsEverySceneAndBothModes() {
    GarageRunPlan selected = plan("{\"full\":true}");
    assertThat(selected.sceneIds()).containsExactly(
        "service-story", "streaming-dispatch", "digital-inspection", "modality-bays",
        "express-invoice", "routing-lane", "dyno-tuning", "attribution-check-in",
        "recovery-road-test");
    assertThat(selected.capabilities()).containsExactly("text", "embedding", "vision", "image");
    assertThat(selected.imageSurface()).isEqualTo(ImageSurface.ALL);
    assertThat(selected.requestModes()).containsExactly(
        OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS, OpenRouterRequestMode.OPENAI_RESPONSES);
  }

  @Test
  void imageDefaultsToSyncAndCanSelectAnotherSurface() {
    assertThat(plan("{\"capabilities\":[\"image\"]}").imageSurface()).isEqualTo(ImageSurface.SYNC);
    GarageRunPlan selected = plan("{\"capabilities\":[\"image\"],"
        + "\"image\":{\"surface\":\"streaming\",\"quality\":\"low\"},"
        + "\"models\":{\"image\":\"synthetic/image\"},\"requestModes\":[\"chat\"]}");
    assertThat(selected.imageSurface()).isEqualTo(ImageSurface.STREAMING);
    assertThat(selected.imageModel()).isEqualTo("synthetic/image");
    assertThat(selected.imageQuality()).isEqualTo("low");
    assertThat(selected.requestModes()).containsExactly(OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS);
  }

  @Test
  void explicitTextSubsetAppliesLimitsAndProviderOverridesToTheRunSettings() {
    GarageRunPlan selected = plan("""
        {"capabilities": ["text"],
         "scenes": ["streaming-dispatch", "dyno-tuning", "attribution-check-in", "recovery-road-test"],
         "models": {"foreman": "synthetic/text:free", "specialist": "synthetic/text:free", "fallbacks": []},
         "limits": {"maxCompletionTokens": 256, "specialistMaxCompletionTokens": 128},
         "reasoningEffort": "low",
         "provider": {"sort": "price", "order": [], "ignore": [], "quantizations": []}}
        """, this.properties);
    assertThat(selected.sceneIds()).containsExactly("streaming-dispatch", "dyno-tuning",
        "attribution-check-in", "recovery-road-test");
    assertThat(selected.foremanModel()).isEqualTo("synthetic/text:free");
    assertThat(selected.specialistModel()).isEqualTo("synthetic/text:free");
    assertThat(selected.fallbackModels()).isEmpty();
    assertThat(this.properties.getMaxCompletionTokens()).isEqualTo(256);
    assertThat(this.properties.getSpecialistMaxCompletionTokens()).isEqualTo(128);
    assertThat(this.properties.getReasoningEffort()).isEqualTo("low");
    assertThat(this.properties.getProviderSort()).isEqualTo("price");
    assertThat(this.properties.getProviderOrder()).isEmpty();
    assertThat(this.properties.getProviderIgnore()).isEmpty();
    assertThat(this.properties.getProviderQuantizations()).isEmpty();
  }

  @Test
  void missingFieldsKeepDefaultsAndEmptyListsClearThem() {
    GarageRunPlan defaults = plan("{\"capabilities\":[\"text\"]}", this.properties);
    assertThat(defaults.fallbackModels()).containsExactly("openai/gpt-oss-20b");
    assertThat(this.properties.getProviderOrder()).containsExactly("OpenAI");

    GarageProperties cleared = new GarageProperties();
    GarageRunPlan clearing = plan("{\"capabilities\":[\"text\"],\"models\":{\"fallbacks\":[]},"
        + "\"provider\":{\"order\":[]}}", cleared);
    assertThat(clearing.fallbackModels()).isEmpty();
    assertThat(cleared.getProviderOrder()).isEmpty();
  }

  @Test
  void nightlyCapabilitiesUseExplicitModelsWithoutGeneratingImages() {
    GarageRunPlan selected = plan("{\"capabilities\":[\"text\",\"embedding\",\"vision\"],"
        + "\"models\":{\"foreman\":\"synthetic/text\",\"embedding\":\"synthetic/embedding\","
        + "\"vision\":\"synthetic/vision\"}}");
    List<String> textScenes = plan("{\"capabilities\":[\"text\"]}").sceneIds();
    assertThat(selected.sceneIds()).containsExactlyInAnyOrderElementsOf(
        Stream.concat(textScenes.stream(), Stream.of("modality-bays")).toList());
    assertThat(selected.capabilities()).containsExactly("text", "embedding", "vision");
    assertThat(selected.runsImageGeneration()).isFalse();
    assertThat(selected.embeddingModel()).isEqualTo("synthetic/embedding");
    assertThat(selected.visionModel()).isEqualTo("synthetic/vision");
  }

  static Stream<Arguments> requireParametersRequests() {
    return Stream.of(
        Arguments.of(Named.of("sent true over a configured false", false),
            "{\"capabilities\":[\"text\"],\"provider\":{\"sort\":\"price\",\"requireParameters\":true}}", true),
        Arguments.of(Named.of("not sent keeps a configured true", true), "{\"capabilities\":[\"text\"]}", true),
        Arguments.of(Named.of("not sent keeps a configured false", false), "{\"capabilities\":[\"text\"]}", false),
        Arguments.of(Named.of("sent false over a configured true", true),
            "{\"capabilities\":[\"text\"],\"provider\":{\"requireParameters\":false}}", false));
  }

  @ParameterizedTest
  @MethodSource("requireParametersRequests")
  void requireParametersIsAppliedOnlyWhenSent(boolean configured, String json, boolean expected) {
    this.properties.setProviderRequireParameters(configured);

    plan(json, this.properties);

    assertThat(this.properties.getProviderRequireParameters()).isEqualTo(expected);
  }

  @Test
  void offlineContractsNeedNoApiKey() {
    GarageRunPlan selected = plan("{\"offlineContracts\":true}");
    assertThat(selected.sceneIds()).containsExactly("recovery-road-test", "dyno-tuning");
    assertThat(selected.requiresApiKey()).isFalse();
  }

  @Test
  void selectedScenesAndModesAreDeduplicated() {
    GarageRunPlan selected = plan("{\"scenes\":[\"dyno-tuning\",\"dyno-tuning\"],"
        + "\"requestModes\":[\"chat\",\"responses\",\"chat\"]}");
    assertThat(selected.sceneIds()).containsExactly("dyno-tuning");
    assertThat(selected.requestModes()).hasSize(2);
  }

  @Test
  void sweepsNeedAtLeastOneModel() {
    assertThatThrownBy(() -> sweep(List.of(), List.of()))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("A sweep needs");
  }

  @Test
  void sweepsNeedAnApiKeyAndDeduplicateTheirModels() {
    GarageRunPlan sweep = sweep(List.of(" synthetic/embedding ", "synthetic/embedding"),
        List.of("synthetic/image@provider?quality=low"));
    assertThat(sweep.isSweep()).isTrue();
    assertThat(sweep.sceneIds()).isEmpty();
    assertThat(sweep.requiresApiKey()).isTrue();
    assertThat(sweep.embeddingSweepModels()).containsExactly("synthetic/embedding");
    assertThat(sweep.imageSweepModels()).containsExactly("synthetic/image@provider?quality=low");
  }

  static Stream<Arguments> contradictorySelections() {
    return Stream.of(
        Arguments.of("{\"capabilities\":[\"text\"],\"scenes\":[\"modality-bays\"]}", "Scene modality-bays is outside the selected capabilities"),
        Arguments.of("{\"capabilities\":[\"embedding\"],\"scenes\":[\"service-story\"]}", "Scene service-story is outside the selected capabilities"),
        Arguments.of("{\"capabilities\":[\"text\",\"embedding\"],\"scenes\":[\"service-story\"]}", "Selected modalities require modality-bays in scenes"),
        Arguments.of("{\"capabilities\":[\"text\",\"embedding\"],\"scenes\":[\"modality-bays\"]}", "The text capability requires at least one text scene"),
        Arguments.of("{\"capabilities\":[\"text\"],\"image\":{\"surface\":\"sync\"}}", "image.surface requires the image capability or full"),
        Arguments.of("{\"capabilities\":[\"image\"],\"image\":{\"surface\":\"none\"}}", "The image capability cannot use image.surface=none"),
        Arguments.of("{\"offlineContracts\":true,\"capabilities\":[\"text\"]}", "offlineContracts cannot be combined with live capabilities"),
        Arguments.of("{\"offlineContracts\":true,\"scenes\":[\"service-story\"]}", "offlineContracts accepts only offline scenes"),
        Arguments.of("{\"scenes\":[]}", "scenes must select at least one scene"),
        Arguments.of("{\"capabilities\":[null]}", "capabilities must not contain null"),
        Arguments.of("{\"capabilities\":[]}", "capabilities must select at least one capability"),
        Arguments.of("{\"topic\":\" \"}", "topic must not be blank"),
        Arguments.of("{\"reasoningEffort\":\"\"}", "reasoningEffort must not be blank"),
        Arguments.of("{\"models\":{\"foreman\":\" \"}}", "models.foreman must not be blank"),
        Arguments.of("{\"provider\":{\"sort\":\"\"}}", "provider.sort must not be blank"),
        Arguments.of("{\"capabilities\":[\"image\"],\"image\":{\"quality\":\"\"}}", "image.quality must not be blank"),
        Arguments.of("{\"requestModes\":[]}", "requestModes must select at least one mode"),
        Arguments.of("{\"limits\":{\"maxCompletionTokens\":0}}", "limits.maxCompletionTokens must be positive"),
        Arguments.of("{\"scenes\":[\"modality-bays\"]}", "modality-bays requires the embedding, vision, or image capability"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("contradictorySelections")
  void contradictorySelectionsFailFastAndNameTheConflict(String json, String message) {
    assertThatThrownBy(() -> plan(json)).isInstanceOf(IllegalArgumentException.class).hasMessage(message);
  }

  private GarageRunPlan select(String json) {
    return plan(json, this.properties);
  }

  private GarageRunPlan sweep(List<String> embeddingModels, List<String> imageModels) {
    return GarageRunPlan.forSweep(
        new GarageSweepRequest(embeddingModels, imageModels, null, null), new GarageProperties());
  }
}
