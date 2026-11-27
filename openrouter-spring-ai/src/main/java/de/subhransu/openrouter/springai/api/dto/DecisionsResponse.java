package de.subhransu.openrouter.springai.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;
import org.jspecify.annotations.Nullable;

// Response of POST /api/alpha/decisions: one typed answer per question name.
@JsonIgnoreProperties(ignoreUnknown = true)
public record DecisionsResponse(@Nullable String id, @Nullable String model, @Nullable String provider,
		@Nullable Map<String, @Nullable Answer> answers, @Nullable Usage usage) {

	/**
	 * One answer. {@code type} names which value is set: {@code noul}, {@code choice} or
	 * {@code score}; a {@code refusal} carries none.
	 *
	 * @param type the answer type
	 * @param noul the probability of yes
	 * @param choice the selected option
	 * @param score the probability-weighted level
	 * @param probabilities the probability of each option, or of each level by index
	 * @param confidence the provider's confidence
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Answer(@Nullable String type, @Nullable Double noul, @Nullable String choice, @Nullable Double score,
			@Nullable Map<String, @Nullable Double> probabilities, @Nullable Double confidence) {
	}

}
