package de.subhransu.openrouter.springai.image;

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.dto.ImagesRequest;
import de.subhransu.openrouter.springai.api.dto.ImagesResponse;
import de.subhransu.openrouter.springai.api.errors.OpenRouterApiException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.tck.TestObservationRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.image.observation.ImageModelObservationContext;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpStatus;

/**
 * Micrometer contract for the synchronous image call. Stream lifecycle (stop once, error
 * identity, cancellation, parent propagation) is pinned for the image path in
 * {@code OpenRouterStreamingObservationLifecycleTests}; this class covers what that
 * lifecycle class does not: the request key values and the sync error path.
 */
class OpenRouterImageModelObservationTests {

	private static final String MODEL = "bytedance-seed/seedream-4.5";

	private static final String OBSERVATION_NAME = "gen_ai.client.operation";

	private final TestObservationRegistry observationRegistry = TestObservationRegistry.create();

	private OpenRouterImageModel model(OpenRouterApi api) {
		return OpenRouterImageModel.builder()
			.openRouterApi(api)
			.defaultOptions(OpenRouterImageOptions.builder().model(MODEL).n(1).build())
			.observationRegistry(this.observationRegistry)
			.build();
	}

	@Test
	void callObservationCarriesTheMergedRequestKeyValuesAndMatchesTheWireRequest() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.images(any())).thenReturn(new ImagesResponse(1750000000L,
				List.of(new ImagesResponse.ImageData("aW1hZ2Ux", "image/png", null)), null));
		ImagePrompt prompt = new ImagePrompt("a red panda",
				OpenRouterImageOptions.builder().n(3).width(1024).height(512).build());

		AtomicReference<ImageResponse> observedResponse = observeResponses();

		ImageResponse response = model(api).call(prompt);

		// The default convention records no response key values; the observation context
		// still carries the response, which custom conventions and handlers read.
		assertThat(observedResponse.get()).isSameAs(response);
		// Default model, runtime n and size: the observation sees the merged options.
		assertThat(this.observationRegistry).hasObservationWithNameEqualTo(OBSERVATION_NAME)
			.that()
			.hasLowCardinalityKeyValue("gen_ai.operation.name", "image")
			.hasLowCardinalityKeyValue("gen_ai.system", "openrouter")
			.hasLowCardinalityKeyValue("gen_ai.request.model", MODEL)
			.hasHighCardinalityKeyValue("gen_ai.request.image.size", "1024x512")
			.hasBeenStarted()
			.hasBeenStopped();
		ArgumentCaptor<ImagesRequest> request = ArgumentCaptor.forClass(ImagesRequest.class);
		verify(api).images(request.capture());
		assertThat(request.getValue().n()).isEqualTo(3);
		assertThat(request.getValue().size()).isEqualTo("1024x512");
		assertThat(response.getResults()).hasSize(1);
	}

	private AtomicReference<ImageResponse> observeResponses() {
		AtomicReference<ImageResponse> observed = new AtomicReference<>();
		this.observationRegistry.observationConfig().observationHandler(new ObservationHandler<>() {
			@Override
			public boolean supportsContext(Observation.Context context) {
				return context instanceof ImageModelObservationContext;
			}

			@Override
			public void onStop(Observation.Context context) {
				observed.set(((ImageModelObservationContext) context).getResponse());
			}
		});
		return observed;
	}

	@Test
	void callErrorIsRecordedOnTheObservationAndThrownUnchanged() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		OpenRouterApiException failure = new OpenRouterApiException("image failed", HttpStatus.BAD_REQUEST,
				"{\"error\":true}");
		when(api.images(any())).thenThrow(failure);
		OpenRouterImageModel model = model(api);
		ImagePrompt prompt = new ImagePrompt("a red panda");

		assertThatThrownBy(() -> model.call(prompt)).isSameAs(failure);

		assertThat(this.observationRegistry).hasObservationWithNameEqualTo(OBSERVATION_NAME)
			.that()
			.hasLowCardinalityKeyValue("gen_ai.request.model", MODEL)
			.hasBeenStarted()
			.hasBeenStopped()
			.thenError()
			.isSameAs(failure);
	}

	@Test
	void retriedCallIsOneObservationStartedAndStoppedOnce() {
		List<Observation.Context> starts = new ArrayList<>();
		List<Observation.Context> stops = new ArrayList<>();
		this.observationRegistry.observationConfig().observationHandler(new ObservationHandler<>() {
			@Override
			public boolean supportsContext(Observation.Context context) {
				return context instanceof ImageModelObservationContext;
			}

			@Override
			public void onStart(Observation.Context context) {
				starts.add(context);
			}

			@Override
			public void onStop(Observation.Context context) {
				stops.add(context);
			}
		});
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.images(any())).thenThrow(new OpenRouterApiException("transient", HttpStatus.SERVICE_UNAVAILABLE, "{}"))
			.thenReturn(new ImagesResponse(1750000000L,
					List.of(new ImagesResponse.ImageData("aW1hZ2Ux", "image/png", null)), null));
		OpenRouterImageModel model = OpenRouterImageModel.builder()
			.openRouterApi(api)
			.defaultOptions(OpenRouterImageOptions.builder().model(MODEL).build())
			.observationRegistry(this.observationRegistry)
			.retryTemplate(new RetryTemplate(RetryPolicy.builder().maxRetries(1).delay(Duration.ZERO).build()))
			.build();

		assertThat(model.call(new ImagePrompt("a red panda")).getResults()).hasSize(1);

		verify(api, times(2)).images(any());
		assertThat(starts).hasSize(1);
		assertThat(stops).containsExactlyElementsOf(starts);
		assertThat(this.observationRegistry).hasNumberOfObservationsEqualTo(1)
			.hasObservationWithNameEqualTo(OBSERVATION_NAME)
			.that()
			.doesNotHaveError();
	}

}
