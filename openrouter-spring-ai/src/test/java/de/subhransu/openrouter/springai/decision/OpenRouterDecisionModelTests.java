package de.subhransu.openrouter.springai.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.chat.OpenRouterMetadata;
import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import de.subhransu.openrouter.springai.chat.OpenRouterTrace;
import de.subhransu.openrouter.springai.chat.OpenRouterUsage;
import de.subhransu.openrouter.springai.errors.OpenRouterHttpFailure;
import de.subhransu.openrouter.springai.errors.OpenRouterNonTransientApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.part.MessagePart;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.part.MediaPart;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.chat.messages.part.ToolResultPart;
import org.springframework.ai.content.Media;
import org.springframework.ai.decision.DecisionOptions;
import org.springframework.ai.decision.DecisionPrompt;
import org.springframework.ai.decision.DecisionResponse;
import org.springframework.ai.decision.input.DecisionInput;
import org.springframework.ai.decision.question.ChoiceQuestion;
import org.springframework.ai.decision.question.DecisionQuestion;
import org.springframework.ai.decision.question.ScoreQuestion;
import org.springframework.ai.decision.question.YesNoQuestion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.DefaultUriBuilderFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("decision")
@Tag("decision.model")
class OpenRouterDecisionModelTests {

	private static final String URL = "https://openrouter.test/api/alpha/decisions";

	private static final String MODEL = "typesafe/jev-1.14";

	private static final String TICKET = "Customer ticket: my card was charged twice for the Team plan and I want a refund today.";

	private static final ObjectMapper JSON = new ObjectMapper();

	private final List<JsonNode> requests = new ArrayList<>();

	@Test
	void asksEveryQuestionTypeAndMapsTheAnswers() {
		Fixture fixture = fixture(OpenRouterDecisionOptions.builder().model(MODEL).build());
		fixture.expectSuccess(fixture("decisions-success.json"));

		DecisionResponse response = fixture.model().call(TICKET, ticketQuestions());

		assertThat(response.getYesNo("is_refund").getProbability()).isEqualTo(0.99);
		assertThat(response.getChoice("team").getValue()).isEqualTo("billing");
		assertThat(response.getChoice("team").getProbabilities()).containsExactly(Map.entry("billing", 1.0),
				Map.entry("technical", 0.0), Map.entry("sales", 0.0));
		assertThat(response.getChoice("team").getConfidence()).isEqualTo(1.0);
		assertThat(response.getScore("urgency").getValue()).isEqualTo(1.94);
		assertThat(response.getScore("urgency").getProbabilities()).containsExactly(0.0, 0.06, 0.94);
		assertThat(response.getScore("urgency").getConfidence()).isEqualTo(0.91);
		assertThat(response.getScore("urgency").getNearestLevel()).isEqualTo(2);
		assertThat(response.getMetadata().getId()).isEqualTo("gen-dec-1795800000-SyntheticFixture01");
		assertThat(response.getMetadata().getModel()).isEqualTo("typesafe/jev-1.14-20261014");
		assertThat((String) response.getMetadata().get(OpenRouterMetadata.PROVIDER)).isEqualTo("TypeSafe");
		OpenRouterUsage usage = OpenRouterUsage.from(response);
		assertThat(usage.getPromptTokens()).isEqualTo(425);
		assertThat(usage.getCompletionTokens()).isEqualTo(71);
		assertThat(usage.getTotalTokens()).isEqualTo(496);
		assertThat(usage.getCost()).isEqualTo(0.00001785);
		assertThat(usage.getCostDetails().upstreamInferenceCost()).isEqualTo(0.00001785);
		assertThat(usage.getByok()).isFalse();

		assertThat(this.requests.get(0)).isEqualTo(JSON.readTree("""
				{"model":"typesafe/jev-1.14",
				 "state":"%s",
				 "questions":{
				   "is_refund":{"type":"noul","instructions":"Is the customer asking for a refund?",
				                "criteria":{"true":"Asks for money back","false":"Anything else"}},
				   "team":{"type":"choice","instructions":"Which team should handle this?",
				           "criteria":{"billing":"Payments and refunds","technical":null,"sales":"Pricing"}},
				   "urgency":{"type":"score","instructions":"How urgent is this?",
				              "criteria":["Low","Medium",{"label":"High","description":"Money or access blocked"}]}}}
				""".formatted(TICKET)));
		fixture.server().verify();
	}

