package de.subhransu.openrouter.springai.api;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.ContentPart;
import de.subhransu.openrouter.springai.api.dto.Delta;
import de.subhransu.openrouter.springai.api.dto.FunctionCall;
import de.subhransu.openrouter.springai.api.dto.ResponsesOutputItem;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.api.dto.StreamError;
import de.subhransu.openrouter.springai.api.dto.ToolCall;
import de.subhransu.openrouter.springai.api.dto.Usage;
import de.subhransu.openrouter.springai.api.dto.AudioOutput;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.api.dto.ChoiceError;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.DeserializationConfig;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.deser.ValueInstantiator;
import tools.jackson.databind.deser.ValueInstantiators;
import tools.jackson.databind.deser.std.StdValueInstantiator;
import tools.jackson.databind.module.SimpleModule;

final class WireRecordModule extends SimpleModule {

	private static final Map<Class<?>, Function<@Nullable Object[], Object>> CONSTRUCTORS = constructors();

	@SuppressWarnings("unchecked")
	private static Map<Class<?>, Function<@Nullable Object[], Object>> constructors() {
		return Map.of(ChatCompletionChunk.class,
				args -> new ChatCompletionChunk((String) args[0], (String) args[1], (Long) args[2], (String) args[3],
						(String) args[4], (List<@Nullable Choice>) args[5], (Usage) args[6], (StreamError) args[7],
						(Map<String, @Nullable Object>) args[8]),
				Choice.class, args -> new Choice((Integer) args[0], (ChatMessage) args[1], (Delta) args[2],
						(String) args[3], args[4], (ChoiceError) args[5], (Map<String, @Nullable Object>) args[6]),
				Delta.class,
				args -> new Delta((String) args[0], (String) args[1], (String) args[2],
						(List<@Nullable ToolCall>) args[3], (List<@Nullable ContentPart>) args[4],
						(List<@Nullable JsonNode>) args[5], (String) args[6], (AudioOutput) args[7],
						(Map<String, @Nullable Object>) args[8]),
				ToolCall.class,
				args -> new ToolCall((String) args[0], (String) args[1], (FunctionCall) args[2], (Integer) args[3],
						(Map<String, @Nullable Object>) args[4]),
				FunctionCall.class, args -> new FunctionCall((String) args[0], (String) args[1]),
				ResponsesStreamEvent.class,
				args -> new ResponsesStreamEvent((String) args[0], (String) args[1], (ResponsesOutputItem) args[2],
						(ResponsesResult) args[3], (StreamError) args[4], (String) args[5], (String) args[6],
						(JsonNode) args[7], (String) args[8], (String) args[9], (Integer) args[10], (Integer) args[11],
						(String) args[12]));
	}

	@Override
	public void setupModule(SetupContext context) {
		context.addValueInstantiators(new ValueInstantiators.Base() {
			@Override
			public ValueInstantiator modifyValueInstantiator(DeserializationConfig config,
					BeanDescription.Supplier beanDescription, ValueInstantiator original) {
				var constructor = CONSTRUCTORS.get(original.getValueClass());
				return constructor != null && original.getClass() == StdValueInstantiator.class
						? new RecordInstantiator((StdValueInstantiator) original, constructor) : original;
			}
		});
	}

	private static final class RecordInstantiator extends StdValueInstantiator {

		private final Function<@Nullable Object[], Object> constructor;

		private RecordInstantiator(StdValueInstantiator original, Function<@Nullable Object[], Object> constructor) {
			super(original);
			this.constructor = constructor;
		}

		@Override
		public Object createFromObjectWith(DeserializationContext context, @Nullable Object[] args) {
			try {
				return this.constructor.apply(args);
			}
			catch (Exception ex) {
				return context.handleInstantiationProblem(getValueClass(), args, rewrapCtorProblem(context, ex));
			}
		}

	}

}
