package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.subhransu.openrouter.springai.chat.mapper.OpenRouterChatRequestMapper;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterResponsesRequestMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

class OpenRouterExtensionPropertiesTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

	@Test
	void propertyExtensionsUseTheSameWirePolicyAsJavaOptions() {
		this.runner
			.withPropertyValues("spring.ai.openrouter.chat.extra-body.[logprobs]=false",
					"spring.ai.openrouter.chat.extra-body.[prompt_cache_key]=synthetic",
					"spring.ai.openrouter.chat.provider-extra-body.[zdr]=false",
					"spring.ai.openrouter.chat.provider-extra-body.[sort].by=latency",
					"spring.ai.openrouter.chat.provider-extra-body.[only][0]=synthetic-provider",
					"spring.ai.openrouter.chat.provider-extra-body.[preferred_max_latency]=0")
			.run(context -> {
				assertThat(context).hasNotFailed();
				var options = context.getBean(OpenRouterChatProperties.class).toOptions();
				var json = new ObjectMapper();
				var wire = json
					.valueToTree(new OpenRouterChatRequestMapper(json).map(List.of(), options, false, List.of()));
				assertThat(wire.get("logprobs").isBoolean()).isTrue();
				assertThat(wire.get("logprobs").asBoolean()).isFalse();
				assertThat(wire.get("provider").get("zdr").asBoolean()).isFalse();
				assertThat(wire.get("provider").get("sort").get("by").asString()).isEqualTo("latency");
				assertThat(wire.get("provider").get("only").get(0).asString()).isEqualTo("synthetic-provider");
				assertThat(wire.get("provider").get("preferred_max_latency").asInt()).isZero();
				assertThatThrownBy(
						() -> new OpenRouterResponsesRequestMapper(json).map(List.of(), options, true, List.of()))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("logprobs");
			});
	}

	@Test
	void propertiesCannotReplaceStandardRequestFields() {
		this.runner.withPropertyValues("spring.ai.openrouter.chat.extra-body.[model]=synthetic")
			.run(context -> assertThatThrownBy(() -> context.getBean(OpenRouterChatProperties.class).toOptions())
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("model"));
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(OpenRouterChatProperties.class)
	static class Config {

	}

}
