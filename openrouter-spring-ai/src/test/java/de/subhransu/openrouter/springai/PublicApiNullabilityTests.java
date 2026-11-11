package de.subhransu.openrouter.springai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jetbrains.kotlin.cli.common.ExitCode;
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class PublicApiNullabilityTests {

	private static final String UNSAFE_HEADER = """
			import de.subhransu.openrouter.springai.api.dto.*
			import de.subhransu.openrouter.springai.chat.*
			import de.subhransu.openrouter.springai.embedding.*
			import de.subhransu.openrouter.springai.image.*
			import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor
			""";

	private static final int HEADER_LINES = (int) UNSAFE_HEADER.lines().count();

	private static final List<String[]> UNSAFE_DECLARATIONS = List.of(
			new String[] { "nullable options model", "fun invalidModel(o: OpenRouterChatOptions): String = o.model",
					"return type mismatch: expected 'String', actual 'String\\?'" },
			new String[] { "nullable usage", "fun invalidUsage(r: ChatCompletionResponse): Usage = r.usage()",
					"return type mismatch: expected 'Usage', actual 'Usage\\?'" },
			new String[] { "nullable choices",
					"fun invalidChoice(r: ChatCompletionResponse): Choice = r.choices()!!.first()",
					"return type mismatch: expected 'Choice', actual 'Choice\\?'" },
			new String[] { "nullable metadata",
					"fun invalidMetadata(o: OpenRouterChatOptions): Map<String, Any> = o.metadata!!",
					"return type mismatch: expected 'Map<String, Any>', actual '\\(Mutable\\)Map<String, Any\\?>'" },
			new String[] { "nullable image style", "fun invalidStyle(o: OpenRouterImageOptions): String = o.style",
					"return type mismatch: expected 'String', actual 'String\\?'" },
			new String[] { "nullable responses status", "fun invalidStatus(r: ResponsesResult): String = r.status()",
					"return type mismatch: expected 'String', actual 'String\\?'" },
			new String[] { "nullable embedding item",
					"fun invalidEmbedding(r: EmbeddingsResponse): EmbeddingsResponse.EmbeddingData = r.data()!!.first()",
					"return type mismatch: expected '(\\w+\\.)*EmbeddingData', actual '(\\w+\\.)*EmbeddingData\\?'" },
			new String[] { "nullable image item",
					"fun invalidImage(r: ImagesResponse): ImagesResponse.ImageData = r.data()!!.first()",
					"return type mismatch: expected '(\\w+\\.)*ImageData', actual '(\\w+\\.)*ImageData\\?'" },
			new String[] { "nullable embedding dimensions",
					"fun invalidDimensions(o: OpenRouterEmbeddingOptions): Int = o.dimensions",
					"return type mismatch: expected 'Int', actual 'Int\\?'" },
			new String[] { "failure policy returning null",
					"class Invalid : OpenRouterToolFailurePolicy { override fun toolExecutionExceptionProcessor(): ToolExecutionExceptionProcessor? = null }",
					"return type of 'fun toolExecutionExceptionProcessor\\(\\): ToolExecutionExceptionProcessor\\?' is not a subtype" });

	private static final List<UnsafeCase> UNSAFE_CASES = IntStream.range(0, UNSAFE_DECLARATIONS.size())
		.mapToObj(index -> new UnsafeCase(UNSAFE_DECLARATIONS.get(index)[0], UNSAFE_DECLARATIONS.get(index)[1],
				UNSAFE_DECLARATIONS.get(index)[2], HEADER_LINES + 1 + index))
		.toList();

	private static final int CONTROL_LINE = HEADER_LINES + UNSAFE_CASES.size() + 1;

	/** Safe counterparts of the unsafe declarations; this line must compile. */
	private static final String CONTROL = "fun control(o: OpenRouterChatOptions, r: ChatCompletionResponse, i: OpenRouterImageOptions): Int = "
			+ "(o.model ?: \"\").length + (r.usage()?.totalTokens() ?: 0) + (r.choices()?.size ?: 0) + (o.metadata?.size ?: 0) + (i.style ?: \"\").length";

	private static CompileResult UNSAFE_COMPILE;

	@TempDir
	static Path shared;

	@TempDir
	Path directory;

	@BeforeAll
	static void compileUnsafeConsumers() throws IOException {
		var source = new StringBuilder(UNSAFE_HEADER);
		UNSAFE_CASES.forEach(unsafe -> source.append(unsafe.declaration()).append('\n'));
		source.append(CONTROL).append('\n');
		UNSAFE_COMPILE = compileSource(shared, source.toString());
		assertThat(UNSAFE_COMPILE.exitCode()).as(UNSAFE_COMPILE.output()).isEqualTo(ExitCode.COMPILATION_ERROR);
	}

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
				    val changed: OpenRouterChatOptions = options.mutate().outputSchema(null).toolContext(null).build()
				    val copy: OpenRouterChatOptions = changed.mutate().build()
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
				    val manager: org.springframework.ai.model.tool.ToolCallingManager =
				        OpenRouterToolCallingManagers.withFailurePolicy(processor) { it.maxCallsPerTool(3) }
				}
				""", ExitCode.OK);
	}

	@ParameterizedTest
	@MethodSource("unsafeCases")
	void kotlinRejectsUnsafeConsumerContracts(UnsafeCase unsafe) {
		var diagnostics = UNSAFE_COMPILE.errorsOnLine(unsafe.line());
		assertThat(diagnostics).as("diagnostics for '%s'", unsafe.name()).isNotEmpty();
		assertThat(diagnostics).allSatisfy(entry -> assertThat(entry).containsPattern(unsafe.diagnostic()));
	}

	@Test
	void kotlinAcceptsTheSafeFormsOfTheUnsafeContracts() {
		assertThat(UNSAFE_COMPILE.errorsOnLine(CONTROL_LINE)).as("safe control").isEmpty();
		assertThat(UNSAFE_COMPILE.errorLines()).as("only the unsafe declarations fail to compile")
			.isSubsetOf(UNSAFE_CASES.stream().map(UnsafeCase::line).toList());
	}

	static List<UnsafeCase> unsafeCases() {
		return UNSAFE_CASES;
	}

	private void compile(String source, ExitCode expected) throws IOException {
		var result = compileSource(this.directory, source);
		assertThat(result.exitCode()).withFailMessage(result.output()).isEqualTo(expected);
	}

	private static CompileResult compileSource(Path directory, String source) throws IOException {
		Path input = directory.resolve("Consumer.kt");
		Files.writeString(input, source);
		var output = new ByteArrayOutputStream();
		try (var diagnostics = new PrintStream(output, true, StandardCharsets.UTF_8)) {
			ExitCode result = new K2JVMCompiler().exec(diagnostics, "-no-stdlib", "-no-reflect", "-jvm-target", "17",
					"-Xjspecify-annotations=strict", "-classpath",
					System.getProperty("compatibility.classpath", System.getProperty("java.class.path")), "-d",
					directory.resolve("classes").toString(), input.toString());
			return new CompileResult(result, output.toString(StandardCharsets.UTF_8));
		}
	}

	record CompileResult(ExitCode exitCode, String output) {

		List<String> errorsOnLine(int line) {
			return output().lines().filter(entry -> entry.contains("Consumer.kt:" + line + ":")).toList();
		}

		List<Integer> errorLines() {
			return output().lines()
				.map(Pattern.compile("Consumer\\.kt:(\\d+):\\d+: error:")::matcher)
				.filter(Matcher::find)
				.map(matcher -> Integer.parseInt(matcher.group(1)))
				.distinct()
				.toList();
		}

	}

	/**
	 * One unsafe consumer declaration and the Kotlin diagnostic that must reject it.
	 */
	record UnsafeCase(String name, String declaration, String diagnostic, int line) {

		@Override
		public String toString() {
			return this.name;
		}

	}

}
