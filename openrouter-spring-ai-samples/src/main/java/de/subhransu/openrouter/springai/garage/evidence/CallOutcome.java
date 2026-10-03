package de.subhransu.openrouter.springai.garage.evidence;

import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;

/**
 * How one model call ended, as a fixed code that is safe to persist: {@code ok}, {@code truncated},
 * or the {@link SceneFailureReason} code of the error it raised. An error the library does not
 * classify is {@code error}.
 */
public final class CallOutcome {

  public static final String OK = "ok";

  public static final String UNCLASSIFIED = "error";

  private CallOutcome() {
  }

  public static String of(ChatResponse response, @Nullable ChatOptions options) {
    return truncated(response, options) ? SceneFailureReason.TRUNCATED.code() : OK;
  }

  public static String of(Throwable error) {
    SceneFailureReason reason = SceneFailureReason.classify(error);
    return reason != null ? reason.code() : UNCLASSIFIED;
  }

  /**
   * Whether a reply ended at its token limit. Chat {@code length} and Responses
   * {@code max_output_tokens} map to {@code LENGTH}, but a provider can also report a normal stop
   * after using every allowed token, so the completion-token count is compared with the limit.
   */
  static boolean truncated(ChatResponse response, @Nullable ChatOptions options) {
    if (response.getResults().stream()
        .anyMatch(generation -> "LENGTH".equals(generation.getMetadata().getFinishReason()))) {
      return true;
    }
    Integer limit = options instanceof OpenRouterChatOptions openRouter && openRouter.getMaxCompletionTokens() != null
        ? openRouter.getMaxCompletionTokens()
        : options != null ? options.getMaxTokens() : null;
    Usage usage = response.getMetadata().getUsage();
    Integer completion = usage != null ? usage.getCompletionTokens() : null;
    return limit != null && completion != null && completion >= limit;
  }
}
