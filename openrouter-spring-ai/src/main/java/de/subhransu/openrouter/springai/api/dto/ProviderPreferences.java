package de.subhransu.openrouter.springai.api.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import de.subhransu.openrouter.springai.support.RequestExtensions;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public record ProviderPreferences(@JsonProperty("allow_fallbacks") @Nullable Boolean allowFallbacks,
		@JsonProperty("require_parameters") @Nullable Boolean requireParameters,
		@JsonProperty("data_collection") @Nullable String dataCollection, @Nullable List<String> order,
		@Nullable List<String> ignore, @Nullable List<String> quantizations,
		@JsonProperty("sort") @Nullable String sort, @JsonAnyGetter @Nullable Map<String, @Nullable Object> extraBody) {
	public ProviderPreferences {
		extraBody = RequestExtensions.provider(extraBody, sort);
	}

	public ProviderPreferences(@Nullable Boolean allowFallbacks, @Nullable Boolean requireParameters,
			@Nullable String dataCollection, @Nullable List<String> order, @Nullable List<String> ignore,
			@Nullable List<String> quantizations, @Nullable String sort) {
		this(allowFallbacks, requireParameters, dataCollection, order, ignore, quantizations, sort, null);
	}

}
