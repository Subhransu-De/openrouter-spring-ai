# OpenRouter Spring AI documentation

Reference pages for applications that use the starter. Start with [Getting started](getting-started.md).

| Page                                                      | Covers                                                                                                        |
| --------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| [Getting started](getting-started.md)                     | Install, first call, main properties, release compatibility, model selection and replacement beans.           |
| [Chat options](chat-options.md)                           | How runtime options replace or compose with defaults, null means inherit, option snapshots.                   |
| [Request modes](request-modes.md)                         | Chat Completions and experimental Responses, structured output, `toolChoice`, refusals and incomplete output. |
| [Supported API and nullability](api.md)                   | The types applications can depend on, request builders, implementation details, JSpecify null contracts.      |
| [Optional request and response extensions](extensions.md) | `extraBody` and `providerExtraBody`, the middle-out transform, unknown response fields.                       |
| [Tool calling](tool-calling.md)                           | Model and advisor roles, tool names and IDs, strict function tools, tool failure policy.                      |
| [Reasoning and prompt caching](reasoning-and-caching.md)  | Reasoning replay across turns and tool rounds, explicit cache breakpoints.                                    |
| [Errors and retries](errors-and-retries.md)               | Responses failures, protocol errors, stream retries and `Retry-After`.                                        |
| [HTTP, timeouts and limits](http-and-limits.md)           | Transport customization, timeouts, per-request headers, response and stream state limits.                     |
| [Images, documents and audio](multimodal.md)              | Image, PDF, audio and video inputs; image generation; streamed chat audio output.                             |
| [Embeddings](embeddings.md)                               | Embedding configuration, metadata mode, dimension discovery.                                                  |
| [Observability](observability.md)                         | Observations, usage and cost metadata, content logging.                                                       |
| [The Garage demo](garage.md)                              | The sample application, its HTTP API, run selection and evidence.                                             |

To build the project or change it, see [CONTRIBUTING.md](../CONTRIBUTING.md). Release history is in [CHANGELOG.md](../CHANGELOG.md).
