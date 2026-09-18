package de.subhransu.openrouter.springai.api.dto;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.subhransu.openrouter.springai.errors.TolerantJsonStringDeserializer;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public record ResponsesResult(@Nullable String id, @Nullable String object,
		@JsonProperty("created_at") @Nullable Long createdAt, @Nullable String model, @Nullable String status,
		@Nullable List<@Nullable ResponsesOutputItem> output, @Nullable Usage usage, @Nullable StreamError error,
		@JsonProperty("incomplete_details") @Nullable IncompleteDetails incompleteDetails, @JsonDeserialize(
				using = TolerantJsonStringDeserializer.class) @JsonProperty("error_type") @Nullable String errorType) {

	public ResponsesResult(@Nullable String id, @Nullable String object, @Nullable Long createdAt,
			@Nullable String model, @Nullable String status, @Nullable List<@Nullable ResponsesOutputItem> output,
			@Nullable Usage usage, @Nullable StreamError error, @Nullable IncompleteDetails incompleteDetails) {
		this(id, object, createdAt, model, status, output, usage, error, incompleteDetails, null);
	}

	public ResponsesResult(@Nullable String id, @Nullable String object, @Nullable Long createdAt,
			@Nullable String model, @Nullable String status, @Nullable List<@Nullable ResponsesOutputItem> output,
			@Nullable Usage usage, @Nullable StreamError error) {
		this(id, object, createdAt, model, status, output, usage, error, null, null);
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(Include.NON_NULL)
	public record IncompleteDetails(@Nullable String reason) {
	}
}
