package de.subhransu.openrouter.springai.chat;

import de.subhransu.openrouter.springai.support.OptionChecks;
import de.subhransu.openrouter.springai.support.OptionSnapshots;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Observability metadata that OpenRouter forwards to its broadcast destinations, sent as
 * {@code trace}. OpenRouter handles the named fields specially; {@code metadata} entries
 * are added next to them as custom keys.
 *
 * @param traceId groups the generations of one trace
 * @param traceName name of the trace
 * @param spanName name of the span that holds the generation
 * @param generationName name of the generation
 * @param parentSpanId identifier of the parent span
 * @param metadata custom keys; must not repeat a named field
 * @author Subhransu De
 */
public record OpenRouterTrace(@Nullable String traceId, @Nullable String traceName, @Nullable String spanName,
		@Nullable String generationName, @Nullable String parentSpanId,
		@Nullable Map<String, @Nullable Object> metadata) {

	/**
	 * Wire names of the named fields.
	 */
	private static final Set<String> NAMED_KEYS = Set.of("trace_id", "trace_name", "span_name", "generation_name",
			"parent_span_id");

	public OpenRouterTrace {
		if (metadata != null) {
			for (String key : metadata.keySet()) {
				if (key == null || NAMED_KEYS.contains(key)) {
					throw new IllegalArgumentException("trace metadata key " + OptionChecks.describe(key)
							+ " is not allowed; set the named trace field instead");
				}
			}
		}
		metadata = OptionSnapshots.map(metadata);
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private @Nullable String traceId;

		private @Nullable String traceName;

		private @Nullable String spanName;

		private @Nullable String generationName;

		private @Nullable String parentSpanId;

		private @Nullable Map<String, @Nullable Object> metadata;

		private Builder() {
		}

		public Builder traceId(@Nullable String traceId) {
			this.traceId = traceId;
			return this;
		}

		public Builder traceName(@Nullable String traceName) {
			this.traceName = traceName;
			return this;
		}

		public Builder spanName(@Nullable String spanName) {
			this.spanName = spanName;
			return this;
		}

		public Builder generationName(@Nullable String generationName) {
			this.generationName = generationName;
			return this;
		}

		public Builder parentSpanId(@Nullable String parentSpanId) {
			this.parentSpanId = parentSpanId;
			return this;
		}

		public Builder metadata(@Nullable Map<String, @Nullable Object> metadata) {
			this.metadata = metadata;
			return this;
		}

		public OpenRouterTrace build() {
			return new OpenRouterTrace(this.traceId, this.traceName, this.spanName, this.generationName,
					this.parentSpanId, this.metadata);
		}

	}

}
