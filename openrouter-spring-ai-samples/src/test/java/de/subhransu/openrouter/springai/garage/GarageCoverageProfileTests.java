package de.subhransu.openrouter.springai.garage;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.autoconfigure.OpenRouterChatProperties;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

class GarageCoverageProfileTests {

  @Test
  void coverageProfileBindsTheCompletePublicChatPropertySurface() {
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(GarageApplication.class)
            .profiles("coverage")
            .properties(
                Map.of(
                    "spring.main.banner-mode", "off",
                    "spring.main.web-application-type", "none",
                    "logging.level.root", "off"))
            .run()) {
      OpenRouterChatProperties properties = context.getBean(OpenRouterChatProperties.class);
      OpenRouterChatOptions options = (OpenRouterChatOptions) context.getBean(ChatModel.class).getOptions();

      assertThat(properties.getModel()).isEqualTo("garage/coverage-primary");
      assertThat(properties.getModels()).containsExactly("garage/coverage-fallback");
      assertThat(properties.getTemperature()).isNotNull();
      assertThat(properties.getTopP()).isNotNull();
      assertThat(properties.getTopK()).isNotNull();
      assertThat(properties.getMaxTokens()).isNotNull();
      assertThat(properties.getMaxCompletionTokens()).isNotNull();
      assertThat(properties.getStop()).isNotEmpty();
      assertThat(properties.getSeed()).isNotNull();
      assertThat(properties.getPresencePenalty()).isNotNull();
      assertThat(properties.getFrequencyPenalty()).isNotNull();
      assertThat(properties.getUser()).isNotBlank();
      assertThat(properties.getParallelToolCalls()).isFalse();
      assertThat(properties.getToolChoice()).isEqualTo("auto");
      assertThat(properties.getRepetitionPenalty()).isNotNull();
      assertThat(properties.getMinP()).isNotNull();
      assertThat(properties.getTopA()).isNotNull();
      assertThat(properties.getRoute()).isEqualTo("fallback");
      assertThat(properties.getIncludeUsage()).isTrue();
      assertThat(properties.getServiceTier()).isNotNull();
      assertThat(properties.getMetadata()).containsEntry("profile", "coverage");
      assertThat(properties.getProvider().dataCollection()).isEqualTo("allow");
      assertThat(properties.getProvider().order()).isNotEmpty();
      assertThat(properties.getProvider().ignore()).isNotEmpty();
      assertThat(properties.getProvider().quantizations()).isNotEmpty();
      assertThat(properties.getReasoning().effort()).isEqualTo("medium");
      assertThat(properties.getReasoning().maxTokens()).isNull();
      assertThat(options.getModel()).isEqualTo(properties.getModel());
      assertThat(options.getProvider()).isEqualTo(properties.getProvider());
      assertThat(options.getReasoning()).isEqualTo(properties.getReasoning());
    }
  }
}
