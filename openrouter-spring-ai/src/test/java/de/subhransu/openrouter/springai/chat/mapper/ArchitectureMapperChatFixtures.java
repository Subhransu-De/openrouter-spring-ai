package de.subhransu.openrouter.springai.chat.mapper;

import de.subhransu.openrouter.springai.api.dto.ArchitectureDtoFixtures;

/**
 * Fixture inside the {@code chat.mapper} seam package, where wire DTOs are allowed; see
 * {@code ArchitectureRuleTests}.
 */
public final class ArchitectureMapperChatFixtures {

	private ArchitectureMapperChatFixtures() {
	}

	public interface DtoUser {

		ArchitectureDtoFixtures.Tolerant dto();

	}

}
