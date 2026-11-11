package de.subhransu.openrouter.springai.embedding;

import de.subhransu.openrouter.springai.api.dto.ArchitectureDtoFixtures;

/**
 * Fixtures that sit inside the {@code embedding} package so architecture rules can be
 * shown to accept and reject them; see {@code ArchitectureRuleTests}.
 */
public final class ArchitectureEmbeddingFixtures {

	private ArchitectureEmbeddingFixtures() {
	}

	public interface DtoUser {

		ArchitectureDtoFixtures.Tolerant dto();

	}

	/** Named like the model class that the mapper-seam rule temporarily exempts. */
	public interface OpenRouterEmbeddingModel {

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
