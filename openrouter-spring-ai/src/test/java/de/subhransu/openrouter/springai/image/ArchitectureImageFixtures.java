package de.subhransu.openrouter.springai.image;

import de.subhransu.openrouter.springai.api.dto.ArchitectureDtoFixtures;

/**
 * Fixtures that sit inside the {@code image} package so architecture rules can be shown
 * to accept and reject them; see {@code ArchitectureRuleTests}.
 */
public final class ArchitectureImageFixtures {

	private ArchitectureImageFixtures() {
	}

	public interface DtoUser {

		ArchitectureDtoFixtures.Tolerant dto();

	}

	/** Named like the model class that the mapper-seam rule temporarily exempts. */
	public interface OpenRouterImageModel {

		ArchitectureDtoFixtures.Tolerant dto();

	}

	public interface SetterOptions {

		void setModel(String model);

	}

	public interface MutateOptions {

		Object mutate();

		String model();

	}

	/** Not named like options, so its setter is allowed. */
	public interface SetterHolder {

		void setModel(String model);

	}

}
