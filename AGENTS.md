# Project

A community library that implements OpenRouter as a native provider for Spring AI.

## Project priorities

- Prefer the smallest implementation that handles the required behavior.
- Preserve Spring AI model contracts while exposing OpenRouter APIs.
- Follow the [Spring AI](https://docs.spring.io/spring-ai/reference/), [Spring Boot](https://docs.spring.io/spring-boot/) and [Spring Framework](https://docs.spring.io/spring-framework/reference/) reference docs when you write code, and follow the standards they set.

## Note from the developer

I like ambitious ideas, simple systems, and software that feels obvious. Do not preserve complexity just because it already exists. Do not introduce machinery because it looks architecturally impressive. Understand the real constraint, then fight for the smallest model that makes the correct behavior unsurprising. Channel both "measure twice, cut once" and "YAGNI". Fight scope creep. Try to honor the developer's intent in both a minimal and realistic fashion.

## Releases

Release only when the developer asks for a version. Pair minor and major upgrades of `spring-ai.version` and `spring-boot.version` in the same PR (compatible single-project patch bumps can land on their own). Never ship a final release against a Spring AI or Spring Boot pre-release: move `main` to Spring AI's `RC1` and release `-RC1`, then ship the final version after Spring AI GA. Only create an `N.N.x` maintenance branch from a previous release tag when that line needs a fix, and run the release workflow from that branch. A maintenance branch cut from a tag before `0.3.0` must cherry-pick the maintenance-branch `ci.yml` and `release.yml` changes in its first PR, and the GitHub `release` environment must allow `*.*.x` branches.

For version `X.Y.Z`:

1. Open a PR on the release branch (`main` or `N.N.x`) that names `X.Y.Z` in the `CHANGELOG.md` heading with the release date, in the install snippets of `README.md` and `docs/`, and in the compatibility table of `docs/getting-started.md`.
2. When that PR is merged and CI on the release branch is green for its commit, run the `Maven Central Release` workflow on that branch with the version, the full commit SHA and `upload` set to `true`. The developer publishes the validated deployment in Central Portal.
3. Push the tag `vX.Y.Z` on that commit and create a draft GitHub release from the changelog section (mark pre-release for `-RC*`). Publish the draft when Maven Central serves the version.
4. For a release from `main`, open a PR on `main` that sets the root `pom.xml` revision to the next minor `-SNAPSHOT`, adds an empty `## Unreleased` section to `CHANGELOG.md`, and records the released commit in the compatibility table. For a release from `N.N.x`, the follow-up PR on `main` only adds that release's changelog section and compatibility row.
