package de.subhransu.openrouter.springai.garage.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class CallOutcomeTests {

  private static ChatResponse reply(String finishReason, int completionTokens) {
    Generation generation = new Generation(new AssistantMessage(""),
        ChatGenerationMetadata.builder().finishReason(finishReason).build());
    return new ChatResponse(List.of(generation),
        ChatResponseMetadata.builder().usage(new DefaultUsage(10, completionTokens)).build());
  }

  @Test
  void aLengthFinishIsTruncated() {
    assertThat(CallOutcome.of(reply("LENGTH", 5), null)).isEqualTo("truncated");
  }

  @Test
  void aStopThatUsedEveryAllowedTokenIsTruncated() {
    var options = OpenRouterChatOptions.builder().maxCompletionTokens(2048).build();
    assertThat(CallOutcome.of(reply("STOP", 2048), options)).isEqualTo("truncated");
  }

  @Test
  void aStopBelowTheLimitIsOk() {
    var options = OpenRouterChatOptions.builder().maxCompletionTokens(2048).build();
    assertThat(CallOutcome.of(reply("STOP", 512), options)).isEqualTo(CallOutcome.OK);
    assertThat(CallOutcome.of(reply("STOP", 512), null)).isEqualTo(CallOutcome.OK);
  }

  @Test
  void anErrorRecordsItsClassifiedReason() {
    assertThat(CallOutcome.of(new OpenRouterProtocolException("synthetic"))).isEqualTo("protocol-error");
    assertThat(CallOutcome.of(new IllegalStateException(new TimeoutException()))).isEqualTo("transport-error");
    assertThat(CallOutcome.of(new IllegalStateException("synthetic"))).isEqualTo(CallOutcome.UNCLASSIFIED);
  }
}
