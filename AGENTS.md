# Project

A community library that implements OpenRouter as a native provider for Spring AI.

## Project priorities

- Prefer the smallest implementation that handles the required behavior.
- Preserve Spring AI model contracts while exposing OpenRouter APIs.
- Follow the [Spring AI](https://docs.spring.io/spring-ai/reference/), [Spring Boot](https://docs.spring.io/spring-boot/) and [Spring Framework](https://docs.spring.io/spring-framework/reference/) reference docs when you write code, and follow the standards they set.

## Note from the developer

I like ambitious ideas, simple systems, and software that feels obvious. Do not preserve complexity just because it already exists. Do not introduce machinery because it looks architecturally impressive. Understand the real constraint, then fight for the smallest model that makes the correct behavior unsurprising. Channel both "measure twice, cut once" and "YAGNI". Fight scope creep. Try to honor the developer's intent in both a minimal and realistic fashion.

## Releases

Release only when the developer asks for a version. For version `X.Y.Z`:

1. Open a PR that names `X.Y.Z` in the `CHANGELOG.md` heading with the release date, in the install snippets of `README.md` and `docs/`, and in the compatibility table of `docs/getting-started.md`.
2. When that PR is merged and CI on `main` is green for its commit, run the `Maven Central Release` workflow with the version and the full commit SHA. The developer publishes the validated deployment in Central Portal.
3. Push the tag `vX.Y.Z` on that commit and create a draft GitHub release from the changelog section. Publish the draft when Maven Central serves the version.
4. Open a PR that sets the root `pom.xml` revision to the next minor `-SNAPSHOT`, adds an empty `## Unreleased` section to `CHANGELOG.md`, and records the released commit in the compatibility table.
