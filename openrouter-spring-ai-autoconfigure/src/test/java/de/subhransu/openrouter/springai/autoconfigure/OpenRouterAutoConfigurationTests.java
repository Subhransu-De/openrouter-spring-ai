package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterStreamingToolCallAggregator;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;

class OpenRouterAutoConfigurationTests {

	private static final String API_KEY_PROPERTY = "spring.ai.openrouter.api-key=test-key";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withConfiguration(
			AutoConfigurations.of(OpenRouterApiAutoConfiguration.class, OpenRouterChatAutoConfiguration.class));

	@ParameterizedTest
	@ValueSource(strings = { "0B", "-1B", "2147483647B", "2147483648B", "3GB" })
	void rejectsUnsupportedBodySizesDuringBinding(String value) {
		for (String property : new String[] { "max-response-body-size", "max-error-body-size" }) {
			contextRunner
				.withPropertyValues(API_KEY_PROPERTY, "spring.ai.openrouter.connection." + property + "=" + value)
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
						.hasStackTraceContaining("spring.ai.openrouter.connection." + property
								+ " must be between 1 and 2147483646 bytes");
				});
		}
	}

	@Test
	void createsApiAndChatModelWhenApiKeyIsConfigured() {
		contextRunner.withPropertyValues(API_KEY_PROPERTY).run(context -> {
			assertThat(context).hasSingleBean(OpenRouterApi.class);
			assertThat(context).hasSingleBean(OpenRouterChatModel.class);
		});
	}

	@Test
	void bindsConnectionTimeoutProperty() {
		contextRunner.withPropertyValues(API_KEY_PROPERTY, "spring.ai.openrouter.connection.timeout=45s")
			.run(context -> {
				assertThat(context).hasSingleBean(OpenRouterConnectionProperties.class);
				assertThat(context.getBean(OpenRouterConnectionProperties.class).getTimeout())
					.isEqualTo(java.time.Duration.ofSeconds(45));
				assertThat(context).hasSingleBean(OpenRouterApi.class);
			});
	}

	@Test
	void bindsAndWiresResponseAndToolCallLimits() {
		contextRunner
			.withPropertyValues(API_KEY_PROPERTY, "spring.ai.openrouter.connection.max-response-body-size=96MB",
					"spring.ai.openrouter.connection.max-error-body-size=32KB",
					"spring.ai.openrouter.chat.tool-call-aggregation.max-size=2MB",
					"spring.ai.openrouter.chat.tool-call-aggregation.max-chunks=200",
					"spring.ai.openrouter.chat.tool-call-aggregation.max-duration=30s")
			.run(context -> {
				OpenRouterConnectionProperties connection = context.getBean(OpenRouterConnectionProperties.class);
				assertThat(connection.getMaxResponseBodySize().toMegabytes()).isEqualTo(96);
				assertThat(connection.getMaxErrorBodySize().toKilobytes()).isEqualTo(32);

				OpenRouterChatProperties chat = context.getBean(OpenRouterChatProperties.class);
				assertThat(chat.getToolCallAggregation().getMaxSize().toMegabytes()).isEqualTo(2);
				assertThat(chat.getToolCallAggregation().getMaxChunks()).isEqualTo(200);
				assertThat(chat.getToolCallAggregation().getMaxDuration()).isEqualTo(Duration.ofSeconds(30));

				OpenRouterStreamingToolCallAggregator aggregator = (OpenRouterStreamingToolCallAggregator) org.springframework.test.util.ReflectionTestUtils
					.getField(context.getBean(OpenRouterChatModel.class), "streamingToolCallAggregator");
				assertThat(org.springframework.test.util.ReflectionTestUtils.getField(aggregator, "maxBytes"))
					.isEqualTo(2L * 1024 * 1024);
				assertThat(org.springframework.test.util.ReflectionTestUtils.getField(aggregator, "maxChunks"))
					.isEqualTo(200);
				assertThat(org.springframework.test.util.ReflectionTestUtils.getField(aggregator, "maxDuration"))
					.isEqualTo(Duration.ofSeconds(30));
			});
	}

	@Test
	void injectsUserDefinedToolCallingManagerIntoChatModel() {
		contextRunner.withUserConfiguration(ToolCallingManagerConfiguration.class)
			.withPropertyValues(API_KEY_PROPERTY)
			.run(context -> {
				OpenRouterChatModel chatModel = context.getBean(OpenRouterChatModel.class);
				assertThat(org.springframework.test.util.ReflectionTestUtils.getField(chatModel, "toolCallingManager"))
					.isSameAs(context.getBean(org.springframework.ai.model.tool.ToolCallingManager.class));
			});
	}

	@Test
	void injectsUserDefinedObservationRegistryIntoChatModel() {
		contextRunner.withUserConfiguration(ObservationRegistryConfiguration.class)
			.withPropertyValues(API_KEY_PROPERTY)
			.run(context -> {
				OpenRouterChatModel chatModel = context.getBean(OpenRouterChatModel.class);
				assertThat(org.springframework.test.util.ReflectionTestUtils.getField(chatModel, "observationRegistry"))
					.isSameAs(context.getBean(io.micrometer.observation.ObservationRegistry.class));
			});
	}

	@Test
	void backsOffWhenUserDefinesOwnChatModel() {
		contextRunner.withUserConfiguration(CustomChatModelConfiguration.class)
			.withPropertyValues(API_KEY_PROPERTY)
			.run(context -> {
				assertThat(context).doesNotHaveBean(OpenRouterChatModel.class);
				assertThat(context).hasSingleBean(ChatModel.class);
			});
	}

	@Test
	void appliesTimeoutFactoryToTheRestClientBuilder() {
		// The auto-configuration must install a timeout-carrying request factory on
		// whatever
		// RestClient.Builder it uses -- including Spring Boot's default prototype builder
		// -- so
		// blocking
		// calls cannot hang. A recording builder captures the requestFactory() call.
		RecordingRestClientBuilderConfiguration.RECORDED_FACTORY.set(null);
		contextRunner.withUserConfiguration(RecordingRestClientBuilderConfiguration.class)
			.withPropertyValues(API_KEY_PROPERTY, "spring.ai.openrouter.connection.timeout=15s")
			.run(context -> {
				assertThat(context).hasSingleBean(OpenRouterApi.class);
				assertThat(RecordingRestClientBuilderConfiguration.RECORDED_FACTORY.get())
					.as("auto-configuration installs a request factory carrying the timeout")
					.isNotNull();
			});
	}

	@Test
	void customRequestFactoryBuilderIsSelectedWithComposedOpenRouterTimeouts() {
		RecordingRestClientBuilderConfiguration.RECORDED_FACTORY.set(null);
		ConfiguredRequestFactoryBuilderConfiguration.RECORDED_SETTINGS.set(null);
		contextRunner
			.withUserConfiguration(RecordingRestClientBuilderConfiguration.class,
					ConfiguredRequestFactoryBuilderConfiguration.class)
			.withPropertyValues(API_KEY_PROPERTY, "spring.ai.openrouter.connection.timeout=15s")
			.run(context -> {
				assertThat(context).hasSingleBean(OpenRouterApi.class);
				assertThat(RecordingRestClientBuilderConfiguration.RECORDED_FACTORY.get())
					.isInstanceOf(ConfiguredRequestFactoryBuilderConfiguration.MarkerFactory.class);
				assertThat(ConfiguredRequestFactoryBuilderConfiguration.RECORDED_SETTINGS.get())
					.extracting(HttpClientSettings::connectTimeout, HttpClientSettings::readTimeout)
					.containsExactly(Duration.ofSeconds(15), Duration.ofSeconds(15));
			});
	}

	@Test
	void timeoutFactoryReplacesFactoryInstalledDirectlyOnRestClientBuilder() {
		DirectFactoryRestClientBuilderConfiguration.RECORDED_FACTORY.set(null);
		ConfiguredRequestFactoryBuilderConfiguration.RECORDED_SETTINGS.set(null);
		contextRunner
			.withUserConfiguration(DirectFactoryRestClientBuilderConfiguration.class,
					ConfiguredRequestFactoryBuilderConfiguration.class)
			.withPropertyValues(API_KEY_PROPERTY, "spring.ai.openrouter.connection.timeout=15s")
			.run(context -> {
				assertThat(context).hasSingleBean(OpenRouterApi.class);
				assertThat(DirectFactoryRestClientBuilderConfiguration.RECORDED_FACTORY.get())
					.as("the provider timeout factory takes precedence over a directly installed factory")
					.isInstanceOf(ConfiguredRequestFactoryBuilderConfiguration.MarkerFactory.class);
				assertThat(ConfiguredRequestFactoryBuilderConfiguration.RECORDED_SETTINGS.get())
					.extracting(HttpClientSettings::connectTimeout, HttpClientSettings::readTimeout)
					.containsExactly(Duration.ofSeconds(15), Duration.ofSeconds(15));
			});
	}

	@Configuration(proxyBeanMethods = false)
	static class ToolCallingManagerConfiguration {

		@Bean
		org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor toolExecutionExceptionProcessor() {
			return exception -> "custom failure";
		}

		@Bean
		org.springframework.ai.model.tool.ToolCallingManager customToolCallingManager(
				org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor processor) {
			return org.springframework.ai.model.tool.ToolCallingManager.builder()
				.toolExecutionExceptionProcessor(processor)
				.build();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class ObservationRegistryConfiguration {

		@Bean
		io.micrometer.observation.ObservationRegistry customObservationRegistry() {
			return io.micrometer.observation.ObservationRegistry.create();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class CustomChatModelConfiguration {

		@Bean
		ChatModel customChatModel() {
			return org.mockito.Mockito.mock(ChatModel.class);
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class RecordingRestClientBuilderConfiguration {

		static final java.util.concurrent.atomic.AtomicReference<org.springframework.http.client.ClientHttpRequestFactory> RECORDED_FACTORY = new java.util.concurrent.atomic.AtomicReference<>();

		@Bean
		org.springframework.web.client.RestClient.Builder recordingRestClientBuilder() {
			org.springframework.web.client.RestClient.Builder spy = org.mockito.Mockito
				.spy(org.springframework.web.client.RestClient.builder());
			org.mockito.Mockito.doAnswer(invocation -> {
				RECORDED_FACTORY.set(invocation.getArgument(0));
				return invocation.callRealMethod();
			})
				.when(spy)
				.requestFactory(org.mockito.ArgumentMatchers
					.any(org.springframework.http.client.ClientHttpRequestFactory.class));
			return spy;
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class ConfiguredRequestFactoryBuilderConfiguration {

		static final java.util.concurrent.atomic.AtomicReference<HttpClientSettings> RECORDED_SETTINGS = new java.util.concurrent.atomic.AtomicReference<>();

		@Bean
		ClientHttpRequestFactoryBuilder<MarkerFactory> markerRequestFactoryBuilder() {
			return settings -> {
				RECORDED_SETTINGS.set(settings);
				return new MarkerFactory();
			};
		}

		static final class MarkerFactory implements ClientHttpRequestFactory {

			@Override
			public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
				throw new UnsupportedOperationException("marker factory is only captured by tests");
			}

		}

	}

	@Configuration(proxyBeanMethods = false)
	static class DirectFactoryRestClientBuilderConfiguration {

		static final java.util.concurrent.atomic.AtomicReference<ClientHttpRequestFactory> RECORDED_FACTORY = new java.util.concurrent.atomic.AtomicReference<>();

		@Bean
		org.springframework.web.client.RestClient.Builder directFactoryRestClientBuilder() {
			org.springframework.web.client.RestClient.Builder spy = org.mockito.Mockito
				.spy(org.springframework.web.client.RestClient.builder().requestFactory(new DirectFactory()));
			org.mockito.Mockito.doAnswer(invocation -> {
				RECORDED_FACTORY.set(invocation.getArgument(0));
				return invocation.callRealMethod();
			}).when(spy).requestFactory(org.mockito.ArgumentMatchers.any(ClientHttpRequestFactory.class));
			return spy;
		}

		static final class DirectFactory implements ClientHttpRequestFactory {

			@Override
			public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) {
				throw new UnsupportedOperationException("direct factory is only replaced by tests");
			}

		}

	}

}
