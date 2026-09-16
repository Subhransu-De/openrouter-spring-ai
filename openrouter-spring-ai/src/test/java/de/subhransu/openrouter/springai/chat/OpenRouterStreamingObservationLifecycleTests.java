package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.image.OpenRouterImageModel;
import de.subhransu.openrouter.springai.image.OpenRouterImageOptions;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeType;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.image.observation.ImageModelObservationContext;
import org.springframework.ai.image.ImagePrompt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

class OpenRouterStreamingObservationLifecycleTests {

	private final OpenRouterApi api = mock(OpenRouterApi.class);

	private final ObservationRegistry registry = ObservationRegistry.create();

	private final List<Observation.Context> starts = new ArrayList<>();

	private final List<Observation.Context> stops = new ArrayList<>();

	private final List<Throwable> errors = new ArrayList<>();

	OpenRouterStreamingObservationLifecycleTests() {
		this.registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
			@Override
			public boolean supportsContext(Observation.Context context) {
				return true;
			}

			@Override
			public void onStart(Observation.Context context) {
				if (context instanceof ChatModelObservationContext || context instanceof ImageModelObservationContext) {
					starts.add(context);
				}
			}

			@Override
			public void onStop(Observation.Context context) {
				if (starts.contains(context)) {
					stops.add(context);
				}
			}

			@Override
			public void onError(Observation.Context context) {
				if (starts.contains(context)) {
					errors.add(context.getError());
				}
			}
		});
	}

	@ParameterizedTest
	@EnumSource(Path.class)
	void mappingFailuresAreObservedPerSubscription(Path path) {
		Flux<?> stream = stream(path, true);
		assertThat(this.starts).isEmpty();
		for (int subscription = 1; subscription <= 2; subscription++) {
			StepVerifier.create(stream).expectError(IllegalArgumentException.class).verify();
			assertThat(this.starts).hasSize(subscription).doesNotHaveDuplicates();
			assertThat(this.stops).containsExactlyElementsOf(this.starts);
			assertThat(this.errors).hasSize(subscription);
			assertThat(this.starts.get(subscription - 1).getError()).isSameAs(this.errors.get(subscription - 1));
		}
		verifyNoInteractions(this.api);
	}

	@ParameterizedTest
	@EnumSource(value = Path.class, names = { "CHAT_COMPLETIONS", "RESPONSES" })
	void unsupportedMediaIsObservedBeforeTransport(Path path) {
		UserMessage message = UserMessage.builder()
			.text("synthetic attachment")
			.media(Media.builder().mimeType(MimeType.valueOf("application/pdf")).data(new byte[] { 1 }).build())
			.build();
		Prompt prompt = new Prompt(List.of(message), chatPrompt(path, false).getOptions());
		StepVerifier.create(chatModel().stream(prompt)).expectError(IllegalArgumentException.class).verify();
		assertThat(this.starts).hasSize(1);
		assertThat(this.stops).containsExactlyElementsOf(this.starts);
		assertThat(this.errors).containsExactly(this.starts.get(0).getError());
		verifyNoInteractions(this.api);
	}

	@ParameterizedTest
	@EnumSource(Path.class)
	void synchronousMappingFailuresHaveTheSameLifecycle(Path path) {
		assertThatThrownBy(() -> {
			if (path == Path.IMAGE) {
				imageModel().call(imagePrompt(true));
			}
			else {
				chatModel().call(chatPrompt(path, true));
			}
		}).isInstanceOf(IllegalArgumentException.class);
		assertThat(this.starts).hasSize(1);
		assertThat(this.stops).containsExactlyElementsOf(this.starts);
		assertThat(this.errors).containsExactly(this.starts.get(0).getError());
		verifyNoInteractions(this.api);
	}

	@ParameterizedTest
	@EnumSource(Path.class)
	void completionPreservesReactiveParentAndStopsOnce(Path path) {
		when(this.api.chatCompletionStream(any())).thenReturn(Flux.empty());
		when(this.api.responsesStream(any())).thenReturn(Flux.empty());
		when(this.api.imagesStream(any())).thenReturn(Flux.empty());
		Observation parent = Observation.start("parent", this.registry);
		try {
			StepVerifier
				.create(stream(path, false).contextWrite(Context.of(ObservationThreadLocalAccessor.KEY, parent)))
				.verifyComplete();
			assertThat(this.starts).hasSize(1);
			assertThat(this.starts.get(0).getParentObservation()).isSameAs(parent);
			assertThat(this.stops).containsExactlyElementsOf(this.starts);
			assertThat(this.errors).isEmpty();
		}
		finally {
			parent.stop();
		}
	}

	@ParameterizedTest
	@EnumSource(Path.class)
	void cancellationStopsOnceWithoutError(Path path) {
		when(this.api.chatCompletionStream(any())).thenReturn(Flux.never());
		when(this.api.responsesStream(any())).thenReturn(Flux.never());
		when(this.api.imagesStream(any())).thenReturn(Flux.never());
		StepVerifier.create(stream(path, false)).thenCancel().verify();
		assertThat(this.starts).hasSize(1);
		assertThat(this.stops).containsExactlyElementsOf(this.starts);
		assertThat(this.errors).isEmpty();
	}

	@ParameterizedTest
	@EnumSource(Path.class)
	void transportErrorsStopOnceAndRecordTheOriginalError(Path path) {
		RuntimeException failure = new IllegalStateException("synthetic transport failure");
		when(this.api.chatCompletionStream(any())).thenReturn(Flux.error(failure));
		when(this.api.responsesStream(any())).thenReturn(Flux.error(failure));
		when(this.api.imagesStream(any())).thenReturn(Flux.error(failure));
		StepVerifier.create(stream(path, false)).expectErrorMatches(error -> error == failure).verify();
		assertThat(this.starts).hasSize(1);
		assertThat(this.stops).containsExactlyElementsOf(this.starts);
		assertThat(this.errors).containsExactly(failure);
	}

	private Flux<?> stream(Path path, boolean invalid) {
		return path == Path.IMAGE ? imageModel().stream(imagePrompt(invalid))
				: chatModel().stream(chatPrompt(path, invalid));
	}

	private OpenRouterChatModel chatModel() {
		return OpenRouterChatModel.builder().openRouterApi(this.api).observationRegistry(this.registry).build();
	}

	private OpenRouterImageModel imageModel() {
		return OpenRouterImageModel.builder().openRouterApi(this.api).observationRegistry(this.registry).build();
	}

	private Prompt chatPrompt(Path path, boolean invalid) {
		OpenRouterChatOptions.Builder options = OpenRouterChatOptions.builder()
			.model("synthetic/model")
			.requestMode(path == Path.RESPONSES ? OpenRouterRequestMode.OPENAI_RESPONSES
					: OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS);
		if (invalid) {
			if (path == Path.RESPONSES) {
				options.seed(1);
			}
			else {
				options.responseFormat(OpenRouterResponseFormat.jsonSchema("{invalid"));
			}
		}
		return new Prompt("synthetic prompt", options.build());
	}

	private ImagePrompt imagePrompt(boolean invalid) {
		return new ImagePrompt("synthetic image",
				OpenRouterImageOptions.builder().model("synthetic/model").width(invalid ? 1024 : null).build());
	}

	enum Path {

		CHAT_COMPLETIONS, RESPONSES, IMAGE

	}

}
