package de.subhransu.openrouter.springai.api;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.Delta;
import de.subhransu.openrouter.springai.api.dto.FunctionCall;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.api.dto.ToolCall;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.NullNode;

final class StreamRecordReader {

	private StreamRecordReader() {
	}

	static <T> @Nullable T read(ObjectMapper mapper, String line, Class<T> eventType) {
		Shape shape;
		if (eventType == ChatCompletionChunk.class) {
			if (line.contains("\"logprobs\"")) {
				return null;
			}
			shape = Shapes.CHAT;
		}
		else if (eventType == ResponsesStreamEvent.class) {
			shape = Shapes.RESPONSES;
		}
		else {
			return null;
		}
		try (JsonParser parser = mapper.createParser(line)) {
			parser.nextToken();
			Object event = shape.read(parser);
			require(parser.nextToken() == null);
			return eventType.cast(event);
		}
		catch (UnsupportedShapeException ex) {
			return null;
		}
	}

	private static void require(boolean supported) {
		if (!supported) {
			throw new UnsupportedShapeException();
		}
	}

	private static final class UnsupportedShapeException extends RuntimeException {

		private UnsupportedShapeException() {
			super(null, null, false, false);
		}

	}

	private record Shape(List<String> names, List<Field> fields, boolean extensions,
			Function<@Nullable Object[], Object> constructor) {

		private Shape(Class<?> type, boolean extensions, List<String> names, List<Field> fields) {
			this(names, fields, extensions, Objects.requireNonNull(WireRecordModule.constructor(type)));
		}

		private @Nullable Object read(JsonParser parser) {
			if (parser.currentToken() == JsonToken.VALUE_NULL) {
				return null;
			}
			require(parser.currentToken() == JsonToken.START_OBJECT);
			@Nullable Object[] values = new @Nullable Object[this.names.size() + (this.extensions ? 1 : 0)];
			if (this.extensions) {
				values[this.names.size()] = Map.of();
			}
			int seen = 0;
			while (parser.nextToken() != JsonToken.END_OBJECT) {
				require(parser.currentToken() == JsonToken.PROPERTY_NAME);
				int index = this.names.indexOf(parser.currentName());
				require(index >= 0 && (seen & (1 << index)) == 0);
				seen |= 1 << index;
				parser.nextToken();
				values[index] = this.fields.get(index).read(parser);
			}
			return this.constructor.apply(values);
		}

		private @Nullable List<@Nullable Object> list(JsonParser parser) {
			if (parser.currentToken() == JsonToken.VALUE_NULL) {
				return null;
			}
			require(parser.currentToken() == JsonToken.START_ARRAY);
			List<@Nullable Object> values = new ArrayList<>();
			while (parser.nextToken() != JsonToken.END_ARRAY) {
				values.add(read(parser));
			}
			return values;
		}

	}

	private enum Field {

		STRING, INTEGER, LONG, NULL, NULL_NODE, CHOICES, DELTA, TOOL_CALLS, FUNCTION;

		private @Nullable Object read(JsonParser parser) {
			if (parser.currentToken() == JsonToken.VALUE_NULL) {
				return this == NULL_NODE ? NullNode.getInstance() : null;
			}
			return switch (this) {
				case STRING -> {
					require(parser.currentToken() == JsonToken.VALUE_STRING);
					yield parser.getString();
				}
				case INTEGER -> {
					require(parser.currentToken() == JsonToken.VALUE_NUMBER_INT);
					yield parser.getIntValue();
				}
				case LONG -> {
					require(parser.currentToken() == JsonToken.VALUE_NUMBER_INT);
					yield parser.getLongValue();
				}
				case CHOICES -> Shapes.CHOICE.list(parser);
				case DELTA -> Shapes.DELTA.read(parser);
				case TOOL_CALLS -> Shapes.TOOL_CALL.list(parser);
				case FUNCTION -> Shapes.FUNCTION.read(parser);
				case NULL, NULL_NODE -> throw new UnsupportedShapeException();
			};
		}

	}

	private static final class Shapes {

		private static final Shape CHAT = new Shape(ChatCompletionChunk.class, true,
				List.of("id", "object", "created", "model", "provider", "choices", "usage", "error"),
				List.of(Field.STRING, Field.STRING, Field.LONG, Field.STRING, Field.STRING, Field.CHOICES, Field.NULL,
						Field.NULL));

		private static final Shape CHOICE = new Shape(Choice.class, true,
				List.of("index", "message", "delta", "finish_reason", "native_finish_reason", "error"),
				List.of(Field.INTEGER, Field.NULL, Field.DELTA, Field.STRING, Field.STRING, Field.NULL));

		private static final Shape DELTA = new Shape(Delta.class, true,
				List.of("role", "content", "reasoning", "tool_calls", "images", "reasoning_details", "refusal",
						"audio"),
				List.of(Field.STRING, Field.STRING, Field.STRING, Field.TOOL_CALLS, Field.NULL, Field.NULL,
						Field.STRING, Field.NULL));

		private static final Shape TOOL_CALL = new Shape(ToolCall.class, true,
				List.of("id", "type", "function", "index"),
				List.of(Field.STRING, Field.STRING, Field.FUNCTION, Field.INTEGER));

		private static final Shape FUNCTION = new Shape(FunctionCall.class, false, List.of("name", "arguments"),
				List.of(Field.STRING, Field.STRING));

		private static final Shape RESPONSES = new Shape(ResponsesStreamEvent.class, false,
				List.of("type", "delta", "item", "response", "error", "code", "message", "metadata", "error_type",
						"refusal", "output_index", "content_index", "text"),
				List.of(Field.STRING, Field.STRING, Field.NULL, Field.NULL, Field.NULL, Field.STRING, Field.STRING,
						Field.NULL_NODE, Field.STRING, Field.STRING, Field.INTEGER, Field.INTEGER, Field.STRING));

	}

}
