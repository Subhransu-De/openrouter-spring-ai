package de.subhransu.openrouter.springai.starter.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.subhransu.openrouter.springai.autoconfigure.OpenRouterApiAutoConfiguration;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterDecisionAutoConfiguration;
import de.subhransu.openrouter.springai.chat.OpenRouterUsage;
import de.subhransu.openrouter.springai.decision.OpenRouterDecisionOptions;
import de.subhransu.openrouter.springai.errors.OpenRouterHttpFailure;
import de.subhransu.openrouter.springai.errors.OpenRouterNonTransientApiException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.decision.DecisionModel;
import org.springframework.ai.decision.DecisionPrompt;
import org.springframework.ai.decision.DecisionResponse;
import org.springframework.ai.decision.question.ChoiceQuestion;
import org.springframework.ai.decision.question.DecisionQuestion;
import org.springframework.ai.decision.question.ScoreQuestion;
import org.springframework.ai.decision.question.YesNoQuestion;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The auto-configured decision model against the OpenRouter mock: typed answers come back
 * through the real HTTP client from the alpha Decisions endpoint, a refusal stays an
 * answer, and a data policy error keeps its status.
 */
@Tag("http")
@Tag("api.wire")
class OpenRouterDecisionIntegrationTests {

	private static final String DECISIONS = "/api/alpha/decisions";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(
				AutoConfigurations.of(OpenRouterApiAutoConfiguration.class, OpenRouterDecisionAutoConfiguration.class))
		.withBean(RetryTemplate.class,
				() -> new RetryTemplate(RetryPolicy.builder()
					.maxRetries(2)
					.delay(Duration.ofMillis(10))
					.includes(TransientAiException.class)
					.build()))
		.withPropertyValues("spring.ai.openrouter.api-key=garage-mock-key",
				"spring.ai.openrouter.base-url=" + OpenRouterMock.baseUrl(), "spring.ai.model.decision=openrouter",
				"spring.ai.openrouter.decision.model=typesafe/jev-1.14",
				"spring.ai.openrouter.decision.session-id=garage-triage");

	@BeforeEach
	void resetMock() {
		OpenRouterMock.reset();
	}

	@Test
	void aTicketIsTriagedWithEveryQuestionType() {
		Map<String, DecisionQuestion> questions = new LinkedHashMap<>();
		questions.put("is_refund", YesNoQuestion.of("Is the customer asking for a refund?"));
		questions.put("team", ChoiceQuestion.of("Which team should handle this?", "billing", "technical", "sales"));
		questions.put("urgency", ScoreQuestion.of("How urgent is this?", "Low", "Medium", "High"));

		run(decision -> {
			DecisionResponse response = decision.call("My card was charged twice; I want a refund today.", questions);

			assertThat(response.getYesNo("is_refund").isYes()).isTrue();
			assertThat(response.getChoice("team").getValue()).isEqualTo("billing");
			assertThat(response.getScore("urgency").getNearestLevel()).isEqualTo(2);
			assertThat(response.getMetadata().getModel()).isEqualTo("typesafe/jev-1.14-20261014");
			assertThat(OpenRouterUsage.from(response).getCost()).isEqualTo(0.00001785);
		});
		JsonNode request = single();
		assertThat(request.path("model").asString()).isEqualTo("typesafe/jev-1.14");
		assertThat(request.path("session_id").asString()).isEqualTo("garage-triage");
		assertThat(request.path("state").asString()).isEqualTo("My card was charged twice; I want a refund today.");
		assertThat(request.path("questions").propertyNames()).containsExactly("is_refund", "team", "urgency");
		assertThat(request.path("questions").path("urgency").path("criteria").toString())
			.isEqualTo("[\"Low\",\"Medium\",\"High\"]");
	}

	@Test
	void aRefusedQuestionDoesNotFailTheOthers() {
		Map<String, DecisionQuestion> questions = new LinkedHashMap<>();
		questions.put("is_safe", YesNoQuestion.of("Is this message safe to auto-reply to?"));
		questions.put("flagged", YesNoQuestion.of("Does it describe a weapon?"));

		run(decision -> {
			DecisionResponse response = decision.call(DecisionPrompt.builder()
				.text("Customer message.")
				.questions(questions)
				.options(OpenRouterDecisionOptions.builder().model("liquid/d1.2").build())
				.build());

			assertThat(response.getYesNo("is_safe").getProbability()).isEqualTo(0.6941153656751077);
			assertThat(response.isRefused("flagged")).isTrue();
		});
		assertThat(single().path("model").asString()).isEqualTo("liquid/d1.2");
	}

	@Test
	void aDataPolicyErrorIsClassifiedAndNotRetried() {
		run(decision -> assertThatThrownBy(() -> decision.call(DecisionPrompt.builder()
			.text("Customer message.")
			.question("is_safe", YesNoQuestion.of("Is this safe?"))
			.options(OpenRouterDecisionOptions.builder().model("synthetic/retained-decisions").build())
			.build())).isInstanceOf(OpenRouterNonTransientApiException.class).satisfies(failure -> {
				OpenRouterHttpFailure http = (OpenRouterHttpFailure) failure;
				assertThat(http.getStatusCode().value()).isEqualTo(404);
				assertThat(http.getErrorDetails().message()).contains("data policy");
			}));
		assertThat(OpenRouterMock.requestsAt(DECISIONS)).hasSize(1);
	}

	private void run(Consumer<DecisionModel> test) {
		this.contextRunner.run(context -> {
			if (context.getStartupFailure() != null) {
				throw context.getStartupFailure();
			}
			test.accept(context.getBean(DecisionModel.class));
		});
		assertThat(OpenRouterMock.unmatched()).isEmpty();
	}

	private static JsonNode single() {
		List<JsonNode> requests = OpenRouterMock.requestsAt(DECISIONS);
		assertThat(requests).hasSize(1);
		return requests.get(0);
	}

}
