# Tool calling

Spring AI 2.0 runs the tool loop in `ToolCallingAdvisor`, which sits in the `ChatClient` advisor chain, not in the chat model. This library follows that design. The model advertises your tools and returns tool calls. It never runs them:

![Sequence diagram: ChatClient sends the prompt and tool definitions to OpenRouterChatModel, which puts them on the wire and returns tool calls with a TOOL_CALLS finish reason. ToolCallingAdvisor runs your ToolCallback, appends the result and calls again until no tool calls remain. advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false)) returns the tool calls without running them.](images/tool-loop.svg)

This works the same for `call()` and `stream()` in both request modes. `ChatClient` registers the advisor by default, so `ChatClient.builder(chatModel).build()` is enough.

## A first tool

```java
class Weather {

	@Tool(description = "Current temperature in a city")
	String temperature(String city) {
		return "21 C in " + city;
	}

}

String answer = ChatClient.builder(chatModel).build()
	.prompt().user("How warm is it in Oslo?").tools(new Weather()).call().content();
```

`@Tool` is `org.springframework.ai.tool.annotation.Tool`. The [Spring AI tool calling guide](https://docs.spring.io/spring-ai/reference/api/tools.html) covers other ways to define tools.

## Where to register tools

- Through `ChatClient`, tools from `defaultTools(...)` and the request (`tools(...)`, `toolCallbacks(...)`) are advertised and executed. If you set any of them, they replace the tools on the model's default options. If you set none, the model's default tools are used.
- With a raw `ChatModel` call, the prompt options replace the model defaults. Put the tools in the prompt options.
- A tool declared only by bean name is resolved during execution through the `ToolCallbackResolver` on the `ToolCallingManager`.

## Tool names and IDs

In Chat Completions streams:

- A tool's function name must arrive complete. Missing or blank names are ignored until a nonblank name arrives. Identical repeats are accepted.
- Differing nonblank names (including split-name fragments), or a completed call without a usable name, raise `OpenRouterProtocolException` before the call is emitted.
- JSON argument fragments are concatenated by tool index. A call is emitted only after a tool-call finish reason.

Elsewhere:

- A blank function name raises `OpenRouterProtocolException` before a callback is selected. This applies to synchronous Chat Completions calls and to all Responses calls. A blank Responses `call_id` fails the same way.
- A missing Chat Completions tool-call ID is accepted.
- Malformed JSON arguments reach the tool, which reports a tool failure.

## Strict function tools

Set `OpenRouterChatOptions.builder().toolStrict(true)` or `spring.ai.openrouter.chat.tool-strict=true` to request strict schemas for every function tool, in calls and streams, in both modes. There are no per-tool overrides.

| `toolStrict`   | Wire flag                  | Schema check before HTTP |
| -------------- | -------------------------- | ------------------------ |
| `true`         | `strict: true`             | Yes                      |
| `false`        | `strict: false`            | No                       |
| unset (`null`) | omitted (provider default) | No                       |

When options are composed (for example by `ChatClient`), an unset value inherits the default and a non-null runtime value overrides it, including `false`. Chat Completions sends the flag as `tools[].function.strict`. Responses sends it as `tools[].strict`.

With `true`, a schema like this passes:

```json
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "part": { "type": "string" },
    "note": { "type": ["string", "null"] }
  },
  "required": ["part", "note"]
}
```

The rules:

- The root is an object. A root `anyOf` is rejected.
- Every object, nested ones too, sets `additionalProperties` to `false` and lists every property in `required` once. Make a value optional by allowing `null`.
- Arrays declare `items`. A `$ref` must be a local pointer such as `#/$defs/part`.
- `allOf`, `oneOf`, `not`, `if`/`then`/`else`, `patternProperties` and similar keywords are rejected.

A broken schema raises `IllegalArgumentException` before the request is sent.

![An annotated strict schema: an object root, additionalProperties false on every object, a required array that lists every property exactly once, and optional values that allow null and stay required. allOf, oneOf, anyOf at the root, and external or unresolved $ref are rejected before HTTP. Chat Completions sends tools[].function.strict; Responses sends tools[].strict.](images/strict-schema.svg)

The library never rewrites a schema. Use a routed model and provider that support strict function tools; the library does not discover capabilities. Validation covers the common [strict function contract](https://developers.openai.com/api/docs/guides/function-calling#strict-mode), not every provider keyword, size limit or model restriction. A provider rejection surfaces as a normal API error, with no retry as non-strict. Response-format strictness is unaffected.

## Tool failure policy

By default, when a tool throws, the model receives `{"error":"Tool execution failed"}` as the tool result and the loop continues. The library logs the failure (details only at debug level) and records it on the current observation.

The starter does this by supplying `OpenRouterToolExecutionExceptionProcessor` before Spring AI creates its shared manager. The processor therefore also applies to other providers that use that manager.

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

A custom or delegating manager can instead implement `de.subhransu.openrouter.springai.chat.OpenRouterToolFailurePolicy` and return the processor it uses from `toolExecutionExceptionProcessor()`. This declares application responsibility; it cannot prove that the implementation honors it.

To keep Spring AI's throwing behavior in a custom manager, declare `DefaultToolExecutionExceptionProcessor.builder().alwaysThrow(true).build()` as a bean and pass that bean to the manager. An inline instance fails validation.

The `spring.ai.openrouter.chat.allow-unsafe-tool-failure-results=true` opt-out skips manager validation and makes the application responsible for redacting failure results. It does not disable the automatically supplied processor.