	@Test
	void aRefusedQuestionIsAnsweredWithARefusal() {
		Fixture fixture = fixture(OpenRouterDecisionOptions.builder().model(MODEL).build());
		fixture.expectSuccess("""
				{"id":"gen-dec-2","model":"typesafe/jev-1.14","answers":{
				  "safe":{"type":"noul","noul":0.92,"confidence":0.92},
				  "flagged":{"type":"refusal"}},
				 "usage":{"input_tokens":30,"output_tokens":0,"cost":0.0000012}}
				""");
		Map<String, YesNoQuestion> questions = new LinkedHashMap<>();
		questions.put("safe", YesNoQuestion.of("Is this safe?"));
		questions.put("flagged", YesNoQuestion.of("Is this flagged?"));

		DecisionResponse response = fixture.model().call("Some text.", questions);

		assertThat(response.getYesNo("safe").isYes()).isTrue();
		assertThat(response.isRefused("flagged")).isTrue();
		assertThat(this.requests.get(0).path("questions").path("safe").has("criteria")).isFalse();
	}

	@Test
	void promptOptionsOverrideTheDefaults() {
		OpenRouterTrace defaultTrace = OpenRouterTrace.builder().traceName("triage").build();
		Fixture fixture = fixture(OpenRouterDecisionOptions.builder()
			.model(MODEL)
			.user("default-user")
			.sessionId("default-session")
			.trace(defaultTrace)
			.provider(OpenRouterProviderPreferences.builder().order(List.of("typesafe")).allowFallbacks(false).build())
			.build());
		fixture.expectSuccess(noulAnswer());
		fixture.expectSuccess(noulAnswer());

		fixture.model()
			.call(DecisionPrompt.builder()
				.text("Some text.")
				.question("urgent", YesNoQuestion.of("Is this urgent?"))
				.options(DecisionOptions.builder().model("liquid/d1.2").build())
				.build());
		fixture.model()
			.call(DecisionPrompt.builder()
				.text("Some text.")
				.question("urgent", YesNoQuestion.of("Is this urgent?"))
				.options(OpenRouterDecisionOptions.builder().sessionId("ticket-7").build())
				.build());

		JsonNode portable = this.requests.get(0);
		assertThat(portable.path("model").asString()).isEqualTo("liquid/d1.2");
		assertThat(portable.path("user").asString()).isEqualTo("default-user");
		assertThat(portable.path("session_id").asString()).isEqualTo("default-session");
		assertThat(portable.path("trace")).isEqualTo(JSON.readTree("{\"trace_name\":\"triage\"}"));
		assertThat(portable.path("provider"))
			.isEqualTo(JSON.readTree("{\"allow_fallbacks\":false,\"order\":[\"typesafe\"]}"));
		JsonNode openRouter = this.requests.get(1);
		assertThat(openRouter.path("model").asString()).isEqualTo(MODEL);
		assertThat(openRouter.path("session_id").asString()).isEqualTo("ticket-7");
		assertThat(fixture.model().getOptions().getSessionId()).isEqualTo("default-session");
	}

	@Test
	void imagesInAContentInputAreSentAsStateParts() {
		Fixture fixture = fixture(OpenRouterDecisionOptions.builder().model(MODEL).build());
		fixture.expectSuccess(noulAnswer());

		fixture.model()
			.call(DecisionInput.content(TextPart.of("A dashboard screenshot:"),
					MediaPart.of(media(MimeTypeUtils.IMAGE_PNG, new byte[] { 1, 2, 3 }))),
					Map.of("urgent", YesNoQuestion.of("Does it show a warning light?")));

		assertThat(this.requests.get(0).path("state")).isEqualTo(JSON.readTree("""
				["A dashboard screenshot:",{"type":"image_url","image_url":{"url":"data:image/png;base64,AQID"}}]
				"""));
	}

