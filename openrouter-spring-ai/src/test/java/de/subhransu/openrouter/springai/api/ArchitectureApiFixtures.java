package de.subhransu.openrouter.springai.api;

import de.subhransu.openrouter.springai.api.dto.ArchitectureDtoFixtures;
import de.subhransu.openrouter.springai.chat.ArchitectureChatFixtures;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Fixtures that sit inside the {@code api} package so architecture rules can be shown to
 * accept and reject them; see {@code ArchitectureRuleTests}.
 */
public final class ArchitectureApiFixtures {

	private ArchitectureApiFixtures() {
	}

	public interface HttpClients {

		RestClient restClient();

		WebClient webClient();

		ServerSentEvent<String> event();

	}

	public interface DependsOnChat {

		ArchitectureChatFixtures.DtoUser chat();

	}

	public interface DependsOnSpringAi {

		UserMessage message();

	}

	public interface Clean {

		String value();

	}

	public interface DtoUser {

		ArchitectureDtoFixtures.Tolerant dto();

	}

	/** Not in a scoped options package, so its setter is allowed. */
	public interface SetterOptions {

		void setModel(String model);

	}

}
