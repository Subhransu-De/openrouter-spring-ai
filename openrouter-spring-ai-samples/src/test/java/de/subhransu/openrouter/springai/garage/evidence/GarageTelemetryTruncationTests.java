package de.subhransu.openrouter.springai.garage.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class GarageTelemetryTruncationTests {

  private static ChatResponse reply(String finishReason, int completionTokens) {
    Generation generation = new Generation(new AssistantMessage(""),
        ChatGenerationMetadata.builder().finishReason(finishReason).build());
    return new ChatResponse(List.of(generation),
        ChatResponseMetadata.builder().usage(new DefaultUsage(10, completionTokens)).build());
  }

  @Test
  void aLengthFinishIsTruncated() {
    assertThat(GarageTelemetry.truncated(reply("LENGTH", 5), null)).isTrue();
  }

  @Test
  void aStopThatUsedEveryAllowedTokenIsTruncated() {
    var options = OpenRouterChatOptions.builder().maxCompletionTokens(2048).build();
    assertThat(GarageTelemetry.truncated(reply("STOP", 2048), options)).isTrue();
  }

  @Test
  void aStopBelowTheLimitIsNotTruncated() {
    var options = OpenRouterChatOptions.builder().maxCompletionTokens(2048).build();
    assertThat(GarageTelemetry.truncated(reply("STOP", 512), options)).isFalse();
    assertThat(GarageTelemetry.truncated(reply("STOP", 512), null)).isFalse();
  }
}
