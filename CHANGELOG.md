# Changelog

Notable changes in each release. The full notes for each release candidate are on the [Releases page](https://github.com/Subhransu-De/openrouter-spring-ai/releases).

## Unreleased

### Changed

- A response that breaks the wire contract now raises `OpenRouterProtocolException` in every request mode, for calls and streams. This covers undecodable JSON, blank or conflicting tool names, embedding count, index or dimension mismatches, and invalid audio fragments. RC3 raised `IllegalStateException` or `IllegalArgumentException` for some of these. Catch `OpenRouterProtocolException` instead. [#211](https://github.com/Subhransu-De/openrouter-spring-ai/pull/211)
- A synchronous Chat Completions tool call, or any Responses tool call, with a blank function name fails with `OpenRouterProtocolException` before a callback is selected. [#211](https://github.com/Subhransu-De/openrouter-spring-ai/pull/211)
- `extraBody` accepts `transforms`. Send an empty list to turn off OpenRouter's middle-out transform. [#211](https://github.com/Subhransu-De/openrouter-spring-ai/pull/211)
- The library is built and tested with the Jackson version that Spring Boot manages, instead of forcing Jackson 3.2.3. [#210](https://github.com/Subhransu-De/openrouter-spring-ai/pull/210)

### Documentation

- The README is now a short landing page. Usage guides are in [`docs/`](docs/README.md), build and quality-check guidance is in [CONTRIBUTING.md](CONTRIBUTING.md), and release history is in this file.
- Diagrams replace long explanations of the architecture, model selection, option composition, request modes, tool calling, reasoning replay, cache breakpoints, stream retries, limits, chat audio output and the Garage demo.

## [0.1.0-RC3](https://github.com/Subhransu-De/openrouter-spring-ai/releases/tag/v0.1.0-RC3) - 2026-10-02

Adds strict function tools, explicit cache breakpoints, optional request and response extensions, embedding metadata and dimension discovery, cumulative Responses state limits, PDF, audio and video inputs, and streaming chat audio output.

### Upgrade notes

- RC2 code that calls a `ChatCompletionRequest` or `ResponsesRequest` constructor no longer compiles. Build the requests with `builder()` instead. See [Supported API and nullability](docs/api.md#build-low-level-requests).
- The [RC2 README](https://github.com/Subhransu-De/openrouter-spring-ai/blob/v0.1.0-RC2/README.md) describes the RC2 API.

## [0.1.0-RC2](https://github.com/Subhransu-De/openrouter-spring-ai/releases/tag/v0.1.0-RC2) - 2026-09-19

Focuses on response correctness, streaming reliability, and clearer integration contracts, and adds structured-output properties and explicit tool-failure policies.

- The repository is now `Subhransu-De/openrouter-spring-ai`. Maven coordinates and Java packages are unchanged. RC2 corrects the module backlinks in the published metadata.

## [0.1.0-RC1](https://github.com/Subhransu-De/openrouter-spring-ai/releases/tag/v0.1.0-RC1) - 2026-09-12

The first release candidate: native OpenRouter chat, embedding, and image support for Spring AI, with Chat Completions as the default and Responses as an experimental opt-in.

- The published RC1 metadata keeps broken module backlinks from before the repository rename. Published metadata cannot be changed in place.
