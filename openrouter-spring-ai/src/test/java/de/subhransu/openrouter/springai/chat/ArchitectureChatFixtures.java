package de.subhransu.openrouter.springai.chat;

import de.subhransu.openrouter.springai.api.dto.ArchitectureDtoFixtures;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Fixtures that sit inside the {@code chat} package so architecture rules can be shown to
 * accept and reject them; see {@code ArchitectureRuleTests}.
 */
public final class ArchitectureChatFixtures {

	private ArchitectureChatFixtures() {
	}

	public interface DtoUser {

		ArchitectureDtoFixtures.Tolerant dto();

	}

	/** Named like the model class that the mapper-seam rule temporarily exempts. */
	public interface OpenRouterChatModel {

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

	public interface CopyOptions {

		Object copy();

	}

	public interface RestClientUser {

		RestClient restClient();

	}

	public interface WebClientUser {

		WebClient webClient();

	}

	public interface CodecUser {

		ServerSentEvent<String> event();

	}

}
