package de.subhransu.openrouter.springai.garage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.garage.evidence.GarageToolLoop;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedTest;
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

  static Stream<Arguments> uncorrelatedFollowUps() {
    return Stream.of(
        Arguments.of(Named.of("wrong call id", List.of(
            new ToolResponseMessage.ToolResponse("other-call", "synthetic_tool", "synthetic result")))),
        Arguments.of(Named.of("wrong tool name", List.of(
            new ToolResponseMessage.ToolResponse("synthetic-call", "other_tool", "synthetic result")))),
        Arguments.of(Named.of("empty result", List.of(
            new ToolResponseMessage.ToolResponse("synthetic-call", "synthetic_tool", "")))),
        Arguments.of(Named.of("missing result", List.<ToolResponseMessage.ToolResponse>of())));
  }

  @ParameterizedTest
  @MethodSource("uncorrelatedFollowUps")
  void rejectsUncorrelatedFollowUp(List<ToolResponseMessage.ToolResponse> responses) {
    var probe = new GarageToolLoop();
    var chain = mock(CallAdvisorChain.class);
    var response = new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
        .toolCalls(List.of(new AssistantMessage.ToolCall("synthetic-call", "function", "synthetic_tool", "{}")))
        .build())));
    when(chain.nextCall(any())).thenReturn(new ChatClientResponse(response, Map.of()));
    probe.adviseCall(new ChatClientRequest(
        new Prompt("synthetic"), Map.of()), chain);
    var result = ToolResponseMessage.builder().responses(responses).build();
    var followUp = new ChatClientRequest(
        new Prompt(List.of(result)), Map.of());
    assertThatThrownBy(() -> probe.adviseCall(followUp, chain)).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no correlated follow-up result");
  }

  @Test
  void directReturnCannotCertifyAModelFollowUpOverACall() {
    GarageToolLoop probe = new GarageToolLoop();

    directReturnRequest(probe).call().chatResponse();

    assertDirectReturnIsIncomplete(probe);
  }

  @Test
  void directReturnCannotCertifyAModelFollowUpOverAStream() {
    GarageToolLoop probe = new GarageToolLoop();

    directReturnRequest(probe).stream().chatResponse().collectList().block();

    assertDirectReturnIsIncomplete(probe);
  }

  private static void assertDirectReturnIsIncomplete(GarageToolLoop probe) {
    assertThatThrownBy(() -> probe.assertCompleted(List.of("synthetic_tool")))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("follow-ups did not complete");
  }

  private static ChatClient.ChatClientRequestSpec directReturnRequest(GarageToolLoop probe) {
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
    return ChatClient.builder(model).build().prompt().user("synthetic")
        .options(OpenRouterChatOptions.builder().toolCallbacks(callback)).advisors(probe);
  }
}
