# Contributing to OpenRouter Spring AI

This page covers building, testing and checking the library. For how applications use it, see the [documentation](docs/README.md).

## Requirements

- JDK 17 or later. [Build quality checks](#build-quality-checks) lists the checks that need JDK 21 or 25.
- Maven, and Gradle 9.7.1 as used in CI. The repository has no Maven or Gradle wrapper.
- Docker, only for the local Qodana check.

Run `mvn -B verify` or `gradle --no-daemon check` before you open a pull request.

## Maven and Gradle builds

Maven and Gradle build the same three thin library JARs. Versions and BOM baselines come from the root POM, which Gradle reads. CI builds and tests both on Java 17, 21 and 25. The samples application is separate; it is the executable artifact.

## Test the Garage without an API key

Run the Garage integration tests. They need no key and no network access.

```sh
mvn -B -pl openrouter-spring-ai-samples -am -Dtest='*IntegrationTests' -Dsurefire.failIfNoSpecifiedTests=false test
gradle --no-daemon :openrouter-spring-ai-samples:test --tests '*IntegrationTests'
```

Drop the filters to run every samples test. `mvn verify` and `gradle check` run them all.

- Unit tests use test doubles. They check request validation, per-run settings, scene logic, evidence and reports.
- Integration tests, in the `garage.integration` package, run the Garage through its HTTP API against a local mock of the OpenRouter API. Only the base URL differs from a live run, so the real HTTP clients, SSE decoding, mappers, tool loops and evidence checks all run.

The integration tests check that:

- A `full` run passes every scene in both request modes, with the right request shape for each modality and the attribution headers.
- Each sad path fails only the targeted scene, with the intended reason code. Examples: a model that skips its tools, a reused tool-call ID, missing usage evidence, a stream that ends early, schema-violating output and an embedding count mismatch.
- Edge cases still pass: parallel tool calls, SSE keepalive comments, a rate limit that clears on retry, and reasoning in the Gemini, Anthropic and OpenAI formats reaching the next tool round unchanged in both request modes.
- Sweeps, one-run-at-a-time scheduling and per-run settings work. Evidence and reports never contain the topic, prompts, model replies or the API key.
- Library paths that no Garage scene sends work: PDF, audio and video input, streamed audio output, provider errors and slow providers.

The mock is [WireMock](https://wiremock.org), serving the corpus in [`openrouter-spring-ai-samples/src/test/resources/openrouter-mock`](openrouter-spring-ai-samples/src/test/resources/openrouter-mock). The `mappings` directory holds the default happy-path stubs. A test loads one directory from `scenarios` to override them for a single sad or edge case. Stubs pick a reply from the request body: the scene in `metadata.sceneId`, whether the request streams, and which tool results it carries. A request that no stub matches fails the test.

The corpus is synthetic. It mirrors OpenRouter's response format and delivery: keepalive whitespace and comments, streams split at arbitrary byte boundaries, Responses bodies that echo the request settings, and one JSON body for Gemini image streams. A mocked run shows that the library and the Garage handle these responses. It does not show that a model or provider behaves the same way.

## Build quality checks

`Verify` in the table means the CI jobs `Verify with Maven · Java N` and `Verify with Gradle · Java N`, which run on Java 17, 21 and 25.

| Gate                                 | Default build                                 | Command                                                                                                                                                     | Requires                | CI job                                                                                           |
| ------------------------------------ | --------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------- | ------------------------------------------------------------------------------------------------ |
| Spring Java Format                   | Maven only, in `validate`; skips samples      | `mvn -B validate`                                                                                                                                           | JDK 17+                 | `Package with Maven · Java N`                                                                    |
| Checkstyle                           | Both                                          | `mvn -B validate`, `gradle --no-daemon checkstyleMain checkstyleTest`                                                                                       | JDK 21+                 | `Package with Maven · Java 21/25`, `Assemble with Gradle · Java 21/25`, Verify on Java 21 and 25 |
| PMD and CPD                          | Both; CPD is Maven only and skips samples     | `mvn -B verify`, `gradle --no-daemon check`                                                                                                                 | JDK 17+                 | Verify                                                                                           |
| JaCoCo coverage                      | Both                                          | `mvn -B verify`, `gradle --no-daemon check`                                                                                                                 | JDK 17+                 | Verify                                                                                           |
| NullAway                             | No; opt in with `-Pnullaway`                  | `mvn -B -Pnullaway clean compile`, `gradle --no-daemon -Pnullaway clean compileJava`                                                                        | JDK 25+                 | `NullAway · Maven`, `NullAway · Gradle`                                                          |
| Qodana `WeakerAccess`                | No                                            | [Qodana declaration access](#qodana-declaration-access)                                                                                                     | Docker                  | `Qodana declaration access`                                                                      |
| SpotBugs correctness                 | No; opt in with `-Pcorrectness`               | `mvn -B -Pcorrectness -pl openrouter-spring-ai-autoconfigure -am -DskipTests verify`, `gradle --no-daemon spotbugsCorrectness`                              | No minimum (CI: JDK 25) | `SpotBugs correctness · Maven`, `SpotBugs correctness · Gradle`                                  |
| FindSecBugs                          | No; opt in with `-Psecurity`, Maven only      | `mvn -B -Psecurity -pl openrouter-spring-ai-autoconfigure -am -DskipTests verify`                                                                           | No minimum (CI: JDK 25) | `FindSecBugs security`                                                                           |
| Modernizer                           | Maven on JDK 25+, profile `modernizer-java25` | `mvn -B -Pmodernizer-java25 verify`                                                                                                                         | JDK 25+                 | `Verify with Maven · Java 25`                                                                    |
| Workflow validation                  | No                                            | `actionlint -color`                                                                                                                                         | actionlint, ShellCheck  | `Workflow validation`                                                                            |
| Gate integration tests               | No; opt in with `-Pgate-its`                  | `mvn -B -N -Pgate-its verify`                                                                                                                               | No minimum (CI: JDK 25) | `Verify with Maven · Java 25`                                                                    |
| Mutation testing (PIT)               | No; opt in with `-Pmutation`                  | `mvn -B -pl openrouter-spring-ai -am -Pmutation test-compile org.pitest:pitest-maven:mutationCoverage`, `gradle --no-daemon :openrouter-spring-ai:mutation` | No minimum (CI: JDK 25) | `Focused mutation gate`                                                                          |
| POM and properties format (Spotless) | No; opt in with `-Pspotless`                  | `mvn -B -Pspotless spotless:check`                                                                                                                          | JDK 17+                 | `Package with Maven · Java N`                                                                    |
| Markdown format (Prettier)           | No                                            | `npx prettier@3.9.6 --check README.md CONTRIBUTING.md CHANGELOG.md "docs/**/*.md"`                                                                          | Node                    | `Check Markdown formatting`                                                                      |

### Formatting

- Java: Maven checks Spring Java Format on every build. Fix a failure with `mvn -B spring-javaformat:apply`. Gradle has no formatter, so use the Maven command. The samples are not checked.
- POMs and `.properties` files under `src/main/resources` and `src/test/resources`: fix with `mvn -B -Pspotless spotless:apply`.
- The `.editorconfig` sets two-space Java indentation, but the build formatter uses tabs. Follow the build.

### Checkstyle and PMD

The library and samples compile with `--release 17`. Checkstyle runs on JDK 21+ only. Library sources use Spring formatting rules. The samples enforce only `EqualsHashCode`, `FallThrough`, `EmptyStatement` and `StringLiteralEquality`, through `config/checkstyle/checkstyle-samples.xml`.

Both builds use PMD 7.28.0 (`pmd.version` in the root POM) with three rulesets in `config/pmd`. `pmd-common.xml` covers production and test sources, samples included. `pmd-main.xml` excludes tests and samples. `pmd-test.xml` excludes main sources.

These rules block the build:

- `CognitiveComplexity`: library methods and constructors fail at 25 or more.
- `CyclomaticComplexity`: library methods fail at 15 or more, and classes at 80 or more.
- `PreserveStackTrace` and `UnnecessaryModifier`: apply to library production and all test sources.
- `JUnitJupiterTestShouldBePackagePrivate`: test classes and methods use package access where JUnit allows it.
- `NoAccessibilityOverride`: library production code must not call `setAccessible`, `trySetAccessible` or `makeAccessible`.

PMD does not check declaration access; [Qodana does](#qodana-declaration-access). An advisory `ExcessivePublicCount` report flags library classes with 45 or more public members and never blocks a build. Run it with `mvn -B test-compile pmd:pmd@pmd-review` or `gradle --no-daemon pmdPublicApiReview`.

### Coverage

`mvn -B verify` and `gradle --no-daemon check` enforce JaCoCo coverage for each library module: at least 90% line and 80% branch. The root POM defines both floors, and Gradle reads them from it. The parent aggregator, the starter and the samples have no percentage gate.

Reports are in each module's `target/site/jacoco` (Maven) or `build/reports/jacoco/test` (Gradle). CI uploads them when verification fails. Any Maven `-DskipTests` run and `gradle --no-daemon assemble` skip coverage checks.

### NullAway

The `nullaway` profile enforces production null contracts on JDK 25+, with NullAway errors and JSpecify generic checking. Tests are not checked. Output stays Java 17 bytecode, and normal builds still support JDK 17 and 21. Error Prone and NullAway are compiler-only dependencies and do not reach published runtime dependency graphs.

### Qodana declaration access

CI's `Qodana declaration access` job runs IntelliJ's `WeakerAccess` inspection ("Declaration access can be weaker") with the free `jetbrains/qodana-jvm-community` linter, pinned in `qodana.yaml`. It needs no Qodana Cloud token. The profile in `.qodana/weaker-access.yaml` enables only this inspection, on production sources of the core and autoconfiguration modules. Any finding fails the job and appears as an annotation on the pull request. The full report is the `qodana-report` artifact.

The inspection sees only callers inside this repository, so it ignores the supported API listed in [Supported API and nullability](docs/api.md). Keep the scopes in `.qodana/weaker-access.yaml` in sync when that list changes. To fix a finding, narrow the declaration. When a framework or reflection needs the wider access, annotate the declaration with `@SuppressWarnings("WeakerAccess")` and a comment that gives the reason. Run the check locally from the repository root:

```sh
docker run --rm -v "$PWD:/data/project" -v "$PWD/../qodana-results:/data/results" jetbrains/qodana-jvm-community:2026.2
```

### SpotBugs and FindSecBugs

The correctness gate selects four detectors: `NP_NULL_ON_SOME_PATH` and `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` (nullable dereferences), `OBL_UNSATISFIED_OBLIGATION` (resource cleanup) and `DC_DOUBLECHECK` (unsafe double-checked publication). The security gate (FindSecBugs) uses maximum effort, reports all confidence levels and fails on any SECURITY finding. Both fail on any analysis error. They analyze production classes of the core and autoconfiguration modules. Tests, samples and the starter are outside the scope. Add the starter to both configurations if it gains executable code.

Reports are in `target/spotbugs/<gate>` and `build/reports/spotbugs/correctness` of each analyzed module (Gradle runs only the correctness gate). CI's `FindSecBugs security` job on JDK 25 is the enforcement point for both Maven and Gradle contributors. You can combine the profiles as `-Psecurity,correctness`.

Put exceptions in `config/spotbugs/*-exclude.xml`, scoped to one detector and one method, with a comment that explains the contract. Package exclusions and generated baselines are not accepted.

### Modernizer

On JDK 25+, the `modernizer-java25` profile activates by itself and runs Modernizer with a Java 25 analysis target on production and test bytecode of every module. The `Verify with Maven · Java 25` job enforces it for both builds. The `--release 17` compile still protects Java 17 compatibility.

### Workflow validation

Install actionlint 1.7.12 and ShellCheck, then run `actionlint -color` from the repository root. The separate `Immutable action references` job fails on any action that is not pinned to a commit SHA.

### Gate integration tests

The library passes every gate, so a normal build cannot show that a gate is still switched on. `maven-invoker-plugin` runs the projects in `config/gate-its`. Each project inherits the real parent POM, breaks one gate on purpose, and must fail for that reason:

- `coverage-below-floor`: the JaCoCo line floor.
- `coverage-branch-below-floor`: the JaCoCo branch floor.
- `pmd-lost-cause`: `PreserveStackTrace`.
- `spotbugs-correctness`: all four correctness detectors.
- `findsecbugs-sql-injection`: `SQL_INJECTION_JDBC`.

A `verify.groovy` script in each project checks the rule, so a build that fails for another reason still fails the run. When you change a gate's configuration, add or update the matching project.

### Focused mutation testing

PIT is opt-in and runs from the repository root. It uses the `DEFAULTS` mutators and an 80% mutation threshold, and it fails on an empty mutation set. The `mutation.targets` property in the root POM lists the classes under test. Reports are in `openrouter-spring-ai/target/pit-reports` (Maven) and `openrouter-spring-ai/build/reports/pitest` (Gradle).

CI uses two workers on Java 25; allow about seven minutes and 3 GiB of JVM memory. Override the workers with `-Dmutation.threads=4` (Maven) or `-Pmutation.threads=4` (Gradle). Do not combine PIT workers with Maven `-T` or parallel JUnit execution. The `Focused mutation gate` job runs once per pull request, always with Maven, and keeps reports for 14 days. The workflow's manual trigger runs only this job, with two or four workers on Maven or Gradle.

When the gate fails:

1. Review the `SURVIVED` and `NO_COVERAGE` entries in the XML or HTML report.
2. Reproduce the affected behavior with a synthetic test, and assert the observable contract.
3. Document equivalent mutations. Do not lower the threshold or remove targets to make the gate pass.

No mutations are excluded. Not every survivor is equivalent, so inspect its observable effect before you add tests or propose a narrow exclusion. PIT counts a mutation timeout as a detection, so when you compare runs, compare which mutations timed out, not only the score.

## Releases

The Maven Central Release workflow takes a release version and a commit on `main`. With `upload=false`, it builds unsigned artifacts in a fresh Maven repository, then assembles a Central bundle and checks its contents. This rehearses packaging without uploading. With `upload=true`, Central validates the signed bundle, and you publish it manually in Central Portal.
