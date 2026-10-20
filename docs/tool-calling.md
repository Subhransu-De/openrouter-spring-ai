# Tool calling

Spring AI 2.0 moved the tool-execution loop out of the chat models and into `ToolCallingAdvisor`, which runs in the `ChatClient` advisor chain. This library follows that design exactly, as do Spring AI's own OpenAI and Anthropic models. The model never runs your tools:

![Sequence diagram: ChatClient sends the prompt and tool definitions to OpenRouterChatModel, which puts them on the wire and returns tool calls with a TOOL_CALLS finish reason. ToolCallingAdvisor runs your ToolCallback, appends the result and calls again until no tool calls remain. advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false)) returns the tool calls without running them.](images/tool-loop.svg)

This works the same for `call()` and `stream()` in both request modes. `ChatClient` registers the advisor by default, so `ChatClient.builder(chatModel).build()` is enough.

Register tools on the request options (or the `ChatClient`), not as model default options: the advisor executes with the options it sees on the prompt. Tools declared only by bean name are resolved through the `ToolCallbackResolver` configured on the `ToolCallingManager` during execution.

## Tool names and IDs

In Chat Completions streams, a tool's function name must arrive as a complete name. Missing or blank names are ignored until a nonblank name arrives; identical repeated names are accepted. Differing nonblank names (including split-name fragments) and completed calls without a usable name fail with `OpenRouterProtocolException` before the call is emitted. JSON argument fragments are still concatenated by tool index, and calls require a tool-call finish reason before they can be emitted.

A synchronous Chat Completions tool call, or any Responses tool call, with a blank function name fails with `OpenRouterProtocolException` before a callback is selected. A blank Responses `call_id` fails the same way. Missing Chat Completions tool-call IDs remain accepted, and malformed JSON arguments still reach the tool, which reports them as a tool failure.

## Strict function tools

Set `OpenRouterChatOptions.builder().toolStrict(true)` or `spring.ai.openrouter.chat.tool-strict=true` to request strict schemas for every function tool, for calls and streams in both modes. There are no per-tool overrides. During option composition, an omitted/null option inherits the default; a non-null runtime option overrides it, including `false`. With no default, null omits the wire flag, preserving provider defaults; `false` explicitly disables strictness. Copying and composing options preserve these distinctions.

With `true`, the library validates each schema before HTTP:

![An annotated strict schema: an object root, additionalProperties false on every object, a required array that lists every property exactly once, and optional values that allow null and stay required. allOf, oneOf, anyOf at the root, and external or unresolved $ref are rejected before HTTP. Chat Completions sends tools[].function.strict; Responses sends tools[].strict.](images/strict-schema.svg)

Schemas are never rewritten; the callback retains its original schema. Omitted/false strictness preserves existing schema handling.

Use a routed model/provider that supports strict function tools; this option does not discover capabilities or guarantee support across OpenRouter. Validation checks the common [strict function contract](https://developers.openai.com/api/docs/guides/function-calling#strict-mode), not every provider-specific schema keyword, size limit, or model restriction. Provider rejections propagate through normal API errors, without silently retrying as non-strict. `ToolCallingAdvisor` still owns execution; response-format strictness is unaffected.

## Tool failure policy

The starter makes sure a failing tool returns a stable, sanitized error result instead of leaking exception details to the model. It supplies `OpenRouterToolExecutionExceptionProcessor` before Spring AI creates its shared manager, so the processor also applies to other providers that use that manager.

![Two decision trees. Which processor handles a tool exception: an application ChatModel bean means you own the policy; an application ToolExecutionExceptionProcessor bean takes precedence; spring.ai.tools.throw-exception-on-error=true keeps Spring AI's throwing policy; otherwise OpenRouterToolExecutionExceptionProcessor is used. Whether a custom ToolCallingManager bean starts: one built with OpenRouterToolCallingManagers.withFailurePolicy or one that implements OpenRouterToolFailurePolicy starts; a plain ToolCallingManager.builder().build() fails at startup unless allow-unsafe-tool-failure-results=true.](images/tool-failure-policy.svg)

To build Spring AI's default manager with a known processor, use `de.subhransu.openrouter.springai.chat.OpenRouterToolCallingManagers`:

```java
@Bean
ToolCallingManager toolCallingManager(ToolExecutionExceptionProcessor processor,
		ToolCallbackResolver resolver) {
	return OpenRouterToolCallingManagers.withFailurePolicy(processor,
			builder -> builder.toolCallbackResolver(resolver));
}
```

The customizer receives Spring AI's `DefaultToolCallingManager.Builder`. The factory sets the processor after the customizer runs, so a processor set inside the customizer is replaced. A plain `ToolCallingManager.builder().build()` bean fails at startup because Spring AI does not expose which processor it uses.

A custom or delegating manager can instead implement `de.subhransu.openrouter.springai.chat.OpenRouterToolFailurePolicy` and return the processor it actually uses from `toolExecutionExceptionProcessor()`. This declares application responsibility; it cannot prove that the implementation honors it.

To keep Spring AI's throwing behavior in a custom manager, declare `DefaultToolExecutionExceptionProcessor.builder().alwaysThrow(true).build()` as a bean and pass that bean to the manager; an inline instance fails validation.

The `spring.ai.openrouter.chat.allow-unsafe-tool-failure-results=true` opt-out skips manager validation and makes the application responsible for redacting failure results. It does not disable the automatically supplied processor.
