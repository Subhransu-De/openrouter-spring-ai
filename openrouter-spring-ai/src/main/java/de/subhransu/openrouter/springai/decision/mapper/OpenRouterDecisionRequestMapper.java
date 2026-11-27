package de.subhransu.openrouter.springai.decision.mapper;

import de.subhransu.openrouter.springai.api.dto.ContentPart;
import de.subhransu.openrouter.springai.api.dto.DecisionsRequest;
import de.subhransu.openrouter.springai.api.dto.ProviderPreferences;
import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import de.subhransu.openrouter.springai.chat.mapper.MediaUrlMapper;
import de.subhransu.openrouter.springai.chat.mapper.TraceMapper;
import de.subhransu.openrouter.springai.decision.OpenRouterDecisionOptions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.part.MediaPart;
import org.springframework.ai.chat.messages.part.MessagePart;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.chat.messages.part.ToolResultPart;
import org.springframework.ai.decision.DecisionPrompt;
import org.springframework.ai.decision.input.ConversationInput;
import org.springframework.ai.decision.input.DecisionInput;
import org.springframework.ai.decision.input.FieldsInput;
import org.springframework.ai.decision.input.TextInput;
import org.springframework.ai.decision.question.ChoiceQuestion;
import org.springframework.ai.decision.question.DecisionQuestion;
import org.springframework.ai.decision.question.ScoreLevel;
import org.springframework.ai.decision.question.ScoreQuestion;
import org.springframework.ai.decision.question.YesNoQuestion;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Maps a {@link DecisionPrompt} and merged options to the Decisions API request.
 *
 * <p>
 * The input becomes the request {@code state}: a text as a string, or, with images, an
 * array of strings and {@code image_url} parts in order; fields as a JSON object in field
 * order; a conversation as {@code {"messages": [...]}} with each message's role, text,
 * tool calls and tool results. OpenRouter reads images only as top-level state parts, so
 * fields and conversations must be text, and media other than images is rejected.
 * Reasoning and unknown parts of a conversation are not sent; elsewhere they are
 * rejected.
 *
 * <p>
 * A {@link YesNoQuestion} becomes a {@code noul} whose criteria are its {@code true} and
 * {@code false} descriptions, a {@link ChoiceQuestion} a {@code choice} whose criteria
 * map each option to its description, and a {@link ScoreQuestion} a {@code score} whose
 * criteria list the levels from the lowest, as a label or as a {@code label} and
 * {@code description} object.
 *
 * @author Subhransu De
 */
public final class OpenRouterDecisionRequestMapper {

	public DecisionsRequest map(DecisionPrompt prompt, OpenRouterDecisionOptions options) {
		String model = options.getModel();
		// OpenRouter has no default decision model; fail before the round trip.
		Assert.hasText(model, "Decision model must not be empty");
		Map<String, DecisionsRequest.Question> questions = new LinkedHashMap<>();
		prompt.getQuestions().forEach((name, question) -> questions.put(name, question(question)));
		return new DecisionsRequest(model, state(prompt.getInstructions()), questions, provider(options.getProvider()),
				options.getUser(), options.getSessionId(), TraceMapper.map(options.getTrace()));
	}

	private static Object state(DecisionInput input) {
		if (input instanceof TextInput textInput) {
			List<Object> items = new ArrayList<>();
			boolean media = false;
			for (MessagePart part : textInput.getParts()) {
				if (part instanceof MediaPart mediaPart) {
					items.add(ContentPart.image(MediaUrlMapper.imageUrl(mediaPart.media())));
					media = true;
				}
				else {
					items.add(text(List.of(part), "the input"));
				}
			}
			return media ? items : textInput.getText();
		}
		if (input instanceof FieldsInput fieldsInput) {
			Map<String, @Nullable Object> state = new LinkedHashMap<>();
			fieldsInput.getFields().forEach((name, value) -> state.put(name, fieldValue(name, value)));
			return state;
		}
		if (input instanceof ConversationInput conversationInput) {
			List<Map<String, Object>> messages = new ArrayList<>();
			for (Message message : conversationInput.getMessages()) {
				Map<String, Object> entry = message(message);
				if (entry != null) {
					messages.add(entry);
				}
			}
			return Map.of("messages", messages);
		}
		// Unreachable while DecisionInput is sealed to the three kinds above.
		throw new IllegalStateException("Unexpected decision input of type " + input.getClass().getName());
	}

	private static @Nullable Object fieldValue(String name, @Nullable Object value) {
		if (value instanceof MessagePart part) {
			return text(List.of(part), "field '" + name + "'");
		}
		if (value instanceof List<?> parts && FieldsInput.isPartList(parts)) {
			return text(parts.stream().map(MessagePart.class::cast).toList(), "field '" + name + "'");
		}
		return value;
	}

