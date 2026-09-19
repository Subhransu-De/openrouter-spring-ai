package example;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.image.ImageModel;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
public class Consumer {

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = """
                    {"model":"synthetic-model","choices":[{"index":0,"message":{
                    "role":"assistant","content":"Hello."},"finish_reason":"stop"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (var context = new SpringApplicationBuilder(Consumer.class)
                .web(WebApplicationType.NONE)
                .properties(Map.of(
                        "spring.ai.openrouter.api-key", "synthetic-packaging-test-key",
                        "spring.ai.openrouter.base-url", "http://127.0.0.1:" + server.getAddress().getPort(),
                        "spring.ai.openrouter.chat.model", "synthetic-model",
                        "spring.ai.model.chat", "openrouter",
                        "spring.ai.model.embedding", "openrouter",
                        "spring.ai.model.image", "openrouter"))
                .run()) {
            context.getBean(ChatModel.class);
            context.getBean(EmbeddingModel.class);
            context.getBean(ImageModel.class);
            ChatClient client = context.getBean(ChatClient.Builder.class).build();
            if (!"Hello.".equals(client.prompt().user("Say hello.").call().content())) {
                throw new IllegalStateException("Synthetic ChatClient response was not mapped");
            }
        } finally {
            server.stop(0);
        }
    }
}