	@Test
	void fieldsAreSentAsAnOrderedObject() {
		Fixture fixture = fixture(OpenRouterDecisionOptions.builder().model(MODEL).build());
		fixture.expectSuccess(noulAnswer());
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("ticket", TextPart.of("My checkout page is blank."));
		fields.put("customer_tier", "enterprise");
		fields.put("seats", 40);
		fields.put("tags", List.of("checkout", "web"));

		fixture.model().call(DecisionInput.fields(fields), Map.of("urgent", YesNoQuestion.of("Is this urgent?")));

		JsonNode state = this.requests.get(0).path("state");
		assertThat(state).isEqualTo(JSON.readTree(
				"""
						{"ticket":"My checkout page is blank.","customer_tier":"enterprise","seats":40,"tags":["checkout","web"]}
						"""));
		assertThat(state.propertyNames()).containsExactly("ticket", "customer_tier", "seats", "tags");
	}

	@Test
	void aConversationIsSentAsMessagesWithToolCallsAndResults() {
		Fixture fixture = fixture(OpenRouterDecisionOptions.builder().model(MODEL).build());
		fixture.expectSuccess(noulAnswer());
		AssistantMessage call = AssistantMessage.builder()
			.parts(List.<MessagePart>of(
					ToolCallPart.of(new ToolCall("call-1", "function", "lookup", "{\"plate\":\"AB-12\"}"))))
			.build();
		ToolResponseMessage result = ToolResponseMessage.builder()
			.parts(List.<MessagePart>of(new ToolResultPart("call-1", "lookup", List.of(TextPart.of("Brake job booked")),
					false, null, Map.of())))
			.build();

		fixture.model()
			.call(DecisionInput.conversation(List.of(new UserMessage("Book my brakes."), call, result,
					new AssistantMessage("Done, see you Monday."))),
					Map.of("urgent", YesNoQuestion.of("Is the request urgent?")));

		assertThat(this.requests.get(0).path("state")).isEqualTo(JSON.readTree(
				"""
						{"messages":[
						  {"role":"user","content":"Book my brakes."},
						  {"role":"assistant","tool_calls":[{"id":"call-1","name":"lookup","arguments":"{\\"plate\\":\\"AB-12\\"}"}]},
						  {"role":"tool","tool_results":[{"id":"call-1","name":"lookup","result":"Brake job booked"}]},
						  {"role":"assistant","content":"Done, see you Monday."}]}
						"""));
	}

	@ParameterizedTest
	@ValueSource(strings = { "fields", "conversation", "audio" })
	void mediaOutsideAContentInputIsRejectedBeforeTheRequest(String input) {
		Fixture fixture = fixture(OpenRouterDecisionOptions.builder().model(MODEL).build());
		MediaPart image = MediaPart.of(media(MimeTypeUtils.IMAGE_PNG, new byte[] { 1 }));
		DecisionInput decisionInput = switch (input) {
			case "fields" -> DecisionInput.fields(Map.of("photo", image));
			case "conversation" -> DecisionInput.conversation(List.of(UserMessage.builder()
				.text("Look at this")
				.media(media(MimeTypeUtils.IMAGE_PNG, new byte[] { 1 }))
				.build()));
			default ->
				DecisionInput.content(MediaPart.of(media(MimeTypeUtils.parseMimeType("audio/wav"), new byte[] { 1 })));
		};

		assertThatThrownBy(() -> fixture.model().call(decisionInput, Map.of("q", YesNoQuestion.of("Is it?"))))
			.isInstanceOf(IllegalArgumentException.class);
		fixture.server().verify();
	}

