# Tool calling

Spring AI 2.0 moved the tool-execution loop out of the chat models and into
`ToolCallingAdvisor`, which runs in the `ChatClient` advisor chain. This library follows
that design exactly (as do Spring AI's own OpenAI and Anthropic models):

- **The model advertises and surfaces, never executes.** `OpenRouterChatModel` puts the
  tool definitions from your `ToolCallback`s on the wire and returns tool-call responses
  as-is (with a `TOOL_CALLS` finish reason), for both `call()` and `stream()`, in both
  chat-completions and responses mode. Your callbacks are never invoked by the model.
- **`ChatClient` + `ToolCallingAdvisor` runs the loop.** The advisor invokes your
  `ToolCallback`s, appends the results to the conversation, and calls the provider again
  until a final answer arrives — for calls and streams alike. `ChatClient`
  auto-registers the advisor by default, so `ChatClient.builder(chatModel).build()` is
  enough; opt out per request with
  `advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))` to surface tool calls
  without executing them.

Register tools on the request options (or the `ChatClient`), not as model default
options: the advisor executes with the options it sees on the prompt. Tools declared only
by bean name are resolved through the `ToolCallbackResolver` configured on the
`ToolCallingManager` during execution.

## Tool names and IDs

In Chat Completions streams, a tool's function name must arrive as a complete name.
Missing or blank names are ignored until a nonblank name arrives; identical repeated
names are accepted. Differing nonblank names (including split-name fragments) and
completed calls without a usable name fail with `OpenRouterProtocolException` before the
call is emitted. JSON argument fragments are still concatenated by tool index, and
calls require a tool-call finish reason before they can be emitted.

A synchronous Chat Completions tool call, or any Responses tool call, with a blank function name fails with `OpenRouterProtocolException` before a callback is selected. A blank Responses `call_id` fails the same way. Missing Chat Completions tool-call IDs remain accepted, and malformed JSON arguments still reach the tool, which reports them as a tool failure.

## Strict function tools

Set `OpenRouterChatOptions.builder().toolStrict(true)` or
`spring.ai.openrouter.chat.tool-strict=true` to request strict schemas for every
function tool. This applies to calls and streams in both modes: Chat Completions
sends `tools[].function.strict`; experimental Responses sends `tools[].strict`.
There are no per-tool overrides. During option composition, an omitted/null option
inherits the default; a non-null runtime option overrides it, including `false`. With no default, null
omits the wire flag, preserving provider defaults; `false` explicitly disables
strictness. Copying and composing options preserve these distinctions.

The library validates rather than adapts schemas when `true`. Supply an object
root, `additionalProperties: false` on every object, and a `required` array
containing every property exactly once (empty objects use empty `properties` and
`required`). Optional values must explicitly allow null **and** remain required,
for example `"note": {"type": ["string", "null"]}` with `"note"` in `required`.
The validator follows nested properties, array items, `anyOf`, definitions, and
local JSON-pointer `$ref`s, including recursion. External/unresolved references,
root `anyOf`, and unsupported composition such as `allOf`/`oneOf` are rejected
before HTTP. Schemas are never rewritten; the callback retains its original schema.
Omitted/false strictness preserves existing schema handling.

Use a routed model/provider that supports strict function tools; this option does
not discover capabilities or guarantee support across OpenRouter. Validation checks
the common [strict function contract](https://developers.openai.com/api/docs/guides/function-calling#strict-mode),
not every provider-specific schema keyword, size limit, or model restriction.
Provider rejections propagate through normal API errors, without silently retrying
as non-strict. `ToolCallingAdvisor` still owns execution; response-format strictness
is unaffected.

## Tool failure policy

When OpenRouter is selected and no replacement `ChatModel` is declared, the starter
supplies `OpenRouterToolExecutionExceptionProcessor` before Spring AI creates its
shared manager. It returns a stable sanitized error result. This processor also applies
to other providers using that shared manager. A declared `ToolExecutionExceptionProcessor`
bean takes precedence; `spring.ai.tools.throw-exception-on-error=true` retains Spring AI's
throwing policy instead.

A custom `ToolCallingManager` bean must declare its failure policy. To build Spring AI's
default manager with a known processor, use
`de.subhransu.openrouter.springai.chat.OpenRouterToolCallingManagers`:

```java
@Bean
ToolCallingManager toolCallingManager(ToolExecutionExceptionProcessor processor,
		ToolCallbackResolver resolver) {
	return OpenRouterToolCallingManagers.withFailurePolicy(processor,
			builder -> builder.toolCallbackResolver(resolver));
}
```

The customizer receives Spring AI's `DefaultToolCallingManager.Builder`. The factory sets
the processor after the customizer runs, so a processor set inside the customizer is
replaced. A plain `ToolCallingManager.builder().build()` bean fails at startup because
Spring AI does not expose which processor it uses.

Custom and delegating implementations can instead implement
`de.subhransu.openrouter.springai.chat.OpenRouterToolFailurePolicy` and return the processor
actually used by execution from `toolExecutionExceptionProcessor()`. Delegates must use that
same processor. The contract applies to both calls and streams; it declares application
responsibility and cannot prove that a custom implementation honors its declaration.

Either way, the processor must be an application-declared bean or an
`OpenRouterToolExecutionExceptionProcessor`. To keep Spring AI's throwing behavior in a
custom manager, declare `DefaultToolExecutionExceptionProcessor.builder().alwaysThrow(true).build()`
as a bean and pass that bean to the manager; an inline instance fails validation.

Managers without a verifiable policy fail validation. The existing
`spring.ai.openrouter.chat.allow-unsafe-tool-failure-results=true` opt-out skips manager
validation and transfers failure-result redaction responsibility to the application; it does
not disable the automatically supplied processor. Declaring an unrelated processor bean
does not approve a manager that uses a different processor.
