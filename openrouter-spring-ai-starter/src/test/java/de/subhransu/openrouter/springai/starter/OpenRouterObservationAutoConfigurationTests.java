package de.subhransu.openrouter.springai.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.EmbeddingsResponse;
import de.subhransu.openrouter.springai.api.dto.ImagesResponse;
import de.subhransu.openrouter.springai.api.dto.Usage;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingModel;
import de.subhransu.openrouter.springai.image.OpenRouterImageModel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.observation.ChatModelMeterObservationHandler;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.observation.EmbeddingModelMeterObservationHandler;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationConvention;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.observation.ImageModelObservationConvention;
import org.springframework.ai.image.observation.ImageModelPromptContentObservationHandler;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Starter-level regression tests for Spring AI's standard observation infrastructure. The
 * context uses classpath auto-configuration, just as a consuming Boot application does,
 * and supplies only the external OpenRouter transport boundary.
 */
@Tag("configuration")
@Tag("configuration.observability")
class OpenRouterObservationAutoConfigurationTests {

	private static final String CHAT_MODEL = "openai/gpt-5.4-mini";

	private static final String EMBEDDING_MODEL = "openai/text-embedding-3-small";

	private static final String IMAGE_MODEL = "bytedance-seed/seedream-4.5";

	private static final String OPERATION_METER = "gen_ai.client.operation";

	private static final String TOKEN_USAGE = "gen_ai.client.token.usage";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(TestApplication.class, MockApiConfiguration.class)
		.withPropertyValues("spring.ai.openrouter.chat.model=" + CHAT_MODEL,
				"spring.ai.openrouter.embedding.model=" + EMBEDDING_MODEL,
				"spring.ai.openrouter.image.model=" + IMAGE_MODEL, "spring.ai.image.observations.log-prompt=true");

