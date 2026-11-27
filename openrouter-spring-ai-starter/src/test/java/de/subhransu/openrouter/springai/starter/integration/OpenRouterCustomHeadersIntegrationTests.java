package de.subhransu.openrouter.springai.starter.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterApiAutoConfiguration;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterChatAutoConfiguration;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterConnectionDetails;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Custom headers on the wire, sent by the auto-configured chat model to the OpenRouter
 * mock in both request modes, in calls and streams. The connection comes from an
 * {@link OpenRouterConnectionDetails} bean, as a service connection would supply it,
 * while the properties name an address that does not resolve.
 */
@Tag("http")
@Tag("api.wire")
class OpenRouterCustomHeadersIntegrationTests {

	// The attribution stubs answer both request modes, in calls and streams.
	private static final Map<String, Object> ATTRIBUTION = Map.of("sceneId", "attribution-check-in");

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(
				AutoConfigurations.of(OpenRouterApiAutoConfiguration.class, OpenRouterChatAutoConfiguration.class))
		.withBean(OpenRouterConnectionDetails.class, MockConnection::new)
		.withPropertyValues("spring.ai.openrouter.api-key=unused-property-key",
				"spring.ai.openrouter.base-url=https://openrouter.invalid/api/v1",
				"spring.ai.openrouter.app.title=Garage", "spring.ai.openrouter.chat.model=synthetic/chat",
				"spring.ai.openrouter.chat.custom-headers.X-Garage-Bay=3",
				"spring.ai.openrouter.chat.custom-headers.X-Request-Tag=default");

	@BeforeEach
	void resetMock() {
		OpenRouterMock.reset();
	}

	@ParameterizedTest
	@EnumSource(OpenRouterRequestMode.class)
	void requestHeadersMergeIntoTheDefaultsInCallsAndStreams(OpenRouterRequestMode mode) {
		this.contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			ChatClient chat = ChatClient.create(context.getBean(ChatModel.class));
			OpenRouterChatOptions.Builder options = OpenRouterChatOptions.builder()
				.requestMode(mode)
				.metadata(ATTRIBUTION)
				.customHeaders(Map.of("x-request-tag", "run-7"));
			chat.prompt("Is the car ready?").options(options).call().content();
			chat.prompt("Is the car ready?").options(options).stream().content().blockLast(Duration.ofSeconds(10));
		});

		String path = "/api/v1" + (mode == OpenRouterRequestMode.RESPONSES ? "/responses" : "/chat/completions");
		List<LoggedRequest> requests = OpenRouterMock.server()
			.getAllServeEvents()
			.stream()
			.map(ServeEvent::getRequest)
			.filter(request -> request.getUrl().equals(path))
			.sorted(Comparator.comparing(LoggedRequest::getLoggedDate))
			.toList();
		assertThat(OpenRouterMock.unmatched()).isEmpty();
		assertThat(requests).hasSize(2);
		assertThat(requests.get(1).getBodyAsString()).contains("\"stream\":true");
		assertThat(requests).allSatisfy(request -> {
			assertThat(request.getHeader("X-Garage-Bay")).isEqualTo("3");
			assertThat(request.getHeader("X-Request-Tag")).isEqualTo("run-7");
			assertThat(request.getHeader("Authorization")).isEqualTo("Bearer garage-mock-key");
			assertThat(request.getHeader("X-OpenRouter-Title")).isEqualTo("Garage");
		});
	}

	private static final class MockConnection implements OpenRouterConnectionDetails {

		@Override
		public @Nullable String getBaseUrl() {
			return OpenRouterMock.baseUrl();
		}

		@Override
		public String getApiKey() {
			return "garage-mock-key";
		}

	}

}
