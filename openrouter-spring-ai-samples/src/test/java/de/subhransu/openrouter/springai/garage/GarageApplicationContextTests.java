package de.subhransu.openrouter.springai.garage;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.autoconfigure.OpenRouterApiAutoConfiguration;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterChatAutoConfiguration;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterEmbeddingAutoConfiguration;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterImageAutoConfiguration;
import de.subhransu.openrouter.springai.garage.scenes.GarageScene;
import de.subhransu.openrouter.springai.garage.web.GarageRunController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException;
import org.springframework.core.env.SimpleCommandLinePropertySource;

/**
 * Sample-wiring smoke test for the Garage demo. It builds the full sample context with a
 * dummy API key and asserts the context starts and exposes every selectable scene.
 *
 * <p>
 * Runs start only through the HTTP API, so building the context makes no OpenRouter call.
 * This catches missing beans or broken sample configuration without touching the network
 * or needing a real API key.
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
			assertThat(context.getBeansOfType(GarageScene.class).values()).extracting(GarageScene::id)
				.containsExactlyInAnyOrderElementsOf(GarageRunRequests.plan("{\"full\":true}").sceneIds());
		});
	}

	@Test
	void bootArgumentsBindTheGarageProperties() {
		String[] args = { "--garage.stream=true", "--garage.foremanModel=synthetic/foreman",
				"--garage.specialistModel=synthetic/configured", "--garage.max-completion-tokens=123" };
		this.contextRunner.withInitializer(context -> context.getEnvironment().getPropertySources()
			.addFirst(new SimpleCommandLinePropertySource(args))).run(context -> {
				assertThat(context).hasNotFailed();
				GarageProperties properties = context.getBean(GarageProperties.class);
				assertThat(properties.isStream()).isTrue();
				assertThat(properties.getForemanModel()).isEqualTo("synthetic/foreman");
				assertThat(properties.getSpecialistModel()).isEqualTo("synthetic/configured");
				assertThat(properties.getMaxCompletionTokens()).isEqualTo(123);
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
				assertThat(context).doesNotHaveBean(GarageRunService.class);
				assertThat(context).doesNotHaveBean(GarageRunController.class);
			});
	}

}
