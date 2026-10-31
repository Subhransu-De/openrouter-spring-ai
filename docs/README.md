# OpenRouter Spring AI documentation

Reference pages for applications that use the starter. Start with [Getting started](getting-started.md).

![A call goes from your app through Spring AI's ChatClient into this library's model and API client, then to OpenRouter, which routes it to an upstream provider.](images/architecture.svg)

Pick a route:

- **First app:** [Getting started](getting-started.md), then [Chat options](chat-options.md).
- **Tools and conversations:** [Tool calling](tool-calling.md), [Reasoning and prompt caching](reasoning-and-caching.md), [Images, documents and audio](multimodal.md).
- **Production:** [Errors and retries](errors-and-retries.md), [HTTP, timeouts and limits](http-and-limits.md), [Observability](observability.md).

| Page                                                      | Answers                                                                        |
| --------------------------------------------------------- | ------------------------------------------------------------------------------ |
| [Getting started](getting-started.md)                     | How do I install it and make a first call?                                     |
| [Chat options](chat-options.md)                           | How do request options combine with defaults? Where are fallbacks and routing? |
| [Request modes](request-modes.md)                         | Chat Completions or Responses? How do structured output and tool choice work?  |
| [Supported API and nullability](api.md)                   | Which types are supported? What can be null?                                   |
| [Optional request and response extensions](extensions.md) | How do I send fields that have no typed option?                                |
| [Tool calling](tool-calling.md)                           | How do tools and tool failures work?                                           |
| [Reasoning and prompt caching](reasoning-and-caching.md)  | How do reasoning replay and cache breakpoints work?                            |
| [Errors and retries](errors-and-retries.md)               | Which exceptions occur, and what is retried?                                   |
| [HTTP, timeouts and limits](http-and-limits.md)           | How do I tune the transport, timeouts and limits?                              |
| [Images, documents and audio](multimodal.md)              | How do I send or generate images, documents and audio?                         |
| [Embeddings](embeddings.md)                               | How do I configure embeddings?                                                 |
| [Observability](observability.md)                         | Where do usage, cost and observations appear?                                  |
| [The Garage demo](garage.md)                              | How do I run the sample application?                                           |

To build the project or change it, see [CONTRIBUTING.md](../CONTRIBUTING.md). Release history is in [CHANGELOG.md](../CHANGELOG.md).
