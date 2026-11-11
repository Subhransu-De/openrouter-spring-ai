package de.subhransu.openrouter.springai.autoconfigure;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.EmbeddingsRequest;
import de.subhransu.openrouter.springai.api.dto.EmbeddingsResponse;
import de.subhransu.openrouter.springai.api.dto.ImagesResponse;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingModel;
import de.subhransu.openrouter.springai.image.OpenRouterImageModel;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Drives the auto-configured chat, embedding and image models against a stubbed
 * {@link OpenRouterApi}, so that a test observes how a collaborator was used by calling
 * the model instead of reading the model's private fields.
 */
final class SyntheticModels {

	static final String API_KEY = "spring.ai.openrouter.api-key=test-key";

	private SyntheticModels() {
	}

	/**
	 * A runner with the API and all three model auto-configurations.
	 * @return the runner
	 */
	static ApplicationContextRunner runner() {
		return new ApplicationContextRunner()
			.withConfiguration(
					AutoConfigurations.of(OpenRouterApiAutoConfiguration.class, OpenRouterChatAutoConfiguration.class,
							OpenRouterEmbeddingAutoConfiguration.class, OpenRouterImageAutoConfiguration.class))
			.withPropertyValues(API_KEY, "spring.ai.openrouter.embedding.model=synthetic/embedding",
					"spring.ai.openrouter.image.model=synthetic/image");
	}

	/**
	 * An API that answers every blocking call with a minimal valid response.
	 * @return the stubbed API
	 */
	static OpenRouterApi succeedingApi() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		ChatMessage reply = new ChatMessage("assistant", "ok", null, null, null);
		when(api.chatCompletion(any())).thenReturn(new ChatCompletionResponse("id", "chat.completion", 1L, "m", "p",
				List.of(new Choice(0, reply, null, "stop", null)), null));
		when(api.embeddings(any())).thenAnswer((invocation) -> {
			EmbeddingsRequest request = invocation.getArgument(0);
			int dimensions = request.dimensions() != null ? request.dimensions() : 1;
			return new EmbeddingsResponse("list",
					List.of(new EmbeddingsResponse.EmbeddingData("embedding", 0, new float[dimensions])), "m", null);
		});
		when(api.images(any()))
			.thenReturn(new ImagesResponse(1L, List.of(new ImagesResponse.ImageData("aW1n", "image/png", null)), null));
		return api;
	}

	/**
	 * An API whose every blocking call fails with the same exception.
	 * @param failure the failure to throw
	 * @return the stubbed API
	 */
	static OpenRouterApi failingApi(RuntimeException failure) {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletion(any())).thenThrow(failure);
		when(api.embeddings(any())).thenThrow(failure);
		when(api.images(any())).thenThrow(failure);
		return api;
	}

	/**
	 * The three model types as named cases for a parameterized test.
	 * @return one argument per model type
	 */
	static Stream<Arguments> everyModel() {
		return Stream.of(
				Arguments.of(Named.of("chat model",
						(ModelCall) (context) -> context.getBean(OpenRouterChatModel.class).call(new Prompt("hi")))),
				Arguments.of(Named.of("embedding model",
						(ModelCall) (context) -> context.getBean(OpenRouterEmbeddingModel.class).embed("hi"))),
				Arguments
					.of(Named.of("image model", (ModelCall) (context) -> context.getBean(OpenRouterImageModel.class)
						.call(new ImagePrompt("hi")))));
	}

	/**
	 * One blocking call on an auto-configured model.
	 */
	@FunctionalInterface
	interface ModelCall extends Consumer<AssertableApplicationContext> {

	}

}
