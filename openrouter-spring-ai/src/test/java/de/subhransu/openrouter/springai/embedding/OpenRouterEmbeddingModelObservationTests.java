package de.subhransu.openrouter.springai.embedding;

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.dto.EmbeddingsResponse;
import de.subhransu.openrouter.springai.api.dto.Usage;
import de.subhransu.openrouter.springai.api.errors.OpenRouterApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.tck.TestObservationRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.observation.DefaultEmbeddingModelObservationConvention;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationContext;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpStatus;

/**
 * Micrometer contract for {@link OpenRouterEmbeddingModel#call}: one observation per
 * logical call, started and stopped once, carrying the OpenRouter provider and the
 * request and response key values, with failures recorded as the original exception.
 */
class OpenRouterEmbeddingModelObservationTests {

	private static final String MODEL = "openai/text-embedding-3-small";

	private static final String OBSERVATION_NAME = "gen_ai.client.operation";

	private final TestObservationRegistry observationRegistry = TestObservationRegistry.create();

	private final OpenRouterApi api = mock(OpenRouterApi.class);

	private OpenRouterEmbeddingModel.Builder modelBuilder() {
		return OpenRouterEmbeddingModel.builder()
			.openRouterApi(this.api)
			.defaultOptions(OpenRouterEmbeddingOptions.builder().model(MODEL).build())
			.retryTemplate(new RetryTemplate(RetryPolicy.builder().maxRetries(0).build()))
			.observationRegistry(this.observationRegistry);
	}

	private static EmbeddingsResponse response(int inputs) {
		List<EmbeddingsResponse.EmbeddingData> data = new ArrayList<>();
		for (int index = 0; index < inputs; index++) {
			data.add(new EmbeddingsResponse.EmbeddingData("embedding", index, new float[] { 0.5f, 1.5f }));
		}
		return new EmbeddingsResponse("list", data, "openai/text-embedding-3-small-2026",
				new Usage(7, null, 7, null, null, null, null, null, null), null);
	}

	@Test
	void callObservationCarriesProviderRequestAndResponseKeyValues() {
		when(this.api.embeddings(any())).thenReturn(response(2));

		modelBuilder().build()
			.call(new EmbeddingRequest(List.of("first", "second"),
					OpenRouterEmbeddingOptions.builder().dimensions(2).build()));

		assertThat(this.observationRegistry).hasNumberOfObservationsEqualTo(1)
			.hasObservationWithNameEqualTo(OBSERVATION_NAME)
			.that()
			.hasContextualNameEqualTo("embedding " + MODEL)
			.hasLowCardinalityKeyValue("gen_ai.operation.name", "embedding")
			.hasLowCardinalityKeyValue("gen_ai.system", "openrouter")
			.hasLowCardinalityKeyValue("gen_ai.request.model", MODEL)
			.hasLowCardinalityKeyValue("gen_ai.response.model", "openai/text-embedding-3-small-2026")
			.hasHighCardinalityKeyValue("gen_ai.request.embedding.dimensions", "2")
			.hasHighCardinalityKeyValue("gen_ai.usage.input_tokens", "7")
			.hasHighCardinalityKeyValue("gen_ai.usage.total_tokens", "7")
			.doesNotHaveError()
			.hasBeenStarted()
			.hasBeenStopped();
	}

	@Test
	void transportFailureIsRecordedAsTheOriginalExceptionAndStopsTheObservation() {
		OpenRouterApiException failure = new OpenRouterApiException("embeddings failed", HttpStatus.BAD_REQUEST,
				"{\"error\":true}");
		when(this.api.embeddings(any())).thenThrow(failure);
		OpenRouterEmbeddingModel model = modelBuilder().build();
		EmbeddingRequest request = new EmbeddingRequest(List.of("first"), null);

		assertThatThrownBy(() -> model.call(request)).isSameAs(failure);

		assertThat(this.observationRegistry).hasNumberOfObservationsEqualTo(1)
			.hasObservationWithNameEqualTo(OBSERVATION_NAME)
			.that()
			.hasLowCardinalityKeyValue("gen_ai.request.model", MODEL)
			.hasLowCardinalityKeyValue("gen_ai.response.model", "none")
			.hasBeenStarted()
			.hasBeenStopped()
			.thenError()
			.isSameAs(failure);
	}

