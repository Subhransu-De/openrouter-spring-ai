package de.subhransu.openrouter.springai.garage.evidence;

import de.subhransu.openrouter.springai.chat.OpenRouterUsage;
import de.subhransu.openrouter.springai.garage.GarageResponses;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

/** Per-scene probe inside the tool advisor: only returned results sent back to a model count. */
public final class GarageToolLoop implements CallAdvisor, StreamAdvisor {

  private final Map<String, ToolCall> pending = new LinkedHashMap<>();
  private final Set<String> callIds = new HashSet<>();
  private final Set<String> completedTools = new HashSet<>();
  private ChatResponse lastResponse;
  private boolean reasoningObserved;

  @Override
  public String getName() {
    return "Garage tool loop evidence";
  }

  @Override
  public int getOrder() {
    return ToolCallingAdvisor.DEFAULT_ORDER + 1;
  }

  @Override
  public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
    followUp(request);
    ChatClientResponse response = chain.nextCall(request);
    received(response);
    return response;
  }

  @Override
  public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
    return Flux.defer(() -> {
      followUp(request);
      return new ChatClientMessageAggregator().aggregateChatClientResponse(
          chain.nextStream(request).switchIfEmpty(
              Flux.error(new IllegalStateException("Tool loop stream produced no response"))), this::received);
    });
  }

  private void followUp(ChatClientRequest request) {
    for (ToolCall call : this.pending.values()) {
      boolean returned = request.prompt().getInstructions().stream()
          .filter(ToolResponseMessage.class::isInstance)
          .map(ToolResponseMessage.class::cast)
          .flatMap(message -> message.getResponses().stream())
          .anyMatch(result -> call.id().equals(result.id()) && call.name().equals(result.name())
              && StringUtils.hasText(result.responseData()));
      if (!returned) {
        throw new IllegalStateException("Model tool call has no correlated follow-up result");
      }
      this.completedTools.add(call.name());
    }
    this.pending.clear();
  }

  private void received(ChatClientResponse response) {
    if (response.chatResponse() == null || response.chatResponse().getResult() == null) {
      throw new IllegalStateException("Tool loop model response was missing");
    }
    this.lastResponse = response.chatResponse();
    // A final answer may need no reasoning after earlier tool-selection rounds.
    this.reasoningObserved |= StringUtils.hasText(GarageResponses.reasoning(this.lastResponse))
        || this.lastResponse.getMetadata().getUsage() instanceof OpenRouterUsage usage
            && usage.getReasoningTokens() != null && usage.getReasoningTokens() > 0;
    for (ToolCall call : response.chatResponse().getResult().getOutput().getToolCalls()) {
      if (!StringUtils.hasText(call.id()) || !this.callIds.add(call.id())) {
        throw new IllegalStateException("Tool call ID was missing or reused");
      }
      this.pending.put(call.id(), call);
    }
  }

  public ChatResponse lastResponse() {
    return this.lastResponse;
  }

  public boolean reasoningObserved() {
    return this.reasoningObserved;
  }

  public void assertCompleted(List<String> requiredTools) {
    if (!this.pending.isEmpty() || !this.completedTools.containsAll(requiredTools)) {
      throw new IllegalStateException("Required model-directed tool calls and follow-ups did not complete");
    }
  }
}
