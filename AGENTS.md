# Spring AI OpenRouter Starter

This community library implements native OpenRouter chat, embedding, and image support for Spring AI. It is independent of OpenRouter and Spring AI.

## Project priorities

- Preserve Spring AI model contracts while exposing OpenRouter routing, reasoning, usage, and attribution capabilities.
- Keep Chat Completions the default; Responses mode remains an explicit experimental opt-in.
- Preserve public options, property names, and documented behavior unless the task explicitly changes their contract.
- Prefer the smallest implementation that handles the required behavior. Explain meaningful compatibility tradeoffs before changing a public contract.

## Vocabulary and modules

- OpenRouter is the routing API; an upstream provider hosts a model. This library is the Spring AI integration.
- Request mode means Chat Completions or Responses. Streaming means incremental SSE output.
- `openrouter-spring-ai`: HTTP API, wire DTOs, model options, mappers, and chat/embedding/image models.
- `openrouter-spring-ai-autoconfigure`: bean wiring, model selection, runtime hints, and `spring.ai.openrouter.*` property binding.
- `openrouter-spring-ai-starter`: the application-facing dependency bundle.
- `openrouter-spring-ai-samples`: the Garage executable demo and live capability harness.

## Architecture

- Keep HTTP client access in `api`; that package must not depend on Spring AI or `chat`.
- Use mapper packages as the wire-to-Spring-AI boundary. Follow the existing architecture tests, including their explicit model-class exceptions; do not widen exceptions just to pass a check.
- Keep auto-configuration concerned with wiring, not wire DTOs or request translation. Preserve acyclic core packages.
- Models advertise tools and return tool calls. `ChatClient` with `ToolCallingAdvisor` owns execution; do not execute callbacks inside a model.
- Preserve opaque reasoning metadata and its replay order across conversation and tool continuations.

## Cover the affected paths

For each behavior change, identify which paths apply and verify them:

- Chat Completions and Responses; synchronous calls and streaming.
- Java options, defaults, per-request overrides, and Boot property binding.
- Chat, embeddings, and images when a shared API or mapper changes.
- Default bean creation, user-supplied bean backoff, and model selection when wiring changes.
- Success, invalid input, provider errors, and stream completion/cancellation as relevant.
- Reasoning replay, fragmented tool arguments, generated media, and usage metadata when affected.

If a mode does not support an option, preserve or add explicit validation rather than silently ignoring it. Record unsupported or untested paths accurately.

## Builds and dependencies

- The root `pom.xml` is authoritative for shared versions and BOM baselines. Gradle reads those properties; do not create competing version constants.
- Keep Maven and Gradle module dependencies, scopes, and packaging aligned when changing either.
- Preserve the configured Java release baseline (currently 17); a newer local JDK is not permission to use newer language or runtime APIs.
- Use installed `mvn` and `gradle`; this checkout has no Maven or Gradle wrapper. Check the CI workflow for the expected Gradle version and JDK matrix.
- Keep the three published library artifacts thin. The samples application is the executable artifact.
- Use the existing Jackson 3 databind stack. Existing `com.fasterxml.jackson.annotation` imports are valid; do not mechanically rewrite those annotations.

## Verification

- Start with focused behavioral tests. Add a regression test for a bug fix; do not add tests that merely repeat implementation details.
- Example core test: `mvn -B -pl openrouter-spring-ai -Dtest=OpenRouterChatModelStreamingTests test`.
- For auto-configuration and its upstream module: `mvn -B -pl openrouter-spring-ai-autoconfigure -am test`.
- Gradle focused equivalent: `gradle --no-daemon :openrouter-spring-ai:test --tests '*OpenRouterChatModelStreamingTests'`.
- For dependency, build, or broad cross-module changes, run `mvn -B -DskipTests package`, `mvn -B verify`, `gradle --no-daemon assemble`, and `gradle --no-daemon check` as applicable to the affected builds.
- Run affected-module `verify` (Maven) or `check` (Gradle) when quality rules or substantial Java changes warrant it. CI runs these quality gates, plus explicit POM/properties formatting and Java style checks. Checkstyle is enabled only on JDK 21+ and samples have exclusions.
- For publication or packaging changes, inspect `.github/actions/check-release/action.yml` and `release-smoke-tests`; preserve the release packaging and consumer checks in CI.
- For runtime-hint or native compatibility changes, inspect the samples native build and run `gradle --no-daemon :openrouter-spring-ai-samples:nativeCompile` with the matching GraalVM when available.
- Report commands, outcomes, and any unverified paths. A configured CI job is not evidence that the current head passed.

## Test data and live verification

- Prefer synthetic wire fixtures and existing test doubles. Use `StepVerifier` for reactive behavior and `ApplicationContextRunner` for Boot wiring.
- Include meaningful edge cases: fragmented events, multiple choices, missing optional fields, provider errors, and unknown additive fields when relevant.
- Wire DTO records must tolerate unknown JSON fields, as required by the architecture tests.
- Do not copy private prompts, account responses, logs, or credentials into fixtures or committed evidence.
- Run the Garage live harness only when live API use is authorized. Confirm its output location and keep generated reports and service records outside the repository.
- Never print an API key or place it in a command argument, documentation, or a commit. Use the existing environment-based configuration.
- Use synthetic regression cases to reproduce a protocol defect; preserve no private live payload in the repository.

## Code style

- Follow nearby code and the Spring Java Format configuration enforced by Maven. Do not reformat unrelated files.
- The current `.editorconfig` Java indentation differs from `.springjavaformatconfig`; follow the build formatter and flag the mismatch rather than changing both during unrelated work.
- Prefer typed options and DTOs for known protocol fields; preserve opaque provider metadata where the existing contract requires it.
- Keep mutable stream assembly state isolated per subscription and choice. Avoid blocking calls and unnecessary whole-stream buffering in reactive paths.
- Reuse existing mappers and validation helpers when semantics match. Explain why an exception exists; avoid comments that narrate obvious code.

## Documentation and artifacts

- Update the relevant README guidance when installation, public options, request modes, or observable behavior changes.
- Explain how a consumer uses a capability and its limitations. Keep local implementation reasoning near the code; create a separate architecture document only for a durable decision spanning components.
- Rewrite stale guidance instead of appending a competing explanation. Do not copy field catalogs from source into documentation.
- Keep plans, review HTML, screenshots, raw logs, and agent scratch files outside the repository. Do not stage them with product changes.
- Use synthetic examples in publishable documentation. Private activity and session-derived information must not be published without explicit authorization for the exact information.

## Delivery and review

- Keep changes focused on the requested outcome; preserve unrelated working-tree changes.
- Explain what changed, why, what was verified, and any remaining limitation.
- Before a commit, inspect the staged paths and complete staged diff. Before an external write, inspect the complete final payload for credentials and private or session-derived information.
- Follow the developer's authorization and workflow for creating, updating, and merging PRs.
- When a PR is in scope, inspect mergeability, required approvals, checks, and feedback for its current head. Fix pipeline failures before review suggestions.
- Validate each review comment against the problem being solved. Address valid findings; explain a wrong or misaligned finding only when a reply is needed and authorized. Bot reactions are not required human approvals.
