package de.subhransu.openrouter.springai.image;

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.dto.ImagesResponse;
import de.subhransu.openrouter.springai.api.dto.ImagesStreamEvent;
import de.subhransu.openrouter.springai.api.errors.OpenRouterInBandErrorException;
import io.micrometer.observation.tck.TestObservationRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;

/**
 * Micrometer observability contract for the image model: every {@code call()} and
 * {@code stream()} is recorded as a {@code gen_ai.client.operation} observation against
 * the configured {@link io.micrometer.observation.ObservationRegistry}, carrying the
 * OpenRouter provider tag, and stream errors are recorded on the observation instead of
 * being dropped.
 */
@Tag("observability")
@Tag("observability.observations")
class OpenRouterImageModelObservationTests {

	private static final String MODEL = "bytedance-seed/seedream-4.5";

	private static final String OBSERVATION_NAME = "gen_ai.client.operation";

	private static final String PROVIDER_KEY = "gen_ai.system";

	private final TestObservationRegistry observationRegistry = TestObservationRegistry.create();

	private OpenRouterImageModel model(OpenRouterApi api) {
		return OpenRouterImageModel.builder()
			.openRouterApi(api)
			.options(OpenRouterImageOptions.builder().model(MODEL).build())
			.observationRegistry(this.observationRegistry)
			.build();
	}

	@Test
	void callRecordsAnImageModelObservationWithProviderAndModel() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.images(any())).thenReturn(new ImagesResponse(1750000000L,
				List.of(new ImagesResponse.ImageData("aW1hZ2Ux", "image/png", null)), null));

		model(api).call(new ImagePrompt("a red panda"));

		assertThat(this.observationRegistry).hasObservationWithNameEqualTo(OBSERVATION_NAME)
			.that()
			.hasLowCardinalityKeyValue(PROVIDER_KEY, "openrouter")
			.hasLowCardinalityKeyValue("gen_ai.request.model", MODEL)
			.hasBeenStarted()
			.hasBeenStopped();
	}

	@Test
	void streamRecordsAnObservationStoppedAfterTheFluxCompletes() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.imagesStream(any())).thenReturn(Flux.just(new ImagesStreamEvent(ImagesStreamEvent.COMPLETED, null,
				"ZmluYWw=", "image/png", 1750000000L, null, null)));

		model(api).stream(new ImagePrompt("a red panda")).collectList().block(Duration.ofSeconds(5));

		assertThat(this.observationRegistry).hasObservationWithNameEqualTo(OBSERVATION_NAME)
			.that()
			.hasLowCardinalityKeyValue(PROVIDER_KEY, "openrouter")
			.hasLowCardinalityKeyValue("gen_ai.request.model", MODEL)
			.hasBeenStarted()
			.hasBeenStopped();
	}

	@Test
	void streamErrorIsRecordedOnTheObservation() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.imagesStream(any())).thenReturn(Flux.error(new OpenRouterInBandErrorException("stream failed",
				HttpStatus.INTERNAL_SERVER_ERROR, "{\"error\":true}")));

		OpenRouterImageModel model = model(api);
		assertThatThrownBy(
				() -> model.stream(new ImagePrompt("a red panda")).collectList().block(Duration.ofSeconds(5)))
			.isInstanceOf(OpenRouterInBandErrorException.class);

		assertThat(this.observationRegistry).hasObservationWithNameEqualTo(OBSERVATION_NAME)
			.that()
			.hasBeenStarted()
			.hasBeenStopped()
			.thenError()
			.isInstanceOf(OpenRouterInBandErrorException.class);
	}

}
