package de.subhransu.openrouter.springai.chat.mapper;

import de.subhransu.openrouter.springai.api.dto.CacheControl;
import de.subhransu.openrouter.springai.api.dto.ContentPart;
import de.subhransu.openrouter.springai.chat.OpenRouterCacheBreakpoint;
import de.subhransu.openrouter.springai.chat.OpenRouterCacheBreakpoint.Ttl;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

final class CacheBreakpointMapper {

	private CacheBreakpointMapper() {
	}

	static List<OpenRouterCacheBreakpoint> breakpoints(Message message) {
		if (!message.getMetadata().containsKey(OpenRouterCacheBreakpoint.METADATA_KEY)) {
			return List.of();
		}
		Object value = message.getMetadata().get(OpenRouterCacheBreakpoint.METADATA_KEY);
		if (!(value instanceof List<?> list)
				|| list.stream().anyMatch(item -> !(item instanceof OpenRouterCacheBreakpoint))) {
			throw new IllegalArgumentException("Cache breakpoints must be a List<OpenRouterCacheBreakpoint>");
		}
		return list.stream().map(OpenRouterCacheBreakpoint.class::cast).toList();
	}

	static void validate(List<Message> messages) {
		int count = 0;
		boolean fiveMinutes = false;
		for (Message message : messages) {
			for (OpenRouterCacheBreakpoint breakpoint : breakpoints(message)) {
				if (message.getMessageType() != MessageType.SYSTEM && message.getMessageType() != MessageType.USER) {
					throw new IllegalArgumentException("Cache breakpoints support only system and user message text");
				}
				if (++count > 4) {
					throw new IllegalArgumentException("At most four cache breakpoints are supported per request");
				}
				if (fiveMinutes && breakpoint.ttl() == Ttl.ONE_HOUR) {
					throw new IllegalArgumentException(
							"One-hour cache breakpoints must precede five-minute breakpoints");
				}
				fiveMinutes |= breakpoint.ttl() == Ttl.FIVE_MINUTES;
			}
		}
	}

	static List<ContentPart> textParts(Message message) {
		String text = message.getText();
		List<ContentPart> parts = new ArrayList<>();
		int start = 0;
		for (OpenRouterCacheBreakpoint breakpoint : breakpoints(message)) {
			int end = breakpoint.endIndex();
			if (text == null || end <= start || end > text.length() || (end < text.length()
					&& Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end)))) {
				throw new IllegalArgumentException(
						"Cache breakpoint offsets must increase within message text without splitting surrogate pairs");
			}
			parts.add(new ContentPart("text", text.substring(start, end), null,
					new CacheControl("ephemeral", breakpoint.ttl() == Ttl.ONE_HOUR ? "1h" : null)));
			start = end;
		}
		if (text != null && start < text.length()) {
			parts.add(ContentPart.text(text.substring(start)));
		}
		return parts;
	}

}
