package de.subhransu.openrouter.springai.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

public final class ArchitectureDtoFixtures {

	private ArchitectureDtoFixtures() {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Tolerant(String value) {
	}

	@JsonIgnoreProperties(ignoreUnknown = false)
	public record Strict(String value) {
	}

	public record Unannotated(String value) {
	}

}
