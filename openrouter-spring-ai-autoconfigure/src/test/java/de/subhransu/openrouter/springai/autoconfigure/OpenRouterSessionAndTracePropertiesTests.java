package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterTrace;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingModel;
import de.subhransu.openrouter.springai.image.OpenRouterImageModel;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("configuration")
@Tag("configuration.properties")
class OpenRouterSessionAndTracePropertiesTests {

	private static final OpenRouterTrace TRACE = new OpenRouterTrace("trace-1", "synthetic-app", "span", "generation",
			"parent", Map.of("tenant", "synthetic"));

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(
				AutoConfigurations.of(OpenRouterApiAutoConfiguration.class, OpenRouterChatAutoConfiguration.class,
						OpenRouterEmbeddingAutoConfiguration.class, OpenRouterImageAutoConfiguration.class))
		.withPropertyValues("spring.ai.openrouter.api-key=synthetic-key",
				"spring.ai.openrouter.embedding.model=synthetic/embedding",
				"spring.ai.openrouter.image.model=synthetic/image");

	@Test
	void bindsSessionAndTraceDefaultsForEveryModel() {
		this.runner.withPropertyValues(properties("chat"))
			.withPropertyValues(properties("embedding"))
			.withPropertyValues(properties("image"))
			.run(context -> {
				assertThat(context).hasNotFailed();
				OpenRouterChatOptions chat = (OpenRouterChatOptions) context.getBean(OpenRouterChatModel.class)
					.getOptions();
				var embedding = context.getBean(OpenRouterEmbeddingModel.class).getOptions();
				var image = context.getBean(OpenRouterImageModel.class).getOptions();
				assertThat(chat.getSessionId()).isEqualTo("default-session");
				assertThat(chat.getTrace()).isEqualTo(TRACE);
				assertThat(embedding.getSessionId()).isEqualTo("default-session");
				assertThat(embedding.getTrace()).isEqualTo(TRACE);
				assertThat(image.getSessionId()).isEqualTo("default-session");
				assertThat(image.getTrace()).isEqualTo(TRACE);
			});
	}

	@Test
	void unsetPropertiesLeaveSessionAndTraceUnset() {
		this.runner.run(context -> {
			OpenRouterChatOptions chat = (OpenRouterChatOptions) context.getBean(OpenRouterChatModel.class)
				.getOptions();
			assertThat(chat.getSessionId()).isNull();
			assertThat(chat.getTrace()).isNull();
			assertThat(context.getBean(OpenRouterEmbeddingModel.class).getOptions().getTrace()).isNull();
			assertThat(context.getBean(OpenRouterImageModel.class).getOptions().getSessionId()).isNull();
		});
	}

	@Test
	void invalidSessionAndTracePropertiesFailStartup() {
		this.runner.withPropertyValues("spring.ai.openrouter.chat.session-id=" + "x".repeat(257))
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("sessionId must be at most 256 characters"));
		this.runner.withPropertyValues("spring.ai.openrouter.embedding.trace.metadata.trace_id=duplicate")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("trace metadata key \"trace_id\""));
	}

	private static String[] properties(String model) {
		String prefix = "spring.ai.openrouter." + model + ".";
		return new String[] { prefix + "session-id=default-session", prefix + "trace.trace-id=trace-1",
				prefix + "trace.trace-name=synthetic-app", prefix + "trace.span-name=span",
				prefix + "trace.generation-name=generation", prefix + "trace.parent-span-id=parent",
				prefix + "trace.metadata.tenant=synthetic" };
	}

}
