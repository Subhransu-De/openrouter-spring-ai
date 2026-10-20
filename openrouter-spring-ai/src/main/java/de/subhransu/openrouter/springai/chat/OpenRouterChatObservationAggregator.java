package de.subhransu.openrouter.springai.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.EmptyRateLimit;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.MessageAggregator.DefaultUsage;
import org.springframework.util.StringUtils;

final class OpenRouterChatObservationAggregator {

	private final Map<Integer, Choice> choices = new LinkedHashMap<>();

	private final ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder()
		.usage(new DefaultUsage(0, 0, 0));

	void add(ChatResponse response) {
		for (Generation generation : response.getResults()) {
			Integer index = generation.getMetadata().get("openrouter.choice_index");
			this.choices.computeIfAbsent(index != null ? index : 0, key -> new Choice()).add(generation);
		}
		addMetadata(response.getMetadata());
	}

	private void addMetadata(ChatResponseMetadata incoming) {
		if (StringUtils.hasText(incoming.getId())) {
			this.metadata.id(incoming.getId());
		}
		if (StringUtils.hasText(incoming.getModel())) {
			this.metadata.model(incoming.getModel());
		}
		if (!(incoming.getRateLimit() instanceof EmptyRateLimit)) {
			this.metadata.rateLimit(incoming.getRateLimit());
		}
		if (incoming.getPromptMetadata().iterator().hasNext()) {
			this.metadata.promptMetadata(incoming.getPromptMetadata());
		}
		// Spring AI's aggregator replaces provider usage with token totals only.
		// OpenRouter usage is a cumulative snapshot, not a per-chunk delta.
		if (incoming.getUsage() instanceof OpenRouterUsage) {
			this.metadata.usage(incoming.getUsage());
		}
		incoming.entrySet().forEach(entry -> this.metadata.keyValue(entry.getKey(), entry.getValue()));
	}

	ChatResponse response() {
		List<Generation> generations = this.choices.isEmpty() ? List.of(new Generation(new AssistantMessage("")))
				: this.choices.values().stream().map(Choice::generation).toList();
		return new ChatResponse(generations, this.metadata.build());
	}

	private static final class Choice {

		private final StringBuilder text = new StringBuilder();

		private final StringBuilder thoughts = new StringBuilder();

		private final StringBuilder outputWithoutThoughts = new StringBuilder();

		private final Map<String, Object> properties = new LinkedHashMap<>();

		private final List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();

		private ChatGenerationMetadata metadata = ChatGenerationMetadata.NULL;

		private void add(Generation generation) {
			AssistantMessage output = generation.getOutput();
			String content = output.getText();
			if (content != null) {
				this.text.append(content);
				Object isThought = output.getMetadata().get("isThought");
				if (isThought != null) {
					if (Boolean.parseBoolean(isThought.toString())) {
						this.thoughts.append(content);
					}
					else {
						this.outputWithoutThoughts.append(content);
					}
				}
			}
			this.properties.putAll(output.getMetadata());
			this.toolCalls.addAll(output.getToolCalls());
			if (generation.getMetadata() != ChatGenerationMetadata.NULL) {
				this.metadata = generation.getMetadata();
			}
		}

		private Generation generation() {
			Map<String, Object> messageProperties = new LinkedHashMap<>(this.properties);
			if (!this.thoughts.isEmpty()) {
				messageProperties.put("thoughts", this.thoughts.toString());
				messageProperties.put("outputWithoutThoughts", this.outputWithoutThoughts.toString());
			}
			return new Generation(AssistantMessage.builder()
				.content(this.text.toString())
				.properties(messageProperties)
				.toolCalls(this.toolCalls)
				.build(), this.metadata);
		}

	}

}