	@Test
	void malformedResponseIsRecordedAsAProtocolErrorOnTheObservation() {
		when(this.api.embeddings(any())).thenReturn(response(1));
		OpenRouterEmbeddingModel model = modelBuilder().build();
		EmbeddingRequest request = new EmbeddingRequest(List.of("first", "second"), null);

		assertThatThrownBy(() -> model.call(request)).isInstanceOf(OpenRouterProtocolException.class)
			.hasMessageContaining("count must match input count");

		assertThat(this.observationRegistry).hasNumberOfObservationsEqualTo(1)
			.hasObservationWithNameEqualTo(OBSERVATION_NAME)
			.that()
			.hasBeenStopped()
			.thenError()
			.isInstanceOf(OpenRouterProtocolException.class);
	}

	@Test
	void requestFailingValidationBeforeTheApiIsObservedAndNeverReachesTheTransport() {
		OpenRouterEmbeddingModel model = OpenRouterEmbeddingModel.builder()
			.openRouterApi(this.api)
			.observationRegistry(this.observationRegistry)
			.build();
		EmbeddingRequest request = new EmbeddingRequest(List.of("first"), null);

		assertThatIllegalArgumentException().isThrownBy(() -> model.call(request))
			.withMessage("Embedding model must not be empty");

		verify(this.api, times(0)).embeddings(any());
		assertThat(this.observationRegistry).hasNumberOfObservationsEqualTo(1)
			.hasObservationWithNameEqualTo(OBSERVATION_NAME)
			.that()
			.hasLowCardinalityKeyValue("gen_ai.request.model", "none")
			.hasBeenStopped()
			.thenError()
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void retriedCallIsOneObservationStartedAndStoppedOnce() {
		List<Observation.Context> starts = new ArrayList<>();
		List<Observation.Context> stops = new ArrayList<>();
		this.observationRegistry.observationConfig().observationHandler(new ObservationHandler<>() {
			@Override
			public boolean supportsContext(Observation.Context context) {
				return context instanceof EmbeddingModelObservationContext;
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
		when(this.api.embeddings(any()))
			.thenThrow(new OpenRouterApiException("transient", HttpStatus.SERVICE_UNAVAILABLE, "{}"))
			.thenReturn(response(1));
		OpenRouterEmbeddingModel model = modelBuilder()
			.retryTemplate(new RetryTemplate(RetryPolicy.builder().maxRetries(1).delay(Duration.ZERO).build()))
			.build();

		assertThat(model.call(new EmbeddingRequest(List.of("first"), null)).getResults()).hasSize(1);

		verify(this.api, times(2)).embeddings(any());
		assertThat(starts).hasSize(1);
		assertThat(stops).containsExactlyElementsOf(starts);
		assertThat(this.observationRegistry).hasNumberOfObservationsEqualTo(1)
			.hasObservationWithNameEqualTo(OBSERVATION_NAME)
			.that()
			.doesNotHaveError();
	}

	@Test
	void customConventionReplacesTheDefaultNameAndKeyValues() {
		when(this.api.embeddings(any())).thenReturn(response(1));
		OpenRouterEmbeddingModel model = modelBuilder().build();
		model.setObservationConvention(new DefaultEmbeddingModelObservationConvention() {
			@Override
			public String getName() {
				return "synthetic.embedding";
			}
		});

		model.call(new EmbeddingRequest(List.of("first"), null));

		assertThat(this.observationRegistry).hasNumberOfObservationsEqualTo(1)
			.hasObservationWithNameEqualTo("synthetic.embedding")
			.that()
			.hasLowCardinalityKeyValue("gen_ai.system", "openrouter")
			.hasBeenStopped();
		assertThatIllegalArgumentException().isThrownBy(() -> model.setObservationConvention(null))
			.withMessage("observationConvention must not be null");
	}

}
