package de.subhransu.openrouter.springai.garage;

import static de.subhransu.openrouter.springai.garage.GarageRunRequests.plan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.garage.run.GarageRunPlan;
import de.subhransu.openrouter.springai.garage.run.GarageRunPlan.ImageSurface;
import de.subhransu.openrouter.springai.garage.run.GarageSweepRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;

// Keep request bodies and expected scene names explicit in each test.
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class GarageRunPlanTests {

  private final GarageProperties properties = new GarageProperties();

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void streamPropertyOnlyAddsToDefaultSceneSelection(boolean stream) {
    this.properties.setStream(stream);
    assertThat(select("").sceneIds()).containsExactlyElementsOf(stream
        ? List.of("service-story", "streaming-dispatch")
        : List.of("service-story"));
    assertThat(select("{\"scenes\":[\"dyno-tuning\"]}").sceneIds()).containsExactly("dyno-tuning");
    assertThat(select("{\"offlineContracts\":true}").sceneIds())
        .containsExactly("recovery-road-test", "dyno-tuning");
    for (String capability : new String[] {"embedding", "vision", "image"}) {
      assertThat(select("{\"capabilities\":[\"" + capability + "\"]}").sceneIds())
          .containsExactly("modality-bays");
      assertThat(select("{\"capabilities\":[\"" + capability + "\"],\"scenes\":[\"modality-bays\"]}")
          .sceneIds()).containsExactly("modality-bays");
    }
    assertThat(select("{\"capabilities\":[\"text\"],\"scenes\":[\"service-story\"]}").sceneIds())
        .containsExactly("service-story");
    assertThat(select("{\"full\":true}").sceneIds()).hasSize(9);
    assertThat(sweep(List.of("synthetic/embedding"), List.of()).sceneIds()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"{\"capabilities\":[\"txt\"]}", "{\"requestModes\":[\"chats\"]}",
      "{\"capabilities\":[\"image\"],\"image\":{\"surface\":\"poster\"}}"})
  void rejectsUnknownSelectionValues(String json) {
    assertThatThrownBy(() -> plan(json)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown");
  }

  @Test
  void rejectsMisspelledRequestFields() {
    assertThatThrownBy(() -> plan("{\"capabilites\":[\"text\"]}"))
        .isInstanceOf(JacksonException.class);
  }

  @Test
  void fullCannotBeNarrowedToASubsetOfScenes() {
    assertThatThrownBy(() -> plan("{\"full\":true,\"scenes\":[\"service-story\"]}"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("full cannot be narrowed");
  }

  @Test
  void rejectsChatImagesWhenOnlyResponsesIsSelected() {
    assertThatThrownBy(() -> plan("{\"capabilities\":[\"image\"],\"image\":{\"surface\":\"chat\"},"
        + "\"requestModes\":[\"responses\"]}"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Chat image generation requires");
    assertThatThrownBy(() -> plan("{\"full\":true,\"requestModes\":[\"responses\"]}"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Chat image generation requires");
    assertThat(plan("{\"capabilities\":[\"image\"],\"image\":{\"surface\":\"chat\"},"
        + "\"requestModes\":[\"both\"]}").imageSurface()).isEqualTo(ImageSurface.CHAT);
    assertThat(plan("{\"capabilities\":[\"image\"],\"image\":{\"surface\":\"sync\"},"
        + "\"requestModes\":[\"responses\"]}").imageSurface()).isEqualTo(ImageSurface.SYNC);
  }

  @Test
  void capabilitiesComposeWithoutChangingModelsOrAddingImages() {
    GarageRunPlan selected = plan("{\"capabilities\":[\"text\",\"embedding\"]}", this.properties);
    assertThat(selected.capabilities()).containsExactly("text", "embedding");
    assertThat(selected.sceneIds()).contains("service-story", "streaming-dispatch", "modality-bays");
    assertThat(selected.sceneIds()).doesNotHaveDuplicates().doesNotContain("routing-lane");
    assertThat(selected.requestModes()).containsExactly(
        OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS, OpenRouterRequestMode.OPENAI_RESPONSES);
    assertThat(selected.runsImageInput()).isFalse();
    assertThat(selected.runsImageGeneration()).isFalse();
    assertThat(selected.foremanModel()).isEqualTo(this.properties.getForemanModel());
    assertThat(this.properties.getMaxCompletionTokens()).isEqualTo(900);
    assertThat(plan("{\"capabilities\":[\"embedding\",\"text\"]}")).isEqualTo(selected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"embedding", "vision", "image"})
  void individualModalitiesNeverSelectTextScenes(String capability) {
    GarageRunPlan selected = plan("{\"capabilities\":[\"" + capability + "\"]}");
    assertThat(selected.capabilities()).containsExactly(capability);
    assertThat(selected.sceneIds()).containsExactly("modality-bays");
    assertThat(selected.runsEmbeddings()).isEqualTo("embedding".equals(capability));
    assertThat(selected.runsImageInput()).isEqualTo("vision".equals(capability));
    assertThat(selected.runsImageGeneration()).isEqualTo("image".equals(capability));
    assertThat(selected.requiresApiKey()).isTrue();
  }

  @Test
  void textAloneDoesNotSelectModalities() {
    GarageRunPlan selected = plan("{\"capabilities\":[\"text\"]}");
    assertThat(selected.capabilities()).containsExactly("text");
    assertThat(selected.sceneIds()).doesNotContain("modality-bays");
    assertThat(selected.imageSurface()).isEqualTo(ImageSurface.NONE);
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
    assertThat(selected.sceneIds()).hasSize(4).doesNotContain("service-story", "modality-bays");
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
    assertThat(selected.sceneIds()).hasSize(8);
    assertThat(selected.capabilities()).containsExactly("text", "embedding", "vision");
    assertThat(selected.runsImageGeneration()).isFalse();
    assertThat(selected.embeddingModel()).isEqualTo("synthetic/embedding");
    assertThat(selected.visionModel()).isEqualTo("synthetic/vision");
  }

  @Test
  void requireParametersIsAppliedOnlyWhenSent() {
    plan("{\"capabilities\":[\"text\"],\"provider\":{\"sort\":\"price\",\"requireParameters\":true}}",
        this.properties);
    assertThat(this.properties.getProviderRequireParameters()).isTrue();
    assertThat(this.properties.getProviderSort()).isEqualTo("price");

    plan("{\"capabilities\":[\"text\"]}", this.properties);
    assertThat(this.properties.getProviderRequireParameters()).isTrue();

    plan("{\"capabilities\":[\"text\"],\"provider\":{\"requireParameters\":false}}", this.properties);
    assertThat(this.properties.getProviderRequireParameters()).isFalse();
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
  void sweepsNeedModelsAndAnApiKey() {
    assertThatThrownBy(() -> sweep(List.of(), List.of()))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("A sweep needs");
    GarageRunPlan sweep = sweep(List.of(" synthetic/embedding ", "synthetic/embedding"),
        List.of("synthetic/image@provider?quality=low"));
    assertThat(sweep.isSweep()).isTrue();
    assertThat(sweep.requiresApiKey()).isTrue();
    assertThat(sweep.embeddingSweepModels()).containsExactly("synthetic/embedding");
    assertThat(sweep.imageSweepModels()).containsExactly("synthetic/image@provider?quality=low");
  }

  @Test
  void contradictorySelectionsFailFast() {
    for (String json : new String[] {
        "{\"capabilities\":[\"text\"],\"scenes\":[\"modality-bays\"]}",
        "{\"capabilities\":[\"embedding\"],\"scenes\":[\"service-story\"]}",
        "{\"capabilities\":[\"text\",\"embedding\"],\"scenes\":[\"service-story\"]}",
        "{\"capabilities\":[\"text\",\"embedding\"],\"scenes\":[\"modality-bays\"]}",
        "{\"capabilities\":[\"text\"],\"image\":{\"surface\":\"sync\"}}",
        "{\"capabilities\":[\"image\"],\"image\":{\"surface\":\"none\"}}",
        "{\"offlineContracts\":true,\"capabilities\":[\"text\"]}",
        "{\"offlineContracts\":true,\"scenes\":[\"service-story\"]}",
        "{\"scenes\":[]}",
        "{\"capabilities\":[null]}",
        "{\"requestModes\":[]}",
        "{\"limits\":{\"maxCompletionTokens\":0}}",
        "{\"scenes\":[\"modality-bays\"]}"}) {
      assertThatThrownBy(() -> plan(json)).as(json).isInstanceOf(IllegalArgumentException.class);
    }
  }

  private GarageRunPlan select(String json) {
    return plan(json, this.properties);
  }

  private GarageRunPlan sweep(List<String> embeddingModels, List<String> imageModels) {
    return GarageRunPlan.forSweep(
        new GarageSweepRequest(embeddingModels, imageModels, null, null), new GarageProperties());
  }
}
