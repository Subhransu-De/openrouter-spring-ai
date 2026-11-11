package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.dto.EmbeddingsRequest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

class OpenRouterTransportOwnershipTests {

	private static final HttpClientSettings BOOT_TIMEOUTS = HttpClientSettings.defaults()
		.withTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(3));

	@Test
	void composesTimeoutsWithoutChangingApplicationSingletonBuilder() {
		String timeout = "15s";
		AtomicReference<HttpHeaders> unrelatedHeaders = new AtomicReference<>();
		AtomicReference<HttpHeaders> providerHeaders = new AtomicReference<>();
		AtomicReference<HttpClientSettings> settings = new AtomicReference<>();
		RestClient.Builder supplied = RestClient.builder()
			.baseUrl("https://unrelated.invalid")
			.defaultHeader("X-Synthetic", "shared")
			.requestFactory(RecordingTransport.headerCapturingFactory(unrelatedHeaders));
		new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(OpenRouterApiAutoConfiguration.class))
			.withBean(RestClient.Builder.class, () -> supplied)
			.withBean(HttpClientSettings.class,
					() -> HttpClientSettings.defaults().withTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(3)))
			.withBean(ClientHttpRequestFactoryBuilder.class, () -> configured -> {
				settings.set(configured);
				return RecordingTransport.headerCapturingFactory(providerHeaders);
			})
			.withPropertyValues("spring.ai.openrouter.api-key=synthetic-key",
					"spring.ai.openrouter.base-url=https://openrouter.invalid/api/v1",
					"spring.ai.openrouter.app.title=Synthetic", "spring.ai.openrouter.connection.timeout=" + timeout)
			.run(context -> {
				assertThat(context).hasNotFailed();
				context.getBean(OpenRouterApi.class)
					.embeddings(new EmbeddingsRequest("synthetic", List.of("input"), null, null, null, null));
				assertThat(providerHeaders.get().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer synthetic-key");
				assertThat(providerHeaders.get().getFirst("X-Synthetic")).isEqualTo("shared");
				// An explicit connection.timeout is more specific than Boot's shared
				// timeouts, so it bounds OpenRouter's blocking connect and read.
				assertThat(settings.get())
					.extracting(HttpClientSettings::connectTimeout, HttpClientSettings::readTimeout)
					.containsExactly(Duration.parse("PT" + timeout), Duration.parse("PT" + timeout));
				assertThat(context.getBean(HttpClientSettings.class).readTimeout()).isEqualTo(Duration.ofSeconds(3));

				supplied.build().get().uri("/resource").retrieve().toBodilessEntity();
				assertThat(unrelatedHeaders.get()).isNotNull();
				assertThat(unrelatedHeaders.get().headerNames()).doesNotContain(HttpHeaders.AUTHORIZATION,
						"HTTP-Referer", "X-OpenRouter-Title", "X-OpenRouter-Categories");
				assertThat(unrelatedHeaders.get().getFirst("X-Synthetic")).isEqualTo("shared");
			});
	}

	static Stream<Arguments> precedenceTable() {
		HttpClientSettings bootReadOnly = HttpClientSettings.defaults().withReadTimeout(Duration.ofSeconds(3));
		return Stream.of(
				row("Boot connect timeout applies when no OpenRouter connect setting exists", BOOT_TIMEOUTS, List.of(),
						Duration.ofSeconds(2), Duration.ofMinutes(2)),
				row("an explicit timeout wins over Boot's connect timeout", BOOT_TIMEOUTS,
						List.of("spring.ai.openrouter.connection.timeout=45s"), Duration.ofSeconds(45),
						Duration.ofSeconds(45)),
				row("a timeout also bounds connect when Boot sets only a read timeout", bootReadOnly,
						List.of("spring.ai.openrouter.connection.timeout=15s"), Duration.ofSeconds(15),
						Duration.ofSeconds(15)),
				row("an explicit connect timeout wins over Boot's connect timeout", BOOT_TIMEOUTS,
						List.of("spring.ai.openrouter.connection.connect-timeout=7s"), Duration.ofSeconds(7),
						Duration.ofMinutes(2)),
				row("an explicit connect timeout wins over an explicit timeout", BOOT_TIMEOUTS,
						List.of("spring.ai.openrouter.connection.timeout=5m",
								"spring.ai.openrouter.connection.connect-timeout=7s"),
						Duration.ofSeconds(7), Duration.ofMinutes(5)),
				row("the default timeout bounds connect and read when Boot sets none", HttpClientSettings.defaults(),
						List.of(), Duration.ofMinutes(2), Duration.ofMinutes(2)));
	}

	private static Arguments row(String name, HttpClientSettings boot, List<String> properties, Duration connect,
			Duration read) {
		return Arguments.of(Named.of(name, new Precedence(boot, properties, connect, read)));
	}

	@ParameterizedTest
	@MethodSource("precedenceTable")
	void blockingTimeoutsFollowTheDocumentedPrecedence(Precedence row) {
		assertThat(blockingSettings(row.boot(), row.properties().toArray(String[]::new)))
			.extracting(HttpClientSettings::connectTimeout, HttpClientSettings::readTimeout)
			.containsExactly(row.connect(), row.read());
	}

	private HttpClientSettings blockingSettings(HttpClientSettings shared, String... properties) {
		AtomicReference<HttpClientSettings> settings = new AtomicReference<>();
		new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(OpenRouterApiAutoConfiguration.class))
			.withBean(HttpClientSettings.class, () -> shared)
			.withBean(ClientHttpRequestFactoryBuilder.class, () -> configured -> {
				settings.set(configured);
				return RecordingTransport.headerCapturingFactory(new AtomicReference<>());
			})
			.withPropertyValues("spring.ai.openrouter.api-key=synthetic-key")
			.withPropertyValues(properties)
			.run(context -> assertThat(context).hasNotFailed());
		return settings.get();
	}

	/**
	 * One row of the blocking-timeout precedence table: Boot's shared settings, the
	 * OpenRouter properties, and the connect and read timeouts that the request factory
	 * must receive.
	 */
	record Precedence(HttpClientSettings boot, List<String> properties, Duration connect, Duration read) {

	}

}
