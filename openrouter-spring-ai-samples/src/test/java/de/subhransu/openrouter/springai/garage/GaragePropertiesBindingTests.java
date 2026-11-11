package de.subhransu.openrouter.springai.garage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.chat.OpenRouterServiceTier;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The {@code garage.*} properties documented in {@code docs/garage.md}, bound the way the Garage binds them. */
class GaragePropertiesBindingTests {

  // Without the developer machine's own GARAGE_* variables, so the defaults are the shipped ones.
  private final ApplicationContextRunner shippedConfiguration = new ApplicationContextRunner()
      .withInitializer(context -> context.getEnvironment().getPropertySources().remove("systemEnvironment"))
      .withInitializer(new ConfigDataApplicationContextInitializer())
      .withUserConfiguration(Properties.class);

  @Test
  void anEmptyEnvironmentKeepsTheDocumentedDefaults() {
    GarageProperties properties = bind(Map.of());

    assertThat(properties.isEnabled()).isTrue();
    assertThat(properties.getRequestModes()).containsExactly(OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS);
    assertThat(properties.getMaxCompletionTokens()).isEqualTo(4096);
    assertThat(properties.getSpecialistMaxCompletionTokens()).isEqualTo(2048);
    assertThat(properties.isStream()).isFalse();
    assertThat(properties.isFull()).isFalse();
    assertThat(properties.getForemanModel()).isEqualTo("openai/gpt-oss-120b");
    assertThat(properties.getSpecialistModel()).isEqualTo("openai/gpt-oss-20b");
    assertThat(properties.getFallbackModels()).containsExactly("openai/gpt-oss-20b");
    assertThat(properties.getEmbeddingModel()).isEqualTo("openai/text-embedding-3-small");
    assertThat(properties.getVisionModel()).isEqualTo("google/gemini-2.5-flash");
    assertThat(properties.getImageModel()).isEqualTo("google/gemini-2.5-flash-image");
  }

  @Test
  void everyDocumentedPropertyBindsFromItsKebabCaseKey() {
    GarageProperties properties = bind(Map.ofEntries(
        Map.entry("garage.enabled", "false"),
        Map.entry("garage.foreman-model", "synthetic/foreman"),
        Map.entry("garage.specialist-model", "synthetic/specialist"),
        Map.entry("garage.embedding-model", "synthetic/embedding"),
        Map.entry("garage.vision-model", "synthetic/vision"),
        Map.entry("garage.image-model", "synthetic/image"),
        Map.entry("garage.fallback-models[0]", "synthetic/fallback-a"),
        Map.entry("garage.fallback-models[1]", "synthetic/fallback-b"),
        Map.entry("garage.topic", "a synthetic topic"),
        Map.entry("garage.output-dir", "synthetic-output"),
        Map.entry("garage.stream", "true"),
        Map.entry("garage.full", "true"),
        Map.entry("garage.request-modes", "OPENAI_CHAT_COMPLETIONS,OPENAI_RESPONSES"),
        Map.entry("garage.max-completion-tokens", "111"),
        Map.entry("garage.specialist-max-completion-tokens", "222"),
        Map.entry("garage.reasoning-effort", "low"),
        Map.entry("garage.provider-sort", "price"),
        Map.entry("garage.service-tier", "FLEX")));

    assertThat(properties.isEnabled()).isFalse();
    assertThat(properties.getForemanModel()).isEqualTo("synthetic/foreman");
    assertThat(properties.getSpecialistModel()).isEqualTo("synthetic/specialist");
    assertThat(properties.getEmbeddingModel()).isEqualTo("synthetic/embedding");
    assertThat(properties.getVisionModel()).isEqualTo("synthetic/vision");
    assertThat(properties.getImageModel()).isEqualTo("synthetic/image");
    assertThat(properties.getFallbackModels()).containsExactly("synthetic/fallback-a", "synthetic/fallback-b");
    assertThat(properties.getTopic()).isEqualTo("a synthetic topic");
    assertThat(properties.getOutputDir()).isEqualTo(Path.of("synthetic-output"));
    assertThat(properties.isStream()).isTrue();
    assertThat(properties.isFull()).isTrue();
    assertThat(properties.getRequestModes()).containsExactly(OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS,
        OpenRouterRequestMode.OPENAI_RESPONSES);
    assertThat(properties.getMaxCompletionTokens()).isEqualTo(111);
    assertThat(properties.getSpecialistMaxCompletionTokens()).isEqualTo(222);
    assertThat(properties.getReasoningEffort()).isEqualTo("low");
    assertThat(properties.getProviderSort()).isEqualTo("price");
    assertThat(properties.getServiceTier()).isEqualTo(OpenRouterServiceTier.FLEX);
  }

