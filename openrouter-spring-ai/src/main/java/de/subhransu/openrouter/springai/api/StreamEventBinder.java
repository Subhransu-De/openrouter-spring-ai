package de.subhransu.openrouter.springai.api;

import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.util.ConcurrentReferenceHashMap;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

final class StreamEventBinder {

	private static final Map<ObjectMapper, ObjectMapper> DIRECT_MAPPERS = new ConcurrentReferenceHashMap<>(16,
			ConcurrentReferenceHashMap.ReferenceType.WEAK);

	private final ObjectMapper objectMapper;

	private final @Nullable ObjectMapper directMapper;

	StreamEventBinder(ObjectMapper objectMapper, boolean useDirectBinding) {
		this.objectMapper = objectMapper;
		this.directMapper = useDirectBinding
				? DIRECT_MAPPERS.computeIfAbsent(objectMapper, StreamEventBinder::failNumberCoercions) : null;
	}

	@Nullable ObjectReader reader(Class<?> eventType) {
		return this.directMapper == null ? null
				: this.directMapper.readerFor(eventType)
					.with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
					.with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
	}

	<T> T bind(String line, @Nullable ObjectReader reader, Class<T> eventType) {
		if (reader != null && line.startsWith("{")) {
			try {
				T event = StreamRecordReader.read(this.objectMapper, line, eventType);
				if (event != null) {
					return event;
				}
				return reader.readValue(line);
			}
			catch (JacksonException ex) {
				return bindTree(line, eventType);
			}
		}
		return bindTree(line, eventType);
	}

	private <T> T bindTree(String line, Class<T> eventType) {
		var tree = this.objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(line);
		if (tree == null || !tree.isObject()) {
			throw new OpenRouterProtocolException("OpenRouter stream chunk must be a JSON object");
		}
		return this.objectMapper.treeToValue(tree, eventType);
	}

	private static ObjectMapper failNumberCoercions(ObjectMapper objectMapper) {
		return objectMapper.rebuild()
			.withCoercionConfig(LogicalType.Textual,
					coercion -> coercion.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
						.setCoercion(CoercionInputShape.Float, CoercionAction.Fail))
			.withCoercionConfig(LogicalType.Integer,
					coercion -> coercion.setCoercion(CoercionInputShape.Float, CoercionAction.Fail))
			.build();
	}

}
