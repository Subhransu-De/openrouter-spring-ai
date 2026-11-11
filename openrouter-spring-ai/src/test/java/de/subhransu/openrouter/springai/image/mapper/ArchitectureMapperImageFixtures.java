package de.subhransu.openrouter.springai.image.mapper;

import de.subhransu.openrouter.springai.api.dto.ArchitectureDtoFixtures;

/**
 * Fixture inside the {@code image.mapper} seam package, where wire DTOs are allowed; see
 * {@code ArchitectureRuleTests}.
 */
public final class ArchitectureMapperImageFixtures {

	private ArchitectureMapperImageFixtures() {
	}

	public interface DtoUser {

		ArchitectureDtoFixtures.Tolerant dto();

	}

}
