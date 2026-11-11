package de.subhransu.openrouter.springai.api;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

class OpenRouterApiTests {

	private static final String RESPONSE_BODY = """
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
			""";

	@Test
	void leavesCallerRequestFactoryUntouchedWhenTimeoutIsSet() {
		// The core never configures the RestClient's request factory; that is the
		// auto-configuration's job. The configured timeout only guards streaming gaps.
		AtomicInteger requests = new AtomicInteger();
		ClientHttpRequestFactory factory = (uri, method) -> {
			requests.incrementAndGet();
			MockClientHttpRequest request = new MockClientHttpRequest(method, uri);
			MockClientHttpResponse response = new MockClientHttpResponse(RESPONSE_BODY.getBytes(StandardCharsets.UTF_8),
					HttpStatus.OK);
			response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
			request.setResponse(response);
			return request;
		};
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey("test-key")
			.baseUrl("https://openrouter.test/api/v1")
			.restClientBuilder(RestClient.builder().requestFactory(factory))
			.timeout(Duration.ofSeconds(30))
			.build();

		ResponsesResult result = api.responses(request());

		assertThat(result.id()).isEqualTo("resp-2");
		assertThat(requests).hasValue(1);
	}

	private static ResponsesRequest request() {
		return ResponsesRequest.builder()
			.model("openai/gpt-5.4")
			.input("hello")
			.maxOutputTokens(128)
			.stream(false)
			.build();
	}

}
