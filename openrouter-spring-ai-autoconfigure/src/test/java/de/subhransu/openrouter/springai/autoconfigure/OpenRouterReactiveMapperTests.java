package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.dto.EmbeddingsRequest;
import de.subhransu.openrouter.springai.api.dto.ImagesRequest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
		RecordingTransport transport = new RecordingTransport()
			.blockingResponse(HttpStatus.OK, "{\"model\":\"wire\",\"data\":[]}")
			.streamingResponse(HttpStatus.OK, MediaType.APPLICATION_JSON, "{\"data\":[{\"b64_json\":\"wire\"}]}");
		transport
			.installOn(new ReactiveWebApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(OpenRouterApiAutoConfiguration.class))
				.withBean(JsonMapper.class, OpenRouterReactiveMapperTests::mapper))
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
				// The stream is finite and in memory; the bound only keeps a broken
				// stream from hanging the build.
				assertThat(api.imagesStream(images).blockLast(Duration.ofSeconds(5)).b64Json()).isEqualTo("decoded");
				List<RecordingTransport.Exchange> exchanges = transport.exchanges();
				assertThat(exchanges).extracting(RecordingTransport.Exchange::streaming)
					.containsExactlyInAnyOrder(false, true);
				assertThat(exchanges).allSatisfy(
						exchange -> assertThat(new ObjectMapper().readTree(exchange.body()).path("model").stringValue())
							.as("request body sent to %s", exchange.uri())
							.isEqualTo("encoded"));
			});
	}

	private static JsonMapper mapper() {
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
