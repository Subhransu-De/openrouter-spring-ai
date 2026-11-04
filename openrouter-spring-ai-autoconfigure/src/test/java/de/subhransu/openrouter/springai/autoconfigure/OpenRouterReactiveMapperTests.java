package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.dto.EmbeddingsRequest;
import de.subhransu.openrouter.springai.api.dto.ImagesRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

class OpenRouterReactiveMapperTests {

	@Test
	void reactiveApplicationMapperControlsBothDirectionsWithoutHttpConverters() {
		AtomicReference<String> blocking = new AtomicReference<>();
		AtomicReference<String> streaming = new AtomicReference<>();
		new ReactiveWebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(OpenRouterApiAutoConfiguration.class))
			.withBean(ObjectMapper.class, OpenRouterReactiveMapperTests::mapper)
			.withBean(ClientHttpRequestFactoryBuilder.class,
					() -> settings -> (uri, method) -> new MockClientHttpRequest(method, uri) {
						@Override
						protected ClientHttpResponse executeInternal() {
							blocking.set(getBodyAsString());
							return new MockClientHttpResponse(
									"{\"model\":\"wire\",\"data\":[]}".getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
						}
					})
			.withBean(WebClient.Builder.class, () -> WebClient.builder().clientConnector((method, uri, callback) -> {
				var request = new org.springframework.mock.http.client.reactive.MockClientHttpRequest(method, uri);
				var response = new org.springframework.mock.http.client.reactive.MockClientHttpResponse(HttpStatus.OK);
				response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
				response.setBody("{\"data\":[{\"b64_json\":\"wire\"}]}");
				return callback.apply(request)
					.then(Mono.defer(request::getBodyAsString))
					.doOnNext(streaming::set)
					.thenReturn(response);
			}))
			.withPropertyValues("spring.ai.openrouter.api-key=synthetic-key",
					"spring.ai.openrouter.base-url=https://synthetic.invalid")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBeansOfType(org.springframework.http.converter.HttpMessageConverter.class))
					.isEmpty();
				OpenRouterApi api = context.getBean(OpenRouterApi.class);
				assertThat(api.embeddings(new EmbeddingsRequest("synthetic", List.of("input"), null, null, null, null))
					.model()).isEqualTo("decoded");
				var images = new ImagesRequest("synthetic", "synthetic", null, null, null, null, null, null, null, null,
						null, true, null, null);
				assertThat(api.imagesStream(images).blockLast(Duration.ofSeconds(5)).b64Json()).isEqualTo("decoded");
				for (String body : List.of(blocking.get(), streaming.get())) {
					assertThat(new ObjectMapper().readTree(body).path("model").stringValue()).isEqualTo("encoded");
				}
			});
	}

	private static ObjectMapper mapper() {
		SimpleModule module = new SimpleModule();
		module.addSerializer(String.class, new ValueSerializer<String>() {
			@Override
			public void serialize(String value, JsonGenerator generator, SerializationContext context) {
				generator.writeString("synthetic".equals(value) ? "encoded" : value);
			}
		});
		module.addDeserializer(String.class, new ValueDeserializer<String>() {
			@Override
			public String deserialize(JsonParser parser, DeserializationContext context) {
				String value = parser.getString();
				return "wire".equals(value) ? "decoded" : value;
			}
		});
		return JsonMapper.builder().addModule(module).build();
	}

}