  @Test
  void requestModesTakeTheEnumNamesAndNothingElse() {
    assertThat(bind(Map.of("garage.request-modes", "OPENAI_RESPONSES")).getRequestModes())
        .containsExactly(OpenRouterRequestMode.OPENAI_RESPONSES);

    assertThatThrownBy(() -> bind(Map.of("garage.request-modes", "chat")))
        .isInstanceOf(BindException.class);
  }

  @Test
  void theShippedConfigurationUsesTheDocumentedEnvironmentVariables() {
    this.shippedConfiguration.withPropertyValues(
        "GARAGE_FOREMAN_MODEL=env/foreman",
        "GARAGE_SPECIALIST_MODEL=env/specialist",
        "GARAGE_EMBEDDING_MODEL=env/embedding",
        "GARAGE_VISION_MODEL=env/vision",
        "GARAGE_IMAGE_MODEL=env/image",
        "GARAGE_FALLBACK_MODEL=env/fallback",
        "GARAGE_OUTPUT_DIR=env-output",
        "GARAGE_FULL=true",
        "GARAGE_REASONING_ENABLED=false",
        "GARAGE_REASONING_EFFORT=high",
        "GARAGE_PROVIDER_SORT=latency",
        "GARAGE_SERVICE_TIER=PRIORITY").run(context -> {
          assertThat(context).hasNotFailed();
          GarageProperties properties = context.getBean(GarageProperties.class);
          assertThat(properties.getForemanModel()).isEqualTo("env/foreman");
          assertThat(properties.getSpecialistModel()).isEqualTo("env/specialist");
          assertThat(properties.getEmbeddingModel()).isEqualTo("env/embedding");
          assertThat(properties.getVisionModel()).isEqualTo("env/vision");
          assertThat(properties.getImageModel()).isEqualTo("env/image");
          assertThat(properties.getFallbackModels()).containsExactly("env/fallback");
          assertThat(properties.getOutputDir()).isEqualTo(Path.of("env-output"));
          assertThat(properties.isFull()).isTrue();
          assertThat(properties.isReasoningEnabled()).isFalse();
          assertThat(properties.getReasoningEffort()).isEqualTo("high");
          assertThat(properties.getProviderSort()).isEqualTo("latency");
          assertThat(properties.getServiceTier()).isEqualTo(OpenRouterServiceTier.PRIORITY);
        });
  }

  @Test
  void theShippedConfigurationKeepsTheDocumentedDefaultsWithoutEnvironmentVariables() {
    this.shippedConfiguration.run(context -> {
      GarageProperties properties = context.getBean(GarageProperties.class);
      assertThat(properties.getForemanModel()).isEqualTo("openai/gpt-oss-120b");
      assertThat(properties.getFallbackModels()).isEqualTo(List.of("openai/gpt-oss-20b"));
      assertThat(properties.getOutputDir()).isEqualTo(Path.of("outputs"));
      assertThat(properties.isFull()).isFalse();
      assertThat(properties.getRequestModes()).containsExactly(OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS);
    });
  }

  private static GarageProperties bind(Map<String, String> source) {
    return new Binder(new MapConfigurationPropertySource(source))
        .bindOrCreate("garage", Bindable.of(GarageProperties.class));
  }

  @EnableConfigurationProperties(GarageProperties.class)
  static class Properties {

  }

}
