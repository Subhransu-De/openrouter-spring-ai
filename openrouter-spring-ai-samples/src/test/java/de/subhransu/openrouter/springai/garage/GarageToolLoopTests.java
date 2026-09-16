package de.subhransu.openrouter.springai.garage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.garage.evidence.GarageToolLoop;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.metadata.ToolMetadata;
import reactor.core.publisher.Flux;

class GarageToolLoopTests {

  @ParameterizedTest
  @ValueSource(strings = {"wrong-id", "wrong-name", "empty-result", "missing-result"})
  void rejectsUncorrelatedFollowUp(String variation) {
    var probe = new GarageToolLoop();
    var chain = mock(CallAdvisorChain.class);
    var response = new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
        .toolCalls(List.of(new AssistantMessage.ToolCall("synthetic-call", "function", "synthetic_tool", "{}")))
        .build())));
    when(chain.nextCall(any())).thenReturn(new ChatClientResponse(response, Map.of()));
    probe.adviseCall(new ChatClientRequest(
        new Prompt("synthetic"), Map.of()), chain);
    var result = ToolResponseMessage.builder().responses(
        "missing-result".equals(variation) ? List.of() : List.of(new ToolResponseMessage.ToolResponse(
            "wrong-id".equals(variation) ? "other-call" : "synthetic-call",
            "wrong-name".equals(variation) ? "other_tool" : "synthetic_tool",
            "empty-result".equals(variation) ? "" : "synthetic result"))).build();
    var followUp = new ChatClientRequest(
        new Prompt(List.of(result)), Map.of());
    assertThatThrownBy(() -> probe.adviseCall(followUp, chain)).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no correlated follow-up result");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void directReturnCannotCertifyAModelFollowUp(boolean streaming) {
    ChatModel model = mock(ChatModel.class);
    when(model.getOptions()).thenReturn(OpenRouterChatOptions.builder().build());
    var response = new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
        .toolCalls(List.of(new AssistantMessage.ToolCall("synthetic-call", "function", "synthetic_tool", "{}")))
        .build())));
    when(model.call(any(Prompt.class))).thenReturn(response);
    when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response));
    var callback = FunctionToolCallback.builder("synthetic_tool", (Map<String, Object> input) -> "synthetic result")
        .description("Synthetic direct return").inputType(Map.class)
        .toolMetadata(ToolMetadata.builder().returnDirect(true).build()).build();
    GarageToolLoop probe = new GarageToolLoop();
    var request = ChatClient.builder(model).build().prompt().user("synthetic")
        .options(OpenRouterChatOptions.builder().toolCallbacks(callback)).advisors(probe);
    if (streaming) {
      request.stream().chatResponse().collectList().block();
    } else {
      request.call().chatResponse();
    }
    assertThatThrownBy(() -> probe.assertCompleted(List.of("synthetic_tool")))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("follow-ups did not complete");
  }
}
