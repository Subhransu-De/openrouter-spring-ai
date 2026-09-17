package de.subhransu.openrouter.springai.garage;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterApiAutoConfiguration;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterChatAutoConfiguration;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterEmbeddingAutoConfiguration;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterImageAutoConfiguration;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingModel;
import de.subhransu.openrouter.springai.garage.cli.GarageCommand;
import de.subhransu.openrouter.springai.garage.evidence.GarageEvidence;
import de.subhransu.openrouter.springai.garage.evidence.GarageTelemetry;
import de.subhransu.openrouter.springai.garage.evidence.GarageTransportEvidence;
import de.subhransu.openrouter.springai.garage.scenes.GarageScene;
import de.subhransu.openrouter.springai.image.OpenRouterImageModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.core.retry.RetryTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.image.ImageModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException;
import org.springframework.core.env.SimpleCommandLinePropertySource;

/**
 * Sample-wiring smoke test for the Garage demo. It builds the full sample context with a
 * dummy API key and asserts the OpenRouter beans plus the garage collaborators wire up.
 *
 * <p>
 * {@link ApplicationContextRunner} constructs beans but does not invoke
 * {@code CommandLineRunner}s, so {@code GarageRunner.run(...)} never fires and no real
 * OpenRouter call is made. This catches missing beans or broken sample configuration
 * without touching the network or needing a real API key.
 */
class GarageApplicationContextTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(OpenRouterApiAutoConfiguration.class,
				OpenRouterChatAutoConfiguration.class, OpenRouterEmbeddingAutoConfiguration.class,
				OpenRouterImageAutoConfiguration.class))
		.withUserConfiguration(GarageApplication.class)
		.withPropertyValues("spring.ai.openrouter.api-key=garage-test-key", "spring.main.banner-mode=off");

	@Test
	void garageContextWiresUpAllBeansWithoutNetworkCall() {
		this.contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(OpenRouterApi.class);
			assertThat(context).hasSingleBean(OpenRouterChatModel.class);
			assertThat(context).hasSingleBean(ChatModel.class);
			assertThat(context).hasSingleBean(OpenRouterEmbeddingModel.class);
			assertThat(context).hasSingleBean(EmbeddingModel.class);
			assertThat(context).hasSingleBean(OpenRouterImageModel.class);
			assertThat(context).hasSingleBean(ImageModel.class);
			// Garage-specific beans and bound properties. GarageTools is created inside the
			// runner per run, so it is intentionally not a context bean.
			assertThat(context).hasSingleBean(GarageProperties.class);
			assertThat(context).hasSingleBean(GarageRunner.class);
			assertThat(context).hasSingleBean(GarageOptionsFactory.class);
			assertThat(context).hasSingleBean(GarageEvidence.class);
			assertThat(context).hasSingleBean(GarageTelemetry.class);
			assertThat(context).hasSingleBean(GarageTransportEvidence.class);
			assertThat(context).hasSingleBean(ObservationRegistry.class);
			assertThat(context).hasSingleBean(SimpleMeterRegistry.class);
			assertThat(context).hasSingleBean(RetryTemplate.class);
			assertThat(context).getBeans(GarageScene.class).hasSize(9);
		});
	}

	@Test
	void garagePropertiesBindFromConfiguration() {
		this.contextRunner
			.withPropertyValues("garage.foreman-model=openai/gpt-5.4", "garage.specialist-model=openai/gpt-5.4-mini")
			.run(context -> {
				GarageProperties properties = context.getBean(GarageProperties.class);
				assertThat(properties.getForemanModel()).isEqualTo("openai/gpt-5.4");
				assertThat(properties.getSpecialistModel()).isEqualTo("openai/gpt-5.4-mini");
			});
	}

	@Test
	void bootArgumentsBindPropertiesAndGarageOverridesRemainAuthoritative() {
		String[] args = { "--spring.profiles.active=coverage", "--spring.main.banner-mode=off",
				"--garage.stream=true", "--garage.specialistModel=synthetic/configured",
				"--garage.max-completion-tokens=123", "--specialist-model=synthetic/override",
				"--max-completion-tokens=456" };
		this.contextRunner.withInitializer(context -> context.getEnvironment().getPropertySources()
			.addFirst(new SimpleCommandLinePropertySource(args))).run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getEnvironment().getActiveProfiles()).contains("coverage");
				assertThat(context.getEnvironment().getProperty("spring.main.banner-mode")).isEqualTo("off");
				GarageProperties properties = context.getBean(GarageProperties.class);
				assertThat(properties.isStream()).isTrue();
				assertThat(properties.getSpecialistModel()).isEqualTo("synthetic/configured");
				assertThat(properties.getMaxCompletionTokens()).isEqualTo(123);
				GarageCommand command = GarageCommand.from(args, properties);
				assertThat(command.sceneIds()).containsExactly("service-story", "streaming-dispatch");
				assertThat(command.specialistModel()).isEqualTo("synthetic/override");
				assertThat(properties.getMaxCompletionTokens()).isEqualTo(456);
			});
	}

	@Test
	void chatModelAutoConfigurationBacksOffForAUserModel() {
		ChatModel customModel = org.mockito.Mockito.mock(ChatModel.class);
		this.contextRunner.withBean(ChatModel.class, () -> customModel).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(ChatModel.class);
			assertThat(context).doesNotHaveBean(OpenRouterChatModel.class);
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "garage.streem", "garage.specialst-model" })
	void unknownGaragePropertiesFailBindingBeforeInference(String key) {
		this.contextRunner.withInitializer(context -> context.getEnvironment().getPropertySources()
			.addFirst(new SimpleCommandLinePropertySource("--" + key + "=true"))).run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure())
					.hasRootCauseInstanceOf(UnboundConfigurationPropertiesException.class)
					.hasStackTraceContaining(key);
			});
	}

	@Test
	void disablingGarageRemainsAValidBoundProperty() {
		this.contextRunner.withInitializer(context -> context.getEnvironment().getPropertySources()
			.addFirst(new SimpleCommandLinePropertySource("--garage.enabled=false"))).run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(GarageProperties.class).isEnabled()).isFalse();
				assertThat(context).doesNotHaveBean(GarageRunner.class);
			});
	}

}
