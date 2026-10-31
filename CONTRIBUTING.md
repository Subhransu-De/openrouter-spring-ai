# Contributing to OpenRouter Spring AI

This page covers building, testing and checking the library. For how applications use it, see the [documentation](docs/README.md).

## Requirements

- JDK 17 or later. [Build quality checks](#build-quality-checks) lists the checks that need JDK 21 or 25.
- Maven, and Gradle 9.7.1 as used in CI. The repository has no Maven or Gradle wrapper.
- Docker, only for the local Qodana check.

Run `mvn -B verify` or `gradle --no-daemon check` before you open a pull request.

## Maven and Gradle builds

Maven and Gradle build the same three thin library JARs. Versions and BOM baselines come from the root POM, which Gradle reads. CI builds and tests both on Java 17, 21 and 25. The samples application is separate; it is the executable artifact.

## Releases

The Maven Central Release workflow takes a release version and a commit on `main`. With `upload=false`, it builds unsigned artifacts in a fresh Maven repository, then assembles a Central bundle and checks its contents. This rehearses packaging without uploading. With `upload=true`, Central validates the signed bundle, and you publish it manually in Central Portal.

## Test the Garage without an API key

Run the Garage integration tests. They need no key and no network access.

```sh
mvn -B -pl openrouter-spring-ai-samples -am -Dtest='Garage*IntegrationTests' -Dsurefire.failIfNoSpecifiedTests=false test
gradle --no-daemon :openrouter-spring-ai-samples:test --tests '*Garage*IntegrationTests'
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

"Verify" means the CI jobs `Verify with Maven · Java N` and `Verify with Gradle · Java N`, which run on Java 17, 21 and 25. "Java 25 (CI)" means CI runs the check on 25 and the build does not enforce it.

| Gate                                 | In `mvn verify`                               | In `gradle check`            | JDK          | Local command                                                                                                                                               | CI job                                                          |
| ------------------------------------ | --------------------------------------------- | ---------------------------- | ------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------- |
| Spring Java Format                   | Yes, in `validate`; samples are skipped       | No                           | 17+          | `mvn -B validate`                                                                                                                                           | `Package with Maven · Java N`                                   |
| Checkstyle                           | Yes, in `validate`                            | Yes                          | 21+          | `mvn -B validate`, `gradle --no-daemon checkstyleMain checkstyleTest`                                                                                       | Verify, Java 21 and 25                                          |
| PMD and CPD                          | Yes; CPD skips samples                        | Yes, PMD only                | 17+          | `mvn -B verify`, `gradle --no-daemon check`                                                                                                                 | Verify                                                          |
| JaCoCo coverage                      | Yes                                           | Yes                          | 17+          | `mvn -B verify`, `gradle --no-daemon check`                                                                                                                 | Verify                                                          |
| NullAway                             | No; opt in with `-Pnullaway`                  | No; opt in with `-Pnullaway` | 25+          | `mvn -B -Pnullaway clean compile`, `gradle --no-daemon -Pnullaway clean compileJava`                                                                        | `NullAway · Maven`, `NullAway · Gradle`                         |
| Qodana `WeakerAccess`                | No                                            | No                           | Docker       | `docker run` command in [Qodana declaration access](#qodana-declaration-access)                                                                             | `Qodana declaration access`                                     |
| SpotBugs correctness                 | No; opt in with `-Pcorrectness`               | No; separate task            | Java 25 (CI) | `mvn -B -Pcorrectness -pl openrouter-spring-ai-autoconfigure -am -DskipTests verify`, `gradle --no-daemon spotbugsCorrectness`                              | `SpotBugs correctness · Maven`, `SpotBugs correctness · Gradle` |
| FindSecBugs                          | No; opt in with `-Psecurity`                  | No; Maven only               | Java 25 (CI) | `mvn -B -Psecurity -pl openrouter-spring-ai-autoconfigure -am -DskipTests verify`                                                                           | `FindSecBugs security`                                          |
| Modernizer                           | Yes, on JDK 25+ (profile `modernizer-java25`) | No                           | 25+          | `mvn -B -Pmodernizer-java25 verify`                                                                                                                         | `Verify with Maven · Java 25`                                   |
| Workflow validation                  | No                                            | No                           | n/a          | `actionlint -color`                                                                                                                                         | `Workflow validation`                                           |
| Gate integration tests               | No; opt in with `-Pgate-its`                  | No                           | Java 25 (CI) | `mvn -B -N -Pgate-its verify`                                                                                                                               | `Verify with Maven · Java 25`                                   |
| Mutation testing (PIT)               | No; opt in with `-Pmutation`                  | No; separate task            | Java 25 (CI) | `mvn -B -pl openrouter-spring-ai -am -Pmutation test-compile org.pitest:pitest-maven:mutationCoverage`, `gradle --no-daemon :openrouter-spring-ai:mutation` | `Focused mutation gate`                                         |
| POM and properties format (Spotless) | No; opt in with `-Pspotless`                  | No                           | 17+          | `mvn -B -Pspotless spotless:check`                                                                                                                          | `Package with Maven · Java N`                                   |
| Markdown format (Prettier)           | No                                            | No                           | Node         | `npx prettier@3.9.6 --check README.md CONTRIBUTING.md CHANGELOG.md "docs/**/*.md"`                                                                          | `Check Markdown formatting`                                     |

### Formatting

- Java: Maven checks Spring Java Format on every build. Fix a failure with `mvn -B spring-javaformat:apply`. Gradle has no formatter, so use the Maven command. The samples are not checked.
- POMs and `.properties` files under `src/main/resources` and `src/test/resources`: fix with `mvn -B -Pspotless spotless:apply`. CI runs the check form.
- The `.editorconfig` sets two-space Java indentation, but the build formatter uses tabs. Trust the build, not the editor setting.

### Checkstyle and PMD

The library and samples compile with `--release 17`. Checkstyle runs on JDK 21+ only. Library sources use Spring formatting rules. The samples keep their own layout and enforce only `EqualsHashCode`, `FallThrough`, `EmptyStatement` and `StringLiteralEquality`, through `config/checkstyle/checkstyle-samples.xml`.

Both builds use PMD 7.28.0 (`pmd.version` in the root POM) with `config/pmd/pmd-common.xml` on production and test sources, samples included. Maven analyzes only the main and test source directories. Gradle skips generated Spring AOT source sets. Gradle has no CPD task, and CPD is off for samples. Five sample classes suppress only `AvoidDuplicateLiterals`.

The shared rulesets scope these blocking rules by source path, so both builds enforce the same policy:

- `CognitiveComplexity`: library methods and constructors fail at 25 or more. PMD 7.28.0 names this property `reportLevel`, not `methodReportLevel`.
- `CyclomaticComplexity`: library methods fail at 15 or more, and classes at 80 or more.
- `PreserveStackTrace` and `UnnecessaryModifier`: apply to library production and all test sources.
- `JUnitJupiterTestShouldBePackagePrivate`: test classes and methods use package access where JUnit allows it.
- `NoAccessibilityOverride`: library production code must not call `setAccessible`, `trySetAccessible` or `makeAccessible`.

Complexity rules do not apply to test or sample sources. Two methods carry a narrow suppression: `Retries.invoke` (`PreserveStackTrace`) rethrows the original cause instead of Spring's retry wrapper, and `OpenRouterErrorClassifier.category(int)` (`CyclomaticComplexity`) keeps a flat status lookup. PMD does not check declaration access; Qodana does.

An advisory `ExcessivePublicCount` report flags library classes with 45 or more public members. It never blocks `verify` or `check`. Run it with `mvn -B test-compile pmd:pmd@pmd-review` or `gradle --no-daemon pmdPublicApiReview`. Maven writes `target/pmd-review/` in each module. Gradle writes `build/reports/pmd/pmdPublicApiReview.xml`.

### Coverage

`mvn -B verify` and `gradle --no-daemon check` enforce JaCoCo coverage separately for each library module: at least 90% line and 80% branch. The root POM defines both floors, and Gradle reads them from it. Reports and gates cover all production classes. The parent aggregator and the samples have no percentage gate. The starter has only package metadata, so it has no percentage yet. When it gains production classes, the same policy and the missing-execution-data check apply to it.

HTML and XML reports are in each module's `target/site/jacoco` (Maven) or `build/reports/jacoco/test` (Gradle). CI uploads them with the synthetic test results when verification fails, and keeps them for seven days. `mvn -B -DskipTests package` and `gradle --no-daemon assemble` skip coverage checks. So does any Maven `-DskipTests` verification, such as the FindSecBugs command above.

Coverage supplements the behavioral tests for fragment ordering, cancellation, terminal errors, reasoning replay and tool aggregation.

### NullAway

The `nullaway` profile enforces production null contracts on JDK 25+, with NullAway errors and JSpecify generic checking. Tests are not checked, so invalid-input fixtures can exercise runtime validation. Output stays Java 17 bytecode, and normal builds still support JDK 17 and 21. Error Prone and NullAway are compiler-only dependencies. They are absent from published runtime dependency graphs.

### Qodana declaration access

CI's `Qodana declaration access` job runs IntelliJ's `WeakerAccess` inspection ("Declaration access can be weaker") with the free `jetbrains/qodana-jvm-community` linter, pinned in `qodana.yaml`. It needs no Qodana Cloud token. The profile in `.qodana/weaker-access.yaml` enables only this inspection. The job checks production sources of the core and autoconfiguration modules. Any finding fails the job and appears as an annotation on the pull request, and the full report is uploaded as the `qodana-report` artifact.

The inspection sees only callers inside this repository, so it ignores the supported API listed in [Supported API and nullability](docs/api.md). Keep the scopes in `.qodana/weaker-access.yaml` in sync when that list changes. To fix a finding, narrow the declaration. When a framework or reflection needs the wider access, annotate the declaration with `@SuppressWarnings("WeakerAccess")` and a comment that gives the reason. Run the check locally from the repository root:

```sh
docker run --rm -v "$PWD:/data/project" -v "$PWD/../qodana-results:/data/results" jetbrains/qodana-jvm-community:2026.2
```

### SpotBugs correctness

The gate selects four detectors: `NP_NULL_ON_SOME_PATH` and `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` (nullable dereferences), `OBL_UNSATISFIED_OBLIGATION` (resource cleanup) and `DC_DOUBLECHECK` (unsafe double-checked publication). Any finding, analysis error, missing dependency class or empty scope fails it. Both builds analyze production classes of the core and autoconfiguration modules. Tests, samples and the starter are outside the scope. Add the starter to both configurations if it gains executable code.

Maven writes `target/spotbugs-correctness.xml` and `target/spotbugs/correctness/spotbugs.html`. Gradle writes `build/reports/spotbugs/correctness/spotbugs.xml` and `spotbugs.html` in each analyzed module. The root POM pins the engine version. Analysis runs during `package`, and the non-forking SpotBugs `verify` goal enforces findings, which avoids the `check` goal's fork losing execution-specific configuration.

Put exceptions in the policy's `config/spotbugs/*-exclude.xml`, scoped to one detector and one method, with a comment that explains the contract. Package exclusions and generated baselines are not accepted. NullAway checks null contracts separately and does not inherit these exclusions.

### FindSecBugs

The gate analyzes compiled core and autoconfiguration production classes. Tests, samples and the starter are excluded. It uses maximum effort, reports all confidence levels (`Low` threshold) and fails on any SECURITY finding or analysis failure. Maven writes `target/findsecbugs.xml` and `target/spotbugs/security/spotbugs.html` in each analyzed module.

The security filter and detector plugin exist only inside the `security` profile. SpotBugs without `-Psecurity` stays unfiltered and writes its own `target/spotbugsXml.xml`. You can combine the profiles as `-Psecurity,correctness`; their filters, reports and skip properties are separate. Two method-specific exclusions in `config/spotbugs/security-exclude.xml` cover an ASCII-validated URI scheme comparison and tool-name log sanitization, which the detector does not recognize.

CI's `FindSecBugs security` job on JDK 25 is the enforcement point for both Maven and Gradle contributors. It overlaps CodeQL's `security-and-quality` suite but gives a local bytecode gate. It is not yet a required branch check.

### Modernizer

On JDK 25+, the `modernizer-java25` profile activates by itself and runs Modernizer with a Java 25 analysis target on production and test bytecode of every module. The `Verify with Maven · Java 25` job is the enforcement point for both builds. Modernizer checks its known API catalog, not every newer Java feature. The `--release 17` compile still protects Java 17 compatibility.

### Workflow validation

Install actionlint 1.7.12 and ShellCheck, then run `actionlint -color` from the repository root. The `Workflow validation` job checks all tracked workflows on every pull request, forks included, and on pushes to `main`. The `Immutable action references` job fails on any action that is not pinned to a commit SHA.

### Gate integration tests

The library passes every gate, so a normal build cannot show that a gate is still switched on. `mvn -B -N -Pgate-its verify` runs `maven-invoker-plugin` over the projects in `config/gate-its`. Each project inherits the real parent POM, breaks one gate on purpose, and must fail for that reason:

- `coverage-below-floor`: the JaCoCo line floor.
- `coverage-branch-below-floor`: the JaCoCo branch floor.
- `pmd-lost-cause`: `PreserveStackTrace`.
- `spotbugs-correctness`: all four correctness detectors.
- `findsecbugs-sql-injection`: `SQL_INJECTION_JDBC`.

A `verify.groovy` script in each project checks the rule, so a build that fails for another reason still fails the run. When you change a gate's configuration, add or update the matching project.

### Focused mutation testing

PIT is opt-in and runs from the repository root. It uses the `DEFAULTS` mutators and an 80% mutation threshold, and it fails on an empty mutation set. Ordinary builds do not run it. Its tooling is absent from published application classpaths. Reports are in `openrouter-spring-ai/target/pit-reports` (Maven) and `openrouter-spring-ai/build/reports/pitest` (Gradle).

The `mutation.targets` property in the root POM lists the classes under test, including nested classes: retry-header parsing and HTTP error classification, reasoning fragment merging, streaming tool-call aggregation, and image response validation. All core tests stay available for coverage selection. Samples and live-provider calls are outside the gate.

Two workers are the default. Override them with `-Dmutation.threads=4` (Maven) or `-Pmutation.threads=4` (Gradle). Do not combine PIT workers with Maven `-T` or parallel JUnit execution.

CI runs the `Focused mutation gate` job once per pull request, always with Maven, on Temurin 25 with a 20-minute timeout. It keeps reports for 14 days, including failed runs. The workflow's manual trigger runs only this job, with two or four workers on Maven or Gradle. Job logs show elapsed time and peak combined JVM memory. A benchmark at commit `fd7a3df` found two workers ([6m 19s, 2.57 GiB](https://github.com/Subhransu-De/openrouter-spring-ai/actions/runs/35981045314)) nearly as fast as four ([5m 59s, 2.90 GiB](https://github.com/Subhransu-De/openrouter-spring-ai/actions/runs/35981060948)) with the same results, so two are the default. Plan for about seven minutes and 3 GiB of JVM memory.

When the gate fails:

1. Review the `SURVIVED` and `NO_COVERAGE` entries in the XML or HTML report.
2. Reproduce the affected behavior with a synthetic test, and assert the observable contract.
3. Document equivalent mutations. Do not lower the threshold or remove targets to make the gate pass.

No mutations are excluded. Not every survivor is equivalent, so inspect its observable effect before you add tests or propose a narrow exclusion. PIT counts a mutation timeout as a detection. When you compare runs, compare which mutations timed out, not only the score.
