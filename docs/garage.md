# The Garage demo

[`openrouter-spring-ai-samples`](../openrouter-spring-ai-samples) is a small story: a garage foreman model inspects a customer's car, delegates one job to a specialist model, and writes a service record. A normal run stays easy to read; a `full` run turns it into a capability tour across both Chat Completions and experimental Responses (with registry-declared limitations).

![The service-story scene: the Foreman model runs a tool loop through ChatClient with inspect_vehicle_profile, hand_to_specialist, score_repair_plan and log_to_jobsheet, hands one job to a Specialist model, and writes service-record.md to disk. Modality bays: a triage matcher using embeddings, a digital inspection bay with image input in both request modes, and a paint bay for image generation. The run API: POST /api/runs returns 202 Accepted with a Location; GET /api/runs/{id} shows status, scene outcomes and cost, with evidence and report files. Evidence levels, weakest to strongest: configured, synthetic-tested, live-verified.](images/garage.svg)

The modality bays correlate completed chat, embedding, and image observations with the scene operation. Evidence includes timing, errors, modality-specific counts and sizes, and available usage and cost, with the same sanitization applied to telemetry snapshots and persisted reports. Image streams publish observation evidence after completion, error, or cancellation. Calls and stream subscriptions must start inside the sample's operation scope.

Every run asserts its own structural outcome, listed under "each run checks" in the diagram, and reports a failed run otherwise. These assertions check tool execution and response contracts independently of the model's prose. The service-story reasoning check accepts reasoning text or positive reasoning-token usage from any Foreman tool-loop round; the final answer need not repeat that evidence.

## What a run proves

The evidence levels at the bottom of the diagram build on each other. CI makes no OpenRouter calls and holds no OpenRouter key, so live verification is a manual Garage run with your own key; a workflow definition or badge is not that evidence. No current all-capabilities live success is asserted here. Unsupported, incomplete, failed and partial outcomes remain explicit.

