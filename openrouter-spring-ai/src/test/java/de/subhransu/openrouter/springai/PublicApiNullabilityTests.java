package de.subhransu.openrouter.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterResponsesRequestMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jetbrains.kotlin.cli.common.ExitCode;
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class PublicApiNullabilityTests {

	@TempDir
	Path directory;

	@Test
	void kotlinConsumerCanUseNullableOptionsResponsesAndCustomPolicy() throws IOException {
		compile("""
				import de.subhransu.openrouter.springai.api.dto.*
				import de.subhransu.openrouter.springai.chat.*
				import de.subhransu.openrouter.springai.embedding.*
				import de.subhransu.openrouter.springai.image.*
				import org.springframework.ai.chat.prompt.ChatOptions
				import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor

				class Policy : OpenRouterToolFailurePolicy {
				    override fun toolExecutionExceptionProcessor(): ToolExecutionExceptionProcessor =
				        OpenRouterToolExecutionExceptionProcessor()
				}
				fun consumer(response: ChatCompletionResponse, result: ResponsesResult, images: ImagesResponse) {
				    val options = OpenRouterChatOptions.builder().model(null).temperature(null)
				        .metadata(mapOf("optional" to null)).build()
				    val portable: ChatOptions = options
				    val model: String? = portable.model
				    options.setOutputSchema(null)
				    options.setToolContext(null)
				    val copy: OpenRouterChatOptions = options.copy()
				    val merged: OpenRouterChatOptions = copy.merge(null)
				    val embedding: Int? = OpenRouterEmbeddingOptions.builder().dimensions(null).build().dimensions
				    val style: String? = OpenRouterImageOptions.builder().width(null).build().style
				    val usage: Usage? = response.usage()
				    val tokens: Int? = usage?.promptTokens()
				    val text: String? = response.choices()?.firstOrNull()?.message()?.content()?.toString()
				    val status: String? = result.status()
				    val url: String? = images.data()?.firstOrNull()?.url()
				    val cost: Double? = OpenRouterUsage(null, null, null, null, null, null, null).cost
				    val processor: ToolExecutionExceptionProcessor = Policy().toolExecutionExceptionProcessor()
				}
				""", ExitCode.OK);
	}

	@ParameterizedTest
	@ValueSource(strings = { "fun invalid(o: OpenRouterChatOptions): String = o.model",
			"fun invalid(r: ChatCompletionResponse): Usage = r.usage()",
			"fun invalid(r: ChatCompletionResponse): Choice = r.choices()!!.first()",
			"fun invalid(o: OpenRouterChatOptions): Map<String, Any> = o.metadata!!",
			"fun invalid(o: OpenRouterImageOptions): String = o.style",
			"class Invalid : OpenRouterToolFailurePolicy { override fun toolExecutionExceptionProcessor(): ToolExecutionExceptionProcessor? = null }" })
	void kotlinRejectsUnsafeConsumerContracts(String source) throws IOException {
		compile("""
				import de.subhransu.openrouter.springai.api.dto.*
				import de.subhransu.openrouter.springai.chat.*
				import de.subhransu.openrouter.springai.image.*
				import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor
				""" + source, ExitCode.COMPILATION_ERROR);
	}

	@Test
	void nullRuntimeOptionInheritsEvenWhenSwitchingRequestMode() {
		var defaults = OpenRouterChatOptions.builder().seed(7).build();
		var runtime = OpenRouterChatOptions.builder()
			.requestMode(OpenRouterRequestMode.OPENAI_RESPONSES)
			.seed(null)
			.build();
		var merged = defaults.merge(runtime);
		assertThat(merged.getSeed()).isEqualTo(7);
		assertThatThrownBy(
				() -> new OpenRouterResponsesRequestMapper(new ObjectMapper()).map(List.of(), merged, false, List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("seed");
	}

	private void compile(String source, ExitCode expected) throws IOException {
		Path input = this.directory.resolve("Consumer.kt");
		Files.writeString(input, source);
		var output = new ByteArrayOutputStream();
		try (var diagnostics = new PrintStream(output, true, StandardCharsets.UTF_8)) {
			ExitCode result = new K2JVMCompiler().exec(diagnostics, "-no-stdlib", "-no-reflect", "-jvm-target", "17",
					"-Xjspecify-annotations=strict", "-classpath",
					System.getProperty("compatibility.classpath", System.getProperty("java.class.path")), "-d",
					this.directory.resolve("classes").toString(), input.toString());
			assertThat(result).withFailMessage(output.toString(StandardCharsets.UTF_8)).isEqualTo(expected);
		}
	}

}
