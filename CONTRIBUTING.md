# Contributing to OpenRouter Spring AI

This page covers building, testing and checking the library. For how applications use it, see the [documentation](docs/README.md).

## Requirements

- JDK 17 or later. Some checks need JDK 21 (Checkstyle) or JDK 25 (NullAway, Modernizer, PIT, SpotBugs and FindSecBugs as run in CI).
- Maven, and Gradle 9.7.1 as used in CI. The repository has no Maven or Gradle wrapper.
- Docker, only for the local Qodana check.

Run `mvn -B verify` or `gradle --no-daemon check` before opening a pull request. Documentation changes must pass `npx prettier@3.9.6 --check README.md CONTRIBUTING.md CHANGELOG.md "docs/**/*.md"`.

## Maven and Gradle builds

Maven and Gradle build the same three published thin library JARs. Versions and BOM
baselines come from the root Maven POM, and CI builds and tests both build systems across the
supported Java versions. The executable samples application is intentionally separate.

## Releases

The Maven Central Release workflow accepts a release version and a commit on `main`.
With `upload=false`, it builds unsigned artifacts in a fresh Maven repository, then
assembles a Central bundle and checks its contents. This rehearses packaging without
uploading; Central validates the signed bundle when `upload=true`, before manual
publication in Central Portal.

## Test the Garage without an API key

The samples tests come in two kinds, and neither needs a key or network access. Unit tests check the Garage's own code with test doubles: request validation, per-run settings, scene logic, evidence, and reports. Integration tests, in the `garage.integration` package, run the Garage through its HTTP API against a local mock of the OpenRouter API. Only the base URL differs from a live run: the real HTTP clients, SSE decoding, mappers, tool loops, and evidence checks all run. Both kinds run in `mvn verify` and `gradle check`.

The integration tests cover:

- A `full` run that must pass every scene in both request modes, with the request shape each modality sends and the attribution headers.
- Sad paths that must fail only the targeted scene for the intended reason. Examples include a model that answers without calling its tools, malformed or unknown tool calls, a reused tool-call ID, missing usage evidence, an unavailable specialist, a fallback that never happens, schema-violating structured output, a stream that ends early or fails, a reply cut off at its token limit, a request no OpenRouter endpoint can serve, a tool call with a blank name, undecodable images, and an embedding count mismatch. Each sad path also checks the failed scene's reason code.
- Edge cases that must still pass, such as parallel tool calls, SSE keepalive comments, a rate limit that clears on retry, SVG images, an image stream answered with a single JSON body, and an image stream of partial images. Reasoning in the Gemini, Anthropic, and OpenAI formats must reach the next tool round with its encrypted data and signatures unchanged, in both request modes.
- Sweeps, one-run-at-a-time scheduling, per-run settings on the wire, and that evidence and reports never contain the topic, prompts, model replies, or the API key.
- Library surfaces the Garage scenes do not send: PDF, audio, and video input in every supported format, streamed audio output, provider errors, a provider slower than the connection timeout, and a slow reply that whitespace keepalives keep within it.

