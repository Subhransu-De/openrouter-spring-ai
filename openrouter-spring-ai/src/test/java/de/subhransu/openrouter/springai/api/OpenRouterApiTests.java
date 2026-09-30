package de.subhransu.openrouter.springai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenRouterApiTests {

	@Test
	void leavesCallerRequestFactoryUntouchedWhenTimeoutIsSet() {
		// The core never configures the RestClient's request factory (that is the
		// auto-configuration's
		// job). MockRestServiceServer installs its own factory; if a configured timeout
		// clobbered it,
		// the mock server would never see the request and verify() would fail.
		RestClient.Builder restClientBuilder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey("test-key")
			.baseUrl("https://openrouter.test/api/v1")
			.restClientBuilder(restClientBuilder)
			.timeout(java.time.Duration.ofSeconds(30))
			.build();

		server.expect(once(), requestTo("https://openrouter.test/api/v1/responses")).andRespond(withSuccess("""
				{
				  "id": "resp-2",
				  "object": "response",
				  "created_at": 123,
				  "model": "openai/gpt-5.4",
				  "status": "completed",
				  "output": [
				    {
				      "type": "message",
				      "role": "assistant",
				      "content": [{"type": "output_text", "text": "ok"}]
				    }
				  ],
				  "usage": {"input_tokens": 1, "output_tokens": 1, "total_tokens": 2}
				}
				""", MediaType.APPLICATION_JSON));

		ResponsesResult result = api.responses(ResponsesRequest.builder()
			.model("openai/gpt-5.4")
			.input("hello")
			.maxOutputTokens(128)
			.stream(false)
			.build());

		assertThat(result.id()).isEqualTo("resp-2");
		server.verify();
	}

}
