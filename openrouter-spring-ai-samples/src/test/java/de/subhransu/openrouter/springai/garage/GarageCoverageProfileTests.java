package de.subhransu.openrouter.springai.garage;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.autoconfigure.OpenRouterChatProperties;
import de.subhransu.openrouter.springai.chat.OpenRouterServiceTier;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** The shipped {@code coverage} profile sets every public chat property to a known value. */
class GarageCoverageProfileTests {

  @Test
  void coverageProfileBindsEveryPublicChatPropertyToItsDocumentedValue() {
    OpenRouterChatProperties properties = coverageChatProperties();

    assertThat(properties.getModel()).isEqualTo("garage/coverage-primary");
    assertThat(properties.getModels()).containsExactly("garage/coverage-fallback");
    assertThat(properties.getTemperature()).isEqualTo(0.2);
    assertThat(properties.getTopP()).isEqualTo(0.85);
    assertThat(properties.getTopK()).isEqualTo(40);
    assertThat(properties.getMaxTokens()).isEqualTo(700);
    assertThat(properties.getMaxCompletionTokens()).isEqualTo(900);
    assertThat(properties.getStop()).containsExactly("GARAGE_END");
    assertThat(properties.getSeed()).isEqualTo(1972);
    assertThat(properties.getPresencePenalty()).isEqualTo(0.1);
    assertThat(properties.getFrequencyPenalty()).isEqualTo(0.1);
    assertThat(properties.getUser()).isEqualTo("garage-coverage");
    assertThat(properties.getParallelToolCalls()).isFalse();
    assertThat(properties.getToolChoice()).isEqualTo("auto");
    assertThat(properties.getRepetitionPenalty()).isEqualTo(1.05);
    assertThat(properties.getMinP()).isEqualTo(0.05);
    assertThat(properties.getTopA()).isEqualTo(0.1);
    assertThat(properties.getRoute()).isEqualTo("fallback");
    assertThat(properties.getIncludeUsage()).isTrue();
    assertThat(properties.getServiceTier()).isEqualTo(OpenRouterServiceTier.DEFAULT);
    assertThat(properties.getMetadata()).containsOnly(Map.entry("application", "garage"),
        Map.entry("profile", "coverage"));
    assertThat(properties.getProvider().allowFallbacks()).isTrue();
    assertThat(properties.getProvider().requireParameters()).isFalse();
    assertThat(properties.getProvider().dataCollection()).isEqualTo("allow");
    assertThat(properties.getProvider().order()).containsExactly("OpenAI");
    assertThat(properties.getProvider().ignore()).containsExactly("Fireworks");
    assertThat(properties.getProvider().quantizations()).containsExactly("bf16", "fp16", "fp8");
    assertThat(properties.getProvider().sort()).isEqualTo("throughput");
    assertThat(properties.getReasoning().effort()).isEqualTo("medium");
    assertThat(properties.getReasoning().exclude()).isFalse();
    assertThat(properties.getReasoning().enabled()).isTrue();
    assertThat(properties.getReasoning().maxTokens()).isNull();
  }

  private static OpenRouterChatProperties coverageChatProperties() {
    StandardEnvironment environment = new StandardEnvironment();
    // Only the shipped profile, never the developer machine's own overrides.
    environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    try {
      new YamlPropertySourceLoader().load("coverage", new ClassPathResource("application-coverage.yml"))
          .forEach(environment.getPropertySources()::addLast);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
    return Binder.get(environment).bind("spring.ai.openrouter.chat", OpenRouterChatProperties.class).get();
  }

}
