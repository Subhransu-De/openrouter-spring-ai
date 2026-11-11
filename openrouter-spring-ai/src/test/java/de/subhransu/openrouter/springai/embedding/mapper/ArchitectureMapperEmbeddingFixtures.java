package de.subhransu.openrouter.springai.embedding.mapper;

import de.subhransu.openrouter.springai.api.dto.ArchitectureDtoFixtures;

/**
 * Fixture inside the {@code embedding.mapper} seam package, where wire DTOs are allowed;
 * see {@code ArchitectureRuleTests}.
 */
public final class ArchitectureMapperEmbeddingFixtures {

	private ArchitectureMapperEmbeddingFixtures() {
	}

	public interface DtoUser {

		ArchitectureDtoFixtures.Tolerant dto();

	}

}