The sample tests run the Garage against a mock OpenRouter without an API key; see [Test the Garage without an API key](../CONTRIBUTING.md#test-the-garage-without-an-api-key).

## Run the Garage

The Garage is a Spring MVC application. Build and start it from the repository root; `-am` builds the sibling modules without a prior install:

```bash
mvn -B -pl openrouter-spring-ai-samples -am -DskipTests package
java -jar openrouter-spring-ai-samples/target/openrouter-spring-ai-samples-0.1.0-SNAPSHOT.jar
```

The equivalent Gradle command (CI uses Gradle 9.7.1) is:

```bash
gradle --no-daemon :openrouter-spring-ai-samples:bootJar
java -jar openrouter-spring-ai-samples/build/libs/openrouter-spring-ai-samples-0.1.0-SNAPSHOT.jar
```

The filename follows the source POM revision, not the published starter version. The Garage listens on `127.0.0.1:8080`; set `GARAGE_ADDRESS` and `GARAGE_PORT` to change that. Anyone who can reach the port can start runs that make billable API calls, so keep it on the loopback address unless your own access control sits in front of it. Live scenes need `OPENROUTER_API_KEY` in the environment; offline contracts do not.

## Start a run and read its evidence

| Endpoint                                       | Purpose                                                                                                                                                                                                                                                                            |
| ---------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `GET /api/scenes`                              | Lists the scenes, whether each is offline, and the feature ids its evidence covers.                                                                                                                                                                                                |
| `POST /api/runs`                               | Starts a run. Returns `202 Accepted` with a `Location` header for the run.                                                                                                                                                                                                         |
| `GET /api/runs/{id}`                           | Shows the run status (`RUNNING`, `PASSED`, `FAILED`, or `ERROR`), scene outcomes, incomplete features, recorded cost, and links to its files. A failed scene names a reason: `truncated`, `no-endpoint`, `provider-error`, `protocol-error`, `transport-error`, or `check-failed`. |
| `GET /api/runs/{id}/evidence`                  | Returns the run's `garage-run.json` evidence bundle.                                                                                                                                                                                                                               |
| `GET /api/runs/{id}/report`                    | Returns the run's `capability-report.md`.                                                                                                                                                                                                                                          |
| `POST /api/sweeps`                             | Starts a model compatibility sweep. Returns `202 Accepted` like a run.                                                                                                                                                                                                             |
| `GET /api/runs/{id}/sweeps/{embedding\|image}` | Returns a sweep result document.                                                                                                                                                                                                                                                   |

The Garage records how each model call ended. A failed scene takes its reason from the first call that raised an error, then from the failure itself, then from a call cut off at its token limit. Otherwise the scene failed its own check. Each modality bay takes its reason from its own call, and the scene reports the first failed bay.

Runs execute in the background, one at a time, because a run's evidence collectors are shared. Starting a second run while one is active returns `409 Conflict` with a link to the active run. The evidence and report endpoints also return `409` until the run finishes. Run records last until the application stops; the files stay in the output directory.

```bash
curl -si -X POST http://127.0.0.1:8080/api/runs \
  -H 'Content-Type: application/json' \
  -d '{"offlineContracts": true}'
curl -s http://127.0.0.1:8080/api/runs/<id>
curl -s http://127.0.0.1:8080/api/runs/<id>/report
```

Invalid selections return `400 Bad Request` with a problem detail that names the conflict. Misspelled request fields are rejected rather than ignored. A live selection without an API key returns `422 Unprocessable Content`.

## Select what a run covers

Omit a request field to keep its `garage.*` default. An empty list clears a list default, such as `"fallbacks": []` or `"order": []`. Every field applies to that run only.

| Field              | Meaning                                                                                                                                                                   |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `capabilities`     | Any of `text`, `embedding`, `vision`, and `image`.                                                                                                                        |
| `scenes`           | Narrows a capability suite to these scene ids. Selected modalities require `modality-bays` in the list.                                                                   |
| `full`             | `true` runs every scene, embeddings, vision, and all image surfaces. It cannot be narrowed with `scenes`.                                                                 |
| `offlineContracts` | `true` runs only the local recovery and dyno contracts, without an API key.                                                                                               |
| `requestModes`     | `chat`, `responses`, or `both`; overrides a suite's default modes.                                                                                                        |
| `topic`            | The customer and car request to inspect.                                                                                                                                  |
| `models`           | `foreman`, `specialist`, `embedding`, `vision`, `image`, and a `fallbacks` list.                                                                                          |
| `image`            | `surface` is `sync` (the default), `streaming`, `chat`, or `all`; `quality` is optional.                                                                                  |
| `limits`           | `maxCompletionTokens` and `specialistMaxCompletionTokens`, both positive. Defaults are 4096 and 2048, which leave room for reasoning models.                              |
| `reasoningEffort`  | Text reasoning effort.                                                                                                                                                    |
| `provider`         | `sort` and `requireParameters` apply to every request. The `order`, `ignore`, and `quantizations` lists apply only to the routing-lane scene's full provider preferences. |

| Selection                                                | Request modes                                          | Selected work                                                        |
| -------------------------------------------------------- | ------------------------------------------------------ | -------------------------------------------------------------------- |
| Empty body                                               | Chat Completions                                       | `service-story`                                                      |
| `{"requestModes":["chat"]}`                              | Chat Completions                                       | `service-story`                                                      |
| `{"requestModes":["responses"]}`                         | Responses                                              | `service-story`                                                      |
| `{"capabilities":["text"]}`                              | Both                                                   | Text, tools, streaming, structured-output and offline text contracts |
| `{"capabilities":["text"],"requestModes":["chat"]}`      | Chat Completions                                       | Same text suite, narrowed to chat                                    |
| `{"capabilities":["text"],"requestModes":["responses"]}` | Responses                                              | Same suite; unsupported registry rows remain explicit                |
| `{"full":true}`                                          | Both                                                   | Every scene, embeddings, vision and all image surfaces               |
| `{"capabilities":["embedding"]}`                         | Chat Completions selection; mode-independent API       | Embeddings only                                                      |
| `{"capabilities":["vision"]}`                            | Both                                                   | Image input only; add `"requestModes":["chat"]` to narrow it         |
| `{"capabilities":["image"]}`                             | Chat Completions selection; mode-independent Image API | Synchronous image generation only                                    |

The Garage deliberately selects both modes for the `text` and `vision` capabilities and for `full`; the library and the plain service-story demo still default to Chat Completions. Explicit `requestModes` override suite defaults. `{"full":true,"requestModes":["responses"]}` is rejected because its chat-image surface requires Chat Completions; use individual capabilities instead. The current Garage registry does not verify Responses structured output, streamed tool aggregation or recovery contracts, even where the library has synthetic coverage. Embeddings and image generation are checked once per selection, not duplicated across modes. `GET /api/scenes` lists the same registry used by generated reports; report rows alone do not mean their features were executed.

A sweep request takes `embeddingModels` entries as `model[@providerTag]` and `imageModels` entries as `model[@providerTag][?key=value&...]`, where the image keys are `resolution`, `quality`, `aspect-ratio`, and `output-format`. It also accepts `imageQuality` and `provider`. Sweep results preserve input order, so match each result to the corresponding request entry.

`garage.*` properties bind once at startup, and unknown `garage.*` properties fail during binding. Request fields override those defaults for one run without changing them for the next. With no explicit selection, `garage.stream=true` adds `streaming-dispatch` to the service-story demo; explicit scene, capability, full, or offline selections take precedence over that default.

Model selection is independent of capabilities; a capability does not imply a free model. The Garage records what a run cost and reports it, but enforces no ceiling of its own. Cap spending with a credit limit on the OpenRouter API key instead, which is the only control that can stop a request before it is billed. Garage enables usage reporting per Chat Completions request rather than as a global chat default, so `ChatClient` can also use Responses mode. Structured-output probes require providers to support all requested parameters, including the JSON schema.

## Evidence and privacy

Each scene run writes `capability-report.md`, `garage-run.json`, and a bundle `README.md` to its own directory under `garage.output-dir`. Garage diagnostic JSON, Markdown, and sweep files retain only allowlisted fields and fixed labels, numeric measurements, booleans, and generated operation identifiers. Free-form text, model/provider names, paths, tool payloads, exception messages, and unknown objects are omitted or redacted; raw diagnostic payload retention is not supported. Authored service records and generated media are separate application outputs and may contain customer or model content. These report protections do not sanitize console logs.

Tool-loop evidence requires model-selected tools and correlated tool results sent in a follow-up request. The service story must complete inspection, specialist delegation, priority scoring, and service-record tools; a text-only answer cannot certify it. Structured-output probes test `responseFormat` and `outputSchema` independently. Feature reports retain each selected request mode's status. Mixed outcomes are `partial`, and a failed, missing, or incomplete required mode fails the run. Unsupported modes remain explicit; embeddings and image generation are checked once because those APIs are mode-independent.