The mock is [WireMock](https://wiremock.org), serving the corpus in [`openrouter-spring-ai-samples/src/test/resources/openrouter-mock`](openrouter-spring-ai-samples/src/test/resources/openrouter-mock). Default stubs in `mappings` answer the happy paths; a test loads one directory from `scenarios` to override them for a single sad or edge case. Stubs select a reply from the request body: the scene in `metadata.sceneId`, whether the request streams, and which tool results it already carries. A request that no stub matches fails the test. The corpus is synthetic; its structure mirrors OpenRouter's response format, including field names, usage details, stream event order, and error bodies. It also mirrors how OpenRouter delivers responses: successful JSON replies start with whitespace keepalive lines, streams start with keepalive comments and arrive split at arbitrary byte boundaries, Responses bodies echo the request settings, and Gemini image models answer a streaming image request with one JSON body. A mocked run shows that the library and the Garage handle these responses; it does not show that a model or provider behaves the same way.

## Build quality checks

### Focused mutation testing

On JDK 25, run either opt-in command from the repository root:

```sh
mvn -B -pl openrouter-spring-ai -am -Pmutation test-compile org.pitest:pitest-maven:mutationCoverage
gradle --no-daemon :openrouter-spring-ai:mutation
```

Both use the root POM's PIT and JUnit adapter versions, `DEFAULTS` mutators,
an 80% mutation threshold, and failure on an empty mutation set. Ordinary builds
do not run PIT, and its tooling dependencies are absent from published application
classpaths. HTML and XML reports are in `openrouter-spring-ai/target/pit-reports`
for Maven and `openrouter-spring-ai/build/reports/pitest` for Gradle.

The shared `mutation.targets` list includes nested classes and covers:

- `OpenRouterRetryAfter` and `OpenRouterHttpExceptionFactory`, which now owns
  retry-header parsing and HTTP error classification. PIT filters compiler-generated
  record methods, so the record alone would not exercise parsing.
- `ReasoningDetailsMerger`, for ordered fragment merging and opaque metadata retention.
- `OpenRouterStreamingToolCallAggregator`, for fragmented arguments, limits,
  cancellation, and per-choice state.
- `OpenRouterImageResponseValidator`, for malformed image payloads and provider-error
  precedence, exercised by synthetic synchronous and streaming-fallback contracts.

All core tests remain available for coverage selection. Samples and live-provider
calls are outside this gate. Two workers are the default. Override them with
`-Dmutation.threads=4` in Maven or `-Pmutation.threads=4` in Gradle. Use the corresponding `mutation.targets`
property for a focused investigation. Do not combine PIT workers with Maven `-T`
or parallel JUnit execution.

CI runs mutation analysis once per pull request on Temurin 25 with a 20-minute
timeout and retains generated reports for 14 days, including failed runs.
The workflow's manual trigger runs only this job and accepts two or four workers
with either Maven or Gradle. Pull requests always use Maven.
For a worker comparison, dispatch it twice against the same commit with warm
dependency caches. Job logs include elapsed time and peak combined JVM resident
memory sampled once per second; compare mutation outcomes and timeouts as well
as runtime before changing the default worker count.
Review `SURVIVED` and `NO_COVERAGE` entries in the XML or HTML report, reproduce
the affected behavior with a synthetic test, and add an assertion for the observable
contract. Keep equivalent mutations documented; do not lower the threshold or remove
targets to make the gate pass. `STRONGER` is reserved for a later scheduled expansion.

The worker comparison at commit `fd7a3df` used Ubuntu 24.04, Temurin 25.0.4,
the same mutation scope, and matching dependency-cache misses:

| Workers                                                                            | Elapsed | Peak combined JVM RSS | Killed | Timed out | Survived / uncovered |
| ---------------------------------------------------------------------------------- | ------- | --------------------- | ------ | --------- | -------------------- |
| [2](https://github.com/Subhransu-De/openrouter-spring-ai/actions/runs/35981045314) | 6m 19s  | 2.57 GiB              | 211    | 24        | 34 / 4               |
| [4](https://github.com/Subhransu-De/openrouter-spring-ai/actions/runs/35981060948) | 5m 59s  | 2.90 GiB              | 212    | 23        | 34 / 4               |

Both detected the same mutations; one changed from timeout to assertion failure.
Four workers saved only 20 seconds and used 13% more memory, so two are the default.
Allow roughly seven minutes and 3 GiB of JVM memory for this measured scope, plus
runner and build-tool overhead. The 20-minute job timeout leaves room for setup,
downloads, and runner variation. PIT counts mutation timeouts as detections;
compare their identities when reviewing later runs rather than relying on the score alone.

No mutations are excluded. Remaining survivors include equivalent date guards
whose parser fallback still rejects the input, a redundant reasoning-object guard,
and per-choice limits also enforced by the shared budget. Uncovered single-byte
writes and overflow protection, stale-timer races, and terminal-state cleanup
remain coverage limitations. Do not treat every survivor as equivalent; inspect
its observable effect before adding tests or proposing a narrow exclusion.

### Workflow validation

For GitHub Actions changes, install actionlint 1.7.12 and ShellCheck, then run
`actionlint -color` from the repository root. CI's `Workflow validation` job checks
all tracked workflows on every pull request (including forks) and main push,
with ShellCheck enabled for embedded shell scripts. Synthetic invalid input also
verifies dependency validation and file/line shell diagnostics.

### Checkstyle and PMD

The library and samples compile with `--release 17`. Run `mvn -B verify` and
`gradle --no-daemon check` for tests and static analysis. Checkstyle runs on JDK 21+
only. Library sources use Spring formatting; samples retain their existing layout
and enforce `EqualsHashCode`, `FallThrough`, `EmptyStatement`, and
`StringLiteralEquality` through `config/checkstyle/checkstyle-samples.xml`.
Both builds use the POM's `pmd.version` and apply `config/pmd/pmd-common.xml` to
maintained production and test sources, including samples. This retains the existing
resource, empty-catch, fall-through, crypto-key, unused-member, and `ImmutableField`
checks. Maven analyzes only the main and test source directories; Gradle excludes
generated Spring AOT source sets. Three sample classes suppress only
duplicate literals to keep registry rows and test inputs explicit. Sample CPD remains deferred because scenario and
fixture duplication is intentional; Gradle has no CPD task. Sample formatter and
Enforcer exclusions are unchanged.

The additional blocking PMD rules are scoped by source path in the shared rulesets,
so Maven's combined analysis and Gradle's separate main/test tasks enforce the same policy:

| Ruleset                        | Canonical rule reference                                                 | Policy                                                                                  |
| ------------------------------ | ------------------------------------------------------------------------ | --------------------------------------------------------------------------------------- |
| `pmd-main.xml`                 | `category/java/design.xml/CognitiveComplexity`                           | Library production methods and constructors fail at 25 or more, using `reportLevel=25`. |
| `pmd-main.xml`                 | `category/java/design.xml/CyclomaticComplexity`                          | Library production methods fail at 15 or more; class totals fail at 80 or more.         |
| `pmd-main.xml`, `pmd-test.xml` | `category/java/bestpractices.xml/PreserveStackTrace`                     | Library production and all test sources retain exception causes.                        |
| `pmd-main.xml`, `pmd-test.xml` | `category/java/codestyle.xml/UnnecessaryModifier`                        | Library production and all test sources omit redundant modifiers.                       |
| `pmd-test.xml`                 | `category/java/bestpractices.xml/JUnitJupiterTestShouldBePackagePrivate` | Test classes and methods use package access where JUnit permits it.                     |
| `pmd-review.xml`               | `category/java/design.xml/ExcessivePublicCount`                          | Advisory library production report with `minimum=45`; never blocks `verify` or `check`. |

PMD 7.27.0 calls the cognitive property `reportLevel`, not `methodReportLevel`.
The proposed thresholds were checked against the baseline and synthetic boundary
fixtures. Production complexity does not apply to test or sample sources.
Two method-specific exceptions retain existing behavior: `Retries.invoke` unwraps
Spring's retry wrapper and rethrows its original cause, and
`OpenRouterErrorClassifier.category(int)` keeps the flat HTTP status lookup together.
The exceptions name only `PreserveStackTrace` and `CyclomaticComplexity`, respectively.
Qodana's `WeakerAccess` check below owns declaration access; PMD does not infer
safe API access changes.

Run the separate advisory report with `mvn -B test-compile pmd:pmd@pmd-review` or
`gradle --no-daemon pmdPublicApiReview`. Maven writes each module's report under
`target/pmd-review/`; Gradle writes `build/reports/pmd/pmdPublicApiReview.xml`.
The baseline has no findings at 45, which is retained as a review threshold for
future public API growth. Published methods are retained. Advisory findings neither replace the
blocking report nor change its failure policy.

### NullAway

On JDK 25+, run `mvn -B -Pnullaway clean compile` or
`gradle --no-daemon -Pnullaway clean compileJava` to enforce production null contracts.
CI checks both build systems with NullAway errors and JSpecify generic
checking enabled. Tests remain outside this checker so invalid-input fixtures can
exercise runtime validation. Normal builds still support JDK 17 and 21; analysis
and normal compilation both produce Java 17 bytecode. Error Prone and NullAway
are compiler-only dependencies and are absent from published runtime dependency
graphs; JSpecify remains the existing public annotation dependency.

### Modernizer

On JDK 25, `mvn -B -Pmodernizer-java25 verify` also runs Modernizer with an
explicit Java 25 analysis target on every module's production and test bytecode.
The profile activates automatically on JDK 25+. The JDK 25 Maven CI leg is the
shared Modernizer enforcement point for both build systems; Gradle `check` does
not invoke it. Modernizer checks its known API catalog, not every newer Java
feature. Compiler release checking still protects Java 17 compatibility.

### FindSecBugs

Run `mvn -B -Psecurity -pl openrouter-spring-ai-autoconfigure -am -DskipTests verify`
for FindSecBugs analysis of compiled core and autoconfiguration production classes.
Tests, samples, and the dependency-only starter are excluded from security analysis.
The opt-in profile uses maximum effort, reports all confidence levels (`Low` threshold),
and fails on any selected SECURITY finding or analysis failure. Reports are written to
each analyzed module's `target/findsecbugs.xml` and `target/spotbugs/security/spotbugs.html`.
The security filter and detector plugin exist only inside this profile's execution;
ordinary SpotBugs analysis without `-Psecurity` remains
unfiltered and writes its separate `target/spotbugsXml.xml` report.
The method-specific exclusions in `config/spotbugs/security-exclude.xml` cover ASCII-validated
URI scheme comparison and tool log sanitization that the detector does not recognize.
Tool failure log messages replace CR and LF in tool names with underscores, with a
regression test for both messages; original exceptions remain available in local debug
diagnostics and observations.

CI's single `FindSecBugs security` job on JDK 25 is the shared enforcement point for
Maven and Gradle contributors; Gradle `check` does not run FindSecBugs. Compilation
still targets Java 17. The job runs the Maven security profile directly against
production library classes.

FindSecBugs overlaps CodeQL's `security-and-quality` suite on injection and other
security patterns, but provides a local bytecode gate without a code-scanning service.
CodeQL retains its broader source/data-flow analysis and independent workflow.
The security job fails builds immediately; making its status a required branch check
is deferred until its CI stability is established. This change does not alter branch
protection or replace CodeQL.

### SpotBugs correctness

The required SpotBugs correctness gate runs once per build tool on CI's JDK 25,
with Java 17 bytecode. Run it locally with either command:

```sh
mvn -B -Pcorrectness -pl openrouter-spring-ai-autoconfigure -am -DskipTests verify
gradle --no-daemon spotbugsCorrectness
```

It selects `NP_NULL_ON_SOME_PATH` and `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE`
for nullable dereferences, `OBL_UNSATISFIED_OBLIGATION` for resource cleanup,
and `DC_DOUBLECHECK` for unsafe double-checked publication. Any selected finding,
analysis error, missing dependency class, or empty required scope fails the gate.
Both tools analyze core and autoconfiguration production classes with their compile
dependency classpaths. Tests, samples, and the dependency-only starter are outside
the initial scope. Add the starter to both configurations if it gains executable code.

Maven writes `target/spotbugs-correctness.xml` plus `target/spotbugs/correctness/spotbugs.html`. Gradle writes
`build/reports/spotbugs/<policy>/spotbugs.xml` and `spotbugs.html` in each analyzed module.
The root POM pins the engine version; it is not a library runtime dependency.
Maven profiles can be combined as `-Psecurity,correctness`. Their filters,
extensions, reports, and skip properties are separate. Analysis runs during `package`;
the non-forking SpotBugs `verify` goal enforces findings during Maven `verify`, avoiding
the `check` goal's fork losing execution-specific configuration. Ordinary Gradle `check`
does not run these scans; the dedicated CI jobs enforce correctness and security.

Exceptions belong in the policy's `config/spotbugs/*-exclude.xml`, scoped to a detector
and specific method with a contract explanation. No package exclusions or generated
baseline are accepted. Correctness currently excludes two helper contracts SpotBugs
cannot infer; existing tests and NullAway cover those contracts. Repeated nullable
record accessors use local values so their guards remain visible to the analyzer.
NullAway checks source null contracts independently and does not inherit these exclusions.

### Qodana declaration access

CI's `Qodana declaration access` job runs IntelliJ's `WeakerAccess` inspection ("Declaration access can be weaker") with the free `jetbrains/qodana-jvm-community` linter, pinned in `qodana.yaml`. It needs no Qodana Cloud token. The inspection profile in `.qodana/weaker-access.yaml` enables only this inspection. Any finding fails the job and appears as an annotation on the pull request; the full report is uploaded as the `qodana-report` artifact. The job analyzes production sources of the core and autoconfiguration modules. Tests, samples, and the dependency-only starter are excluded.

The inspection only sees callers inside this repository, so it ignores the supported API listed in [Supported API and nullability](docs/api.md). Keep the scopes in `.qodana/weaker-access.yaml` in sync when that list changes. Mapper packages, `internal`, `support`, the error factories, classifiers, messages, wire responses, and deserializers in `errors`, and the remaining auto-configuration and `api` classes are checked. Narrow a reported declaration. When a framework or reflection needs the wider access, annotate the declaration with `@SuppressWarnings("WeakerAccess")` and a comment that gives the reason. Run the check locally with Docker from the repository root:

```sh
docker run --rm -v "$PWD:/data/project" -v "$PWD/../qodana-results:/data/results" jetbrains/qodana-jvm-community:2026.2
```

### Coverage

`mvn -B verify` and `gradle --no-daemon check` enforce JaCoCo coverage independently
for each library module, with at least 90% line and 80% branch coverage. The root
POM defines both floors; Gradle reads them from that POM. Reports and gates analyze
all production classes without configured exclusions, retaining JaCoCo's automatic
generated-code filtering. The parent aggregator and samples have no percentage gate.
The starter currently contains only package metadata, so it has no coverage percentage.
Adding production classes brings it under the same policy, including the check for
missing execution data. Package descriptors do not require execution data.

HTML and XML reports are in each module's `target/site/jacoco` for Maven or
`build/reports/jacoco/test` for Gradle. CI uploads these reports and synthetic test
results on verification failure, retaining artifacts for seven days without rerunning
tests. `mvn -B -DskipTests package` and `gradle --no-daemon assemble` still package
without coverage verification. Explicit Maven `-DskipTests` verification, used for
static security analysis, also skips coverage enforcement.

Coverage percentages supplement the behavioral tests for fragment ordering,
cancellation, terminal errors, reasoning replay, and tool aggregation.

### Gate integration tests

The library's own code passes every gate, so a normal build cannot show that a gate is still switched on. `mvn -B -N -Pgate-its verify` runs `maven-invoker-plugin` over the projects in `src/it`. Each project inherits the real parent POM, breaks one gate on purpose, and must fail for that reason: `coverage-below-floor` (JaCoCo line floor), `coverage-branch-below-floor` (JaCoCo branch floor), `pmd-lost-cause` (`PreserveStackTrace`), `spotbugs-correctness` (all four correctness detectors), and `findsecbugs-sql-injection` (`SQL_INJECTION_JDBC`). A `verify.groovy` script in each project checks the rule, so a build that fails for another reason still fails the run. The Java 25 Maven verification job runs this profile. When you change a gate's configuration, add or update the matching project.