	@Test
	void starterInstallsHandlersAndRecordsStandardMetersForEveryModelType() {
		this.contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(ObservationRegistry.class);
			assertThat(context).hasSingleBean(MeterRegistry.class);
			assertThat(context).hasSingleBean(ChatModelMeterObservationHandler.class);
			assertThat(context).hasSingleBean(EmbeddingModelMeterObservationHandler.class);
			assertThat(context).hasSingleBean(ImageModelPromptContentObservationHandler.class);

			OpenRouterApi api = context.getBean(OpenRouterApi.class);
			when(api.chatCompletion(any())).thenReturn(chatResponse());
			when(api.embeddings(any())).thenReturn(embeddingResponse());
			when(api.images(any())).thenReturn(imageResponse());

			context.getBean(OpenRouterChatModel.class).call(new Prompt("hello"));
			context.getBean(OpenRouterEmbeddingModel.class).embed("hello");
			context.getBean(OpenRouterImageModel.class).call(new ImagePrompt("a red panda"));

			MeterRegistry meters = context.getBean(MeterRegistry.class);
			assertDurationMeter(meters, CHAT_MODEL);
			assertDurationMeter(meters, EMBEDDING_MODEL);
			assertDurationMeter(meters, IMAGE_MODEL);
			assertTokenMeter(meters, CHAT_MODEL, "input", 3.0);
			assertTokenMeter(meters, CHAT_MODEL, "output", 2.0);
			assertTokenMeter(meters, CHAT_MODEL, "total", 5.0);
			assertTokenMeter(meters, EMBEDDING_MODEL, "input", 4.0);
			assertTokenMeter(meters, EMBEDDING_MODEL, "total", 4.0);
		});
	}

	@Test
	void userDefinedHandlersReplaceStandardHandlersWithoutDuplicates() {
		this.contextRunner.withUserConfiguration(CustomHandlerConfiguration.class).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(ChatModelMeterObservationHandler.class);
			assertThat(context.getBean(ChatModelMeterObservationHandler.class))
				.isSameAs(context.getBean("customChatModelMeterObservationHandler"));
			assertThat(context).hasSingleBean(EmbeddingModelMeterObservationHandler.class);
			assertThat(context.getBean(EmbeddingModelMeterObservationHandler.class))
				.isSameAs(context.getBean("customEmbeddingModelMeterObservationHandler"));
			assertThat(context).hasSingleBean(ImageModelPromptContentObservationHandler.class);
			assertThat(context.getBean(ImageModelPromptContentObservationHandler.class))
				.isSameAs(context.getBean("customImageModelPromptContentObservationHandler"));
		});
	}

	@Test
	void customObservationConventionsStillOverrideModelDefaults() {
		this.contextRunner.withUserConfiguration(CustomConventionConfiguration.class).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(OpenRouterChatModel.class)).extracting("observationConvention")
				.isSameAs(context.getBean(ChatModelObservationConvention.class));
			assertThat(context.getBean(OpenRouterEmbeddingModel.class)).extracting("observationConvention")
				.isSameAs(context.getBean(EmbeddingModelObservationConvention.class));
			assertThat(context.getBean(OpenRouterImageModel.class)).extracting("observationConvention")
				.isSameAs(context.getBean(ImageModelObservationConvention.class));
		});
	}

	private static void assertDurationMeter(MeterRegistry meters, String model) {
		assertThat(meters.find(OPERATION_METER)
			.tag("gen_ai.system", "openrouter")
			.tag("gen_ai.request.model", model)
			.timer()).isNotNull().extracting(timer -> timer.count()).isEqualTo(1L);
	}

	private static void assertTokenMeter(MeterRegistry meters, String model, String tokenType, double count) {
		assertThat(meters.find(TOKEN_USAGE)
			.tag("gen_ai.system", "openrouter")
			.tag("gen_ai.request.model", model)
			.tag("gen_ai.token.type", tokenType)
			.counter()).isNotNull().extracting(counter -> counter.count()).isEqualTo(count);
	}

	private static ChatCompletionResponse chatResponse() {
		return new ChatCompletionResponse("gen-1", "chat.completion", 123L, CHAT_MODEL, "openai",
				List.of(new Choice(0, new ChatMessage("assistant", "Hello.", null, null, null), null, "stop", "stop")),
				new Usage(3, 2, 5, null, null, null, null, null, null));
	}

	private static EmbeddingsResponse embeddingResponse() {
		return new EmbeddingsResponse("list",
				List.of(new EmbeddingsResponse.EmbeddingData("embedding", 0, new float[] { 0.25f, -0.5f })),
				EMBEDDING_MODEL, new Usage(4, null, 4, null, null, null, null, null, null));
	}

	private static ImagesResponse imageResponse() {
		return new ImagesResponse(1750000000L, List.of(new ImagesResponse.ImageData("aW1hZ2Ux", "image/png", null)),
				null);
	}

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	static class TestApplication {

	}

	@Configuration(proxyBeanMethods = false)
	static class MockApiConfiguration {

		@Bean
		OpenRouterApi openRouterApi() {
			return mock(OpenRouterApi.class);
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class CustomHandlerConfiguration {

		@Bean
		ChatModelMeterObservationHandler customChatModelMeterObservationHandler(MeterRegistry meterRegistry) {
			return new ChatModelMeterObservationHandler(meterRegistry);
		}

		@Bean
		EmbeddingModelMeterObservationHandler customEmbeddingModelMeterObservationHandler(MeterRegistry meterRegistry) {
			return new EmbeddingModelMeterObservationHandler(meterRegistry);
		}

		@Bean
		ImageModelPromptContentObservationHandler customImageModelPromptContentObservationHandler() {
			return new ImageModelPromptContentObservationHandler();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class CustomConventionConfiguration {

		@Bean
		ChatModelObservationConvention chatModelObservationConvention() {
			return mock(ChatModelObservationConvention.class);
		}

		@Bean
		EmbeddingModelObservationConvention embeddingModelObservationConvention() {
			return mock(EmbeddingModelObservationConvention.class);
		}

		@Bean
		ImageModelObservationConvention imageModelObservationConvention() {
			return mock(ImageModelObservationConvention.class);
		}

	}

}
