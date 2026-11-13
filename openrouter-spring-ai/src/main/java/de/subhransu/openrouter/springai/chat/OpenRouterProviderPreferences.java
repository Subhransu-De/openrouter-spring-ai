package de.subhransu.openrouter.springai.chat;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import de.subhransu.openrouter.springai.support.OptionChecks;
import de.subhransu.openrouter.springai.support.OptionSnapshots;
import java.util.Arrays;
import java.util.List;

public record OpenRouterProviderPreferences(@Nullable Boolean allowFallbacks, @Nullable Boolean requireParameters,
		@Nullable String dataCollection, @Nullable List<String> order, @Nullable List<String> ignore,
		@Nullable List<String> quantizations, @Nullable String sort) {

	private static final Log logger = LogFactory.getLog(OpenRouterProviderPreferences.class);

	public OpenRouterProviderPreferences {
		order = OptionSnapshots.list(OptionChecks.noNullElements("provider order", order));
		ignore = OptionSnapshots.list(OptionChecks.noNullElements("provider ignore", ignore));
		quantizations = OptionSnapshots.list(OptionChecks.noNullElements("provider quantizations", quantizations));
		OptionChecks.warnIfUnknown(logger, "provider dataCollection", dataCollection, OpenRouterDataCollection.VALUES);
		OptionChecks.warnIfUnknown(logger, "provider sort", sort, OpenRouterProviderSort.VALUES);
		if (quantizations != null) {
			for (String quantization : quantizations) {
				OptionChecks.warnIfUnknown(logger, "provider quantization", quantization,
						OpenRouterQuantization.VALUES);
			}
		}
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * The String setters also cover values that OpenRouter adds after this release. An
	 * undocumented value is sent and logged once as a warning.
	 */
	public static final class Builder {

		private @Nullable Boolean allowFallbacks;

		private @Nullable Boolean requireParameters;

		private @Nullable String dataCollection;

		private @Nullable List<String> order;

		private @Nullable List<String> ignore;

		private @Nullable List<String> quantizations;

		private @Nullable String sort;

		private Builder() {
		}

		public Builder allowFallbacks(@Nullable Boolean allowFallbacks) {
			this.allowFallbacks = allowFallbacks;
			return this;
		}

		public Builder requireParameters(@Nullable Boolean requireParameters) {
			this.requireParameters = requireParameters;
			return this;
		}

		public Builder dataCollection(@Nullable String dataCollection) {
			this.dataCollection = dataCollection;
			return this;
		}

		public Builder dataCollection(@Nullable OpenRouterDataCollection dataCollection) {
			this.dataCollection = dataCollection != null ? dataCollection.value() : null;
			return this;
		}

		public Builder order(@Nullable List<String> order) {
			this.order = order;
			return this;
		}

		public Builder ignore(@Nullable List<String> ignore) {
			this.ignore = ignore;
			return this;
		}

		public Builder quantizations(@Nullable List<String> quantizations) {
			this.quantizations = quantizations;
			return this;
		}

		public Builder quantizations(OpenRouterQuantization @Nullable ... quantizations) {
			this.quantizations = quantizations == null ? null
					: Arrays.stream(quantizations)
						.map(quantization -> quantization != null ? quantization.value() : null)
						.toList();
			return this;
		}

		public Builder sort(@Nullable String sort) {
			this.sort = sort;
			return this;
		}

		public Builder sort(@Nullable OpenRouterProviderSort sort) {
			this.sort = sort != null ? sort.value() : null;
			return this;
		}

		public OpenRouterProviderPreferences build() {
			return new OpenRouterProviderPreferences(this.allowFallbacks, this.requireParameters, this.dataCollection,
					this.order, this.ignore, this.quantizations, this.sort);
		}

	}
}