	/**
	 * The text of the given parts. Media is rejected, since only a content input can
	 * carry images, and so is any other kind of part.
	 */
	private static String text(List<? extends MessagePart> parts, String what) {
		StringBuilder text = new StringBuilder();
		for (MessagePart part : parts) {
			if (part instanceof TextPart textPart) {
				text.append(textPart.text());
			}
			else if (part instanceof MediaPart) {
				throw new IllegalArgumentException(
						"OpenRouter reads decision images only as top-level state parts, but " + what
								+ " carries media; pass images in a content input, DecisionInput.content(...)");
			}
			else {
				throw new IllegalArgumentException("Unsupported decision input part " + part.getClass().getSimpleName()
						+ " in " + what + ": OpenRouter accepts text parts here");
			}
		}
		return text.toString();
	}

	/**
	 * A message as a JSON object: its role, its text as {@code content}, and its tool
	 * calls and results. Reasoning and unknown parts are not sent, and a message with
	 * nothing else to send is {@code null}.
	 */
	private static @Nullable Map<String, Object> message(Message message) {
		String role = message.getMessageType().getValue();
		StringBuilder content = new StringBuilder();
		List<Map<String, @Nullable Object>> toolCalls = new ArrayList<>();
		List<Map<String, @Nullable Object>> toolResults = new ArrayList<>();
		for (MessagePart part : ConversationInput.parts(message)) {
			if (part instanceof TextPart || part instanceof MediaPart) {
				content.append(text(List.of(part), "a " + role + " message"));
			}
			else if (part instanceof ToolCallPart toolCallPart) {
				Map<String, @Nullable Object> call = new LinkedHashMap<>();
				call.put("id", toolCallPart.toolCall().id());
				call.put("name", toolCallPart.toolCall().name());
				call.put("arguments", toolCallPart.toolCall().arguments());
				toolCalls.add(call);
			}
			else if (part instanceof ToolResultPart toolResultPart) {
				Map<String, @Nullable Object> result = new LinkedHashMap<>();
				result.put("id", toolResultPart.id());
				result.put("name", toolResultPart.name());
				result.put("result", text(toolResultPart.content(), "a tool result"));
				if (toolResultPart.isError()) {
					result.put("error", true);
				}
				toolResults.add(result);
			}
		}
		boolean hasContent = StringUtils.hasText(content);
		if (!hasContent && toolCalls.isEmpty() && toolResults.isEmpty()) {
			return null;
		}
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("role", role);
		if (hasContent) {
			entry.put("content", content.toString());
		}
		if (!toolCalls.isEmpty()) {
			entry.put("tool_calls", toolCalls);
		}
		if (!toolResults.isEmpty()) {
			entry.put("tool_results", toolResults);
		}
		return entry;
	}

	private static DecisionsRequest.Question question(DecisionQuestion question) {
		if (question instanceof YesNoQuestion yesNo) {
			Map<String, String> criteria = new LinkedHashMap<>();
			if (yesNo.getWhenYes() != null) {
				criteria.put("true", yesNo.getWhenYes());
			}
			if (yesNo.getWhenNo() != null) {
				criteria.put("false", yesNo.getWhenNo());
			}
			return new DecisionsRequest.Question("noul", yesNo.getText(), criteria.isEmpty() ? null : criteria);
		}
		if (question instanceof ChoiceQuestion choice) {
			// An option without a description is sent as null, so its name speaks for it.
			return new DecisionsRequest.Question("choice", choice.getText(), new LinkedHashMap<>(choice.getOptions()));
		}
		if (question instanceof ScoreQuestion score) {
			List<Object> levels = new ArrayList<>();
			for (ScoreLevel level : score.getLevels()) {
				if (level.getDescription() == null) {
					levels.add(level.getLabel());
				}
				else {
					Map<String, String> described = new LinkedHashMap<>();
					described.put("label", level.getLabel());
					described.put("description", level.getDescription());
					levels.add(described);
				}
			}
			return new DecisionsRequest.Question("score", score.getText(), levels);
		}
		// Unreachable while DecisionQuestion is sealed to the three kinds above.
		throw new IllegalStateException("Unexpected decision question of type " + question.getClass().getName());
	}

	private static @Nullable ProviderPreferences provider(@Nullable OpenRouterProviderPreferences provider) {
		if (provider == null) {
			return null;
		}
		return new ProviderPreferences(provider.allowFallbacks(), provider.requireParameters(),
				provider.dataCollection(), provider.order(), provider.ignore(), provider.quantizations(),
				provider.sort());
	}

}
