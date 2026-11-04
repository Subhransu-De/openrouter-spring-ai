package de.subhransu.openrouter.springai.api.dto;

import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public record EmbeddingsResponse(@Nullable String object, @Nullable List<@Nullable EmbeddingData> data,
		@Nullable String model, @Nullable Usage usage, @Nullable StreamError error) {

	public EmbeddingsResponse(@Nullable String object, @Nullable List<@Nullable EmbeddingData> data,
			@Nullable String model, @Nullable Usage usage) {
		this(object, data, model, usage, null);
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(Include.NON_NULL)
	public record EmbeddingData(@Nullable String object,
			@JsonDeserialize(using = IndexDeserializer.class) @Nullable Integer index, float @Nullable [] embedding) {

		// The generated record methods compare the float[] by identity; compare and
		// render its content instead.
		@Override
		public boolean equals(@Nullable Object other) {
			return other instanceof EmbeddingData that && Objects.equals(this.object, that.object)
					&& Objects.equals(this.index, that.index) && Arrays.equals(this.embedding, that.embedding);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.object, this.index, Arrays.hashCode(this.embedding));
		}

		@Override
		public String toString() {
			return "EmbeddingData[object=" + this.object + ", index=" + this.index + ", embedding="
					+ Arrays.toString(this.embedding) + "]";
		}
	}

	private static final class IndexDeserializer extends ValueDeserializer<Integer> {

		@Override
		public @Nullable Integer deserialize(JsonParser parser, DeserializationContext context) {
			if (parser.currentToken() == JsonToken.VALUE_NUMBER_FLOAT
					&& parser.getDecimalValue().stripTrailingZeros().scale() > 0) {
				throw new OpenRouterProtocolException("Embedding response index must be an integer");
			}
			return context.readValue(parser, Integer.class);
		}

	}
}
