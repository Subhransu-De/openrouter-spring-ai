# OpenRouter Spring AI documentation

These pages hold what the source cannot tell you: upstream provider behavior, design decisions, compatibility promises and operational advice. For options, defaults, exceptions and limits, read the Javadoc and the `spring.ai.openrouter.*` property metadata.

![A call goes from your app through Spring AI's ChatClient into this library's model and API client, then to OpenRouter, which routes it to an upstream provider.](images/architecture.svg)

- [Getting started](getting-started.md): tested versions, and pitfalls next to another provider starter.
- [Chat](chat.md): request modes, sessions, structured output, tools, reasoning, prompt caching and extra fields.
- [Media and embeddings](media-and-embeddings.md): attachments, image generation, chat audio, speech, transcription and embedding migration.
- [Production](production.md): retries and billing, timeouts, HTTP customization, stream limits and cost.
- [Supported API](api.md): the types you can depend on.
- [The Garage demo](garage.md): the sample application and what its evidence proves.
