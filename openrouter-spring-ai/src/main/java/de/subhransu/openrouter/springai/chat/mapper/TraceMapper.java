package de.subhransu.openrouter.springai.chat.mapper;

import de.subhransu.openrouter.springai.chat.OpenRouterTrace;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Maps {@link OpenRouterTrace} to the wire {@code trace} object shared by the chat,
 * embeddings and images endpoints.
 *
 * @author Subhransu De
 */
public final class TraceMapper {

	private TraceMapper() {
	}

	public static @Nullable Map<String, @Nullable Object> map(@Nullable OpenRouterTrace trace) {
		if (trace == null) {
			return null;
		}
		Map<String, @Nullable Object> wire = new LinkedHashMap<>();
		put(wire, "trace_id", trace.traceId());
		put(wire, "trace_name", trace.traceName());
		put(wire, "span_name", trace.spanName());
		put(wire, "generation_name", trace.generationName());
		put(wire, "parent_span_id", trace.parentSpanId());
		Map<String, @Nullable Object> metadata = trace.metadata();
		if (metadata != null) {
			wire.putAll(metadata);
		}
		return wire;
	}

	private static void put(Map<String, @Nullable Object> wire, String key, @Nullable String value) {
		if (value != null) {
			wire.put(key, value);
		}
	}

}