	@Test
	void aModelIsRequiredBeforeTheRequest() {
		Fixture fixture = fixture(OpenRouterDecisionOptions.builder().build());

		assertThatThrownBy(() -> fixture.model().call("text", Map.of("q", YesNoQuestion.of("Is it?"))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Decision model");
		fixture.server().verify();
	}

	@ParameterizedTest
	@ValueSource(
			strings = { "{}", "{\"q\":{\"type\":\"box\",\"box\":{\"top\":0.1,\"bottom\":0.2,\"left\":0,\"right\":1}}}",
					"{\"q\":{\"type\":\"noul\"}}", "{\"q\":{\"type\":\"noul\",\"noul\":1.2}}", "{\"q\":null}",
					"{\"q\":{\"type\":\"noul\",\"noul\":0.5},\"extra\":{\"type\":\"noul\",\"noul\":0.5}}" })
	void yesNoAnswersThatDoNotFitThePromptAreProtocolErrors(String answers) {
		assertProtocolError(answers, YesNoQuestion.of("Is it?"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"q\":{\"type\":\"choice\",\"choice\":\"other\"}}",
			"{\"q\":{\"type\":\"choice\",\"choice\":\"a\",\"probabilities\":{\"a\":0.5,\"z\":0.5}}}",
			"{\"q\":{\"type\":\"choice\",\"choice\":\"a\",\"probabilities\":{\"a\":1,\"b\":null}}}",
			"{\"q\":{\"type\":\"score\",\"score\":1}}" })
	void choiceAnswersThatDoNotFitThePromptAreProtocolErrors(String answers) {
		assertProtocolError(answers, ChoiceQuestion.of("Which?", "a", "b"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"q\":{\"type\":\"score\",\"score\":2.5}}",
			"{\"q\":{\"type\":\"score\",\"score\":1,\"probabilities\":{\"3\":1}}}",
			"{\"q\":{\"type\":\"score\",\"score\":1,\"probabilities\":{\"high\":1}}}",
			"{\"q\":{\"type\":\"score\",\"score\":1,\"probabilities\":{\"0\":0.1,\"1\":null,\"2\":0.9}}}" })
	void scoreAnswersThatDoNotFitThePromptAreProtocolErrors(String answers) {
		assertProtocolError(answers, ScoreQuestion.of("How?", "low", "mid", "high"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"q\":{\"type\":\"choice\",\"choice\":\"%s\"}}",
			"{\"q\":{\"type\":\"choice\",\"choice\":\"a\",\"probabilities\":{\"%s\":1}}}",
			"{\"q\":{\"type\":\"%s\"}}" })
	void aMalformedAnswerNeverCarriesTheApiKeyIntoTheFailure(String answers) {
		String apiKey = "decision-Key+/=_0123456789";
		RestClient.Builder restClient = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(restClient).build();
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey(apiKey)
			.baseUrl("https://openrouter.test/api/v1")
			.restClientBuilder(restClient)
			.build();
		server.expect(once(), requestTo(URL))
			.andRespond(withSuccess("{\"answers\":" + answers.formatted(apiKey) + "}", MediaType.APPLICATION_JSON));
		OpenRouterDecisionModel model = OpenRouterDecisionModel.builder()
			.openRouterApi(api)
			.options(OpenRouterDecisionOptions.builder().model(MODEL).build())
			.build();

		assertThatThrownBy(() -> model.call("text", Map.of("q", ChoiceQuestion.of("Which?", "a", "b"))))
			.isInstanceOf(OpenRouterProtocolException.class)
			.satisfies(failure -> {
				for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
					assertThat(String.valueOf(cause.getMessage())).doesNotContain(apiKey);
					assertThat(cause.toString()).doesNotContain(apiKey);
				}
			});
	}

	@Test
	void anAnswerContractErrorAndAnHttpErrorShareTheDecisionsEndpointLabel() {
		Fixture fixture = fixture(OpenRouterDecisionOptions.builder().model(MODEL).build());
		fixture.expectSuccess("{\"answers\":{\"q\":{\"type\":\"score\",\"score\":1}}}");

		assertThatThrownBy(() -> fixture.model().call("text", Map.of("q", YesNoQuestion.of("Is it?"))))
			.isInstanceOfSatisfying(OpenRouterProtocolException.class,
					failure -> assertThat(failure.getEndpoint()).isEqualTo("/alpha/decisions"));
	}

	@Test
	void aScoreWithoutProbabilitiesKeepsThemEmpty() {
		Fixture fixture = fixture(OpenRouterDecisionOptions.builder().model(MODEL).build());
		fixture.expectSuccess("{\"answers\":{\"q\":{\"type\":\"score\",\"score\":1.5}}}");

		DecisionResponse response = fixture.model()
			.call("text", Map.of("q", ScoreQuestion.of("How?", "low", "mid", "high")));

		assertThat(response.getScore("q").getProbabilities()).isEmpty();
		assertThat(response.getScore("q").getConfidence()).isNull();
		assertThat(OpenRouterUsage.from(response).getTotalTokens()).isZero();
		assertThat(OpenRouterUsage.from(response).getCost()).isNull();
	}

	@Test
	void aProviderErrorIsClassifiedUnderTheDecisionsEndpoint() {
		Fixture fixture = fixture(OpenRouterDecisionOptions.builder().model(MODEL).build());
		fixture.server()
			.expect(once(), requestTo(URL))
			.andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
				.body("{\"error\":{\"code\":404,\"message\":\"No endpoints found matching your data policy\"}}"));

		assertThatThrownBy(() -> fixture.model().call("text", Map.of("q", YesNoQuestion.of("Is it?"))))
			.isInstanceOf(OpenRouterNonTransientApiException.class)
			.satisfies(failure -> assertThat(((OpenRouterHttpFailure) failure).getStatusCode().value()).isEqualTo(404))
			.satisfies(failure -> assertThat(((OpenRouterHttpFailure) failure).getEndpoint())
				.isEqualTo("/alpha/decisions"))
			.hasMessageNotContaining("openrouter.test");
	}

	@Test
	void theObservationRecordsTheMergedModelAndTheResponse() {
		TestObservationRegistry registry = TestObservationRegistry.create();
		RestClient.Builder restClient = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(restClient).build();
		OpenRouterDecisionModel model = OpenRouterDecisionModel.builder()
			.openRouterApi(api(restClient))
			.options(OpenRouterDecisionOptions.builder().model(MODEL).build())
			.observationRegistry(registry)
			.build();
		new Fixture(model, server, this.requests).expectSuccess(fixture("decisions-success.json"));

		model.call(TICKET, ticketQuestions());

		TestObservationRegistryAssert.assertThat(registry)
			.hasObservationWithNameEqualTo("gen_ai.client.operation")
			.that()
			.hasContextualNameEqualTo("decision " + MODEL)
			.hasLowCardinalityKeyValue("gen_ai.operation.name", "decision")
			.hasLowCardinalityKeyValue("gen_ai.system", "openrouter")
			.hasLowCardinalityKeyValue("gen_ai.request.model", MODEL)
			.hasLowCardinalityKeyValue("gen_ai.response.model", "typesafe/jev-1.14-20261014")
			.hasBeenStarted()
			.hasBeenStopped();
	}

	@Test
	void decisionsGoBesideTheVersionedApiOfACustomBaseUrl() {
		assertDecisionsGoTo(RestClient.builder(), "https://gateway.test/openrouter/v1/",
				"https://gateway.test/openrouter/alpha/decisions");
	}

	@Test
	void decisionsGoUnderAHostOnlyBaseUrl() {
		assertDecisionsGoTo(RestClient.builder(), "http://gateway.test:8080",
				"http://gateway.test:8080/alpha/decisions");
	}

	@Test
	void decisionsFollowACustomUriBuilderFactory() {
		RestClient.Builder restClient = RestClient.builder()
			.uriBuilderFactory(new DefaultUriBuilderFactory("https://gateway.test/openrouter/v1"));
		assertDecisionsGoTo(restClient, null, "https://gateway.test/openrouter/alpha/decisions");
	}

	@Test
	void aTemplatedBaseUrlIsExpandedWhenDecisionsAreRequested() {
		RestClient.Builder restClient = RestClient.builder().defaultUriVariables(Map.of("host", "gateway.test"));
		assertDecisionsGoTo(restClient, "https://{host}/api/v1", "https://gateway.test/api/alpha/decisions");
	}

	private static void assertDecisionsGoTo(RestClient.Builder restClient, @Nullable String baseUrl,
			String expectedUrl) {
		MockRestServiceServer server = MockRestServiceServer.bindTo(restClient).build();
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey("test-key")
			.baseUrl(baseUrl)
			.restClientBuilder(restClient)
			.build();
		server.expect(once(), requestTo(expectedUrl))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
			.andRespond(withSuccess(noulAnswer(), MediaType.APPLICATION_JSON));

		OpenRouterDecisionModel.builder()
			.openRouterApi(api)
			.options(OpenRouterDecisionOptions.builder().model(MODEL).build())
			.build()
			.call("text", Map.of("urgent", YesNoQuestion.of("Is it?")));

		server.verify();
	}

	private void assertProtocolError(String answers, DecisionQuestion question) {
		Fixture fixture = fixture(OpenRouterDecisionOptions.builder().model(MODEL).build());
		fixture.expectSuccess("{\"answers\":" + answers + "}");

		assertThatThrownBy(() -> fixture.model().call("text", Map.of("q", question)))
			.isInstanceOf(OpenRouterProtocolException.class)
			.hasMessageContaining("does not answer the prompt");
	}

	private static Map<String, DecisionQuestion> ticketQuestions() {
		Map<String, DecisionQuestion> questions = new LinkedHashMap<>();
		questions.put("is_refund",
				YesNoQuestion.builder()
					.text("Is the customer asking for a refund?")
					.whenYes("Asks for money back")
					.whenNo("Anything else")
					.build());
		questions.put("team",
				ChoiceQuestion.builder()
					.text("Which team should handle this?")
					.option("billing", "Payments and refunds")
					.option("technical")
					.option("sales", "Pricing")
					.build());
		questions.put("urgency",
				ScoreQuestion.builder()
					.text("How urgent is this?")
					.level("Low")
					.level("Medium")
					.level("High", "Money or access blocked")
					.build());
		return questions;
	}

	private static Media media(MimeType mimeType, byte[] data) {
		return Media.builder().mimeType(mimeType).data(data).build();
	}

	private static String noulAnswer() {
		return "{\"answers\":{\"urgent\":{\"type\":\"noul\",\"noul\":0.1}},\"model\":\"" + MODEL + "\"}";
	}

	private static String fixture(String name) {
		try (InputStream in = OpenRouterDecisionModelTests.class.getResourceAsStream("/fixtures/" + name)) {
			assertThat(in).as(name).isNotNull();
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private Fixture fixture(OpenRouterDecisionOptions defaults) {
		RestClient.Builder restClient = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(restClient).build();
		OpenRouterDecisionModel model = OpenRouterDecisionModel.builder()
			.openRouterApi(api(restClient))
			.options(defaults)
			.build();
		return new Fixture(model, server, this.requests);
	}

	private static OpenRouterApi api(RestClient.Builder restClient) {
		return OpenRouterApi.builder()
			.apiKey("test-key")
			.baseUrl("https://openrouter.test/api/v1")
			.restClientBuilder(restClient)
			.build();
	}

	private record Fixture(OpenRouterDecisionModel model, MockRestServiceServer server, List<JsonNode> requests) {

		void expectSuccess(String body) {
			this.server.expect(once(), requestTo(URL))
				.andExpect(method(HttpMethod.POST))
				.andExpect(request -> this.requests
					.add(JSON.readTree(((MockClientHttpRequest) request).getBodyAsString())))
				.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
		}

	}

}
