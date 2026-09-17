package de.subhransu.openrouter.springai.garage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.garage.cli.GarageCommand;
import de.subhransu.openrouter.springai.garage.cli.GarageCommand.ImageSurface;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GarageCommandTests {

  private final GarageProperties properties = new GarageProperties();

  @Test
  void fullCannotBeNarrowedToASubsetOfScenes() {
    assertThatThrownBy(() -> command("--full", "--scene=service-story"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--full cannot be narrowed");
  }

  @Test
  void rejectsChatImagesWhenOnlyResponsesIsSelected() {
    assertThatThrownBy(() -> command("--image", "--image-surface=chat", "--request-mode=responses"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Chat image generation requires");
    assertThatThrownBy(() -> command("--full", "--request-mode=responses"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Chat image generation requires");
    assertThat(command("--image", "--image-surface=chat", "--request-mode=both").imageSurface())
        .isEqualTo(ImageSurface.CHAT);
    assertThat(command("--image", "--image-surface=sync", "--request-mode=responses").imageSurface())
        .isEqualTo(ImageSurface.SYNC);
  }

  @Test
  void capabilityFlagsComposeWithoutChangingModelsOrAddingImages() {
    GarageCommand selected = command("--text", "--embedding");
    assertThat(selected.capabilities()).containsExactly("text", "embedding");
    assertThat(selected.sceneIds()).contains("service-story", "streaming-dispatch", "modality-bays");
    assertThat(selected.sceneIds()).doesNotHaveDuplicates().doesNotContain("routing-lane");
    assertThat(selected.requestModes()).containsExactly(
        OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS, OpenRouterRequestMode.OPENAI_RESPONSES);
    assertThat(selected.runsImageInput()).isFalse();
    assertThat(selected.runsImageGeneration()).isFalse();
    assertThat(selected.foremanModel()).isEqualTo(this.properties.getForemanModel());
    assertThat(selected.maxCostUsd()).isNull();
    assertThat(this.properties.getMaxCompletionTokens()).isEqualTo(900);
    assertThat(command("--embedding", "--text")).isEqualTo(selected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"embedding", "vision", "image"})
  void individualModalitiesNeverSelectTextScenes(String capability) {
    GarageCommand selected = command("--" + capability);
    assertThat(selected.capabilities()).containsExactly(capability);
    assertThat(selected.sceneIds()).containsExactly("modality-bays");
    assertThat(selected.runsEmbeddings()).isEqualTo("embedding".equals(capability));
    assertThat(selected.runsImageInput()).isEqualTo("vision".equals(capability));
    assertThat(selected.runsImageGeneration()).isEqualTo("image".equals(capability));
    assertThat(selected.requiresApiKey()).isTrue();
  }

  @Test
  void textAloneDoesNotSelectModalities() {
    GarageCommand selected = command("--text");
    assertThat(selected.capabilities()).containsExactly("text");
    assertThat(selected.sceneIds()).doesNotContain("modality-bays");
    assertThat(selected.imageSurface()).isEqualTo(ImageSurface.NONE);
  }

  @Test
  void fullSelectsEverySceneAndBothModes() {
    GarageCommand selected = command("--full");
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
    assertThat(command("--image").imageSurface()).isEqualTo(ImageSurface.SYNC);
    GarageCommand selected = command("--image-surface=streaming", "--image",
        "--image-model=synthetic/image", "--image-quality=low", "--request-mode=chat");
    assertThat(selected.imageSurface()).isEqualTo(ImageSurface.STREAMING);
    assertThat(selected.imageModel()).isEqualTo("synthetic/image");
    assertThat(selected.imageQuality()).isEqualTo("low");
    assertThat(selected.requestModes()).containsExactly(OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS);
  }

  @Test
  void workflowExplicitlySelectsItsTextSubsetAndCostControls() {
    GarageCommand selected = command("--text",
        "--scene=streaming-dispatch,dyno-tuning,attribution-check-in,recovery-road-test",
        "--foreman-model=synthetic/text:free", "--specialist-model=synthetic/text:free",
        "--fallback-models=", "--max-cost-usd=0", "--max-completion-tokens=256",
        "--specialist-max-completion-tokens=128", "--reasoning-effort=low",
        "--provider-sort=price", "--provider-order=", "--provider-ignore=",
        "--provider-quantizations=");
    assertThat(selected.sceneIds()).hasSize(4).doesNotContain("service-story", "modality-bays");
    assertThat(selected.foremanModel()).isEqualTo("synthetic/text:free");
    assertThat(selected.specialistModel()).isEqualTo("synthetic/text:free");
    assertThat(selected.fallbackModels()).isEmpty();
    assertThat(selected.maxCostUsd()).isZero();
    assertThat(this.properties.getMaxCompletionTokens()).isEqualTo(256);
    assertThat(this.properties.getSpecialistMaxCompletionTokens()).isEqualTo(128);
    assertThat(this.properties.getReasoningEffort()).isEqualTo("low");
    assertThat(this.properties.getProviderSort()).isEqualTo("price");
    assertThat(this.properties.getProviderOrder()).isEmpty();
    assertThat(this.properties.getProviderIgnore()).isEmpty();
    assertThat(this.properties.getProviderQuantizations()).isEmpty();
  }

  @Test
  void nightlyCapabilitiesUseExplicitModelsWithoutGeneratingImages() {
    GarageCommand selected = command("--text", "--embedding", "--vision",
        "--foreman-model=synthetic/text", "--embedding-model=synthetic/embedding",
        "--vision-model=synthetic/vision", "--max-cost-usd=0.002");
    assertThat(selected.sceneIds()).hasSize(8);
    assertThat(selected.capabilities()).containsExactly("text", "embedding", "vision");
    assertThat(selected.runsImageGeneration()).isFalse();
    assertThat(selected.maxCostUsd()).isEqualTo(0.002);
    assertThat(selected.embeddingModel()).isEqualTo("synthetic/embedding");
    assertThat(selected.visionModel()).isEqualTo("synthetic/vision");
  }

  @Test
  void nightlyRequiresProvidersToHonourEveryRequestedParameter() {
    command("--text", "--embedding", "--vision", "--provider-sort=price",
        "--provider-require-parameters=true");
    assertThat(this.properties.getProviderRequireParameters()).isTrue();
    assertThat(this.properties.getProviderSort()).isEqualTo("price");

    command("--text", "--provider-require-parameters=false");
    assertThat(this.properties.getProviderRequireParameters()).isFalse();
  }

  @Test
  void requireParametersFlagToleratesSurroundingWhitespace() {
    command("--text", "--provider-require-parameters= true ");
    assertThat(this.properties.getProviderRequireParameters()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "yes", "1", "truthy", "no"})
  void unparsableRequireParametersFlagsFailBeforeInference(String raw) {
    assertThatThrownBy(() -> command("--text", "--provider-require-parameters=" + raw))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be true or false");
  }

  @Test
  void offlineContractsNeedNoApiKeyAndAutoIsALegacyNoOp() {
    GarageCommand selected = command("--offline-contracts");
    assertThat(selected.sceneIds()).containsExactly("recovery-road-test", "dyno-tuning");
    assertThat(selected.requiresApiKey()).isFalse();
    assertThat(command("--offline-contracts", "--auto")).isEqualTo(selected);
  }

  @Test
  void selectedScenesAndModesAreDeduplicated() {
    GarageCommand selected = command("--scene=dyno-tuning,dyno-tuning",
        "--request-modes=chat,responses,chat");
    assertThat(selected.sceneIds()).containsExactly("dyno-tuning");
    assertThat(selected.requestModes()).hasSize(2);
  }

  @ParameterizedTest
  @ValueSource(strings = {"NaN", "Infinity", "-Infinity", "-0.01"})
  void invalidCostCeilingsFailBeforeInference(String amount) {
    assertThatThrownBy(() -> command("--text", "--max-cost-usd=" + amount))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("finite and non-negative");
  }

  @Test
  void contradictorySelectionsFailFast() {
    for (String[] args : new String[][] {
        {"--text", "--scene=modality-bays"},
        {"--embedding", "--scene=service-story"},
        {"--text", "--embedding", "--scene=service-story"},
        {"--text", "--embedding", "--scene=modality-bays"},
        {"--text", "--image-surface=sync"},
        {"--image", "--image-surface=none"},
        {"--offline-contracts", "--text"},
        {"--offline-contracts", "--scene=service-story"},
        {"--offline-contracts", "--embedding-sweep=synthetic/model"},
        {"--text", "--embedding-sweep=synthetic/model"},
        {"--scene="},
        {"--max-completion-tokens=0"},
        {"--profile=pr-free"},
        {"--mystery"}}) {
      assertThatThrownBy(() -> command(args)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  private GarageCommand command(String... args) {
    return GarageCommand.from(args, this.properties);
  }
}
