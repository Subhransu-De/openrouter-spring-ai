# The Garage demo

[`openrouter-spring-ai-samples`](../openrouter-spring-ai-samples) is a small story. A garage foreman model inspects a customer's car, delegates one job to a specialist model, and writes a service record. A normal run stays easy to read. A `full` run is a capability tour across Chat Completions and experimental Responses.

Three terms recur:

- A scene is one self-contained part of a run, such as `service-story` (the default).
- The modality bays are one scene, `modality-bays`. It probes embeddings, image input and image generation.
- The registry is the Garage's list of features. Each row names the scene that exercises it. `GET /api/scenes` and every report use it.

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

The filename follows the source POM revision, not the published starter version. The Garage listens on `127.0.0.1:8080`; set `GARAGE_ADDRESS` and `GARAGE_PORT` to change that. Anyone who can reach the port can start runs that make billable API calls, so keep it on the loopback address unless your own access control sits in front of it. Live scenes need `OPENROUTER_API_KEY` in the environment. Offline contracts do not.

The sample tests run the Garage against a mock OpenRouter without an API key; see [Test the Garage without an API key](../CONTRIBUTING.md#test-the-garage-without-an-api-key).

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

```bash
curl -si -X POST http://127.0.0.1:8080/api/runs \
  -H 'Content-Type: application/json' \
  -d '{"offlineContracts": true}'
curl -s http://127.0.0.1:8080/api/runs/<id>
curl -s http://127.0.0.1:8080/api/runs/<id>/report
```

Runs execute in the background, one at a time, because a run's evidence collectors are shared. Starting a second run while one is active returns `409 Conflict` with a link to the active run. The evidence and report endpoints also return `409` until the run finishes. Run records last until the application stops; the files stay in the output directory.

Invalid selections return `400 Bad Request` with a problem detail that names the conflict. Misspelled request fields are rejected rather than ignored. A live selection without an API key returns `422 Unprocessable Content`.

A failed scene gets one failure reason. The Garage picks it in this order:

1. A reason the scene recorded itself. A modality bay takes its reason from its own model call, and the scene reports the first failed bay.
2. The first model call, in start order, that raised a classified error.
3. The type of the failure that ended the scene.
4. `truncated`, if a call was cut off at its token limit.
5. `check-failed`: the scene's own check failed.

## Select what a run covers

Omit a request field to keep its `garage.*` default. An empty list clears a list default, such as `"fallbacks": []` or `"order": []`. Every field applies to that run only.

| Field              | Meaning                                                                                                                                                                   |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `capabilities`     | Any of `text`, `embedding`, `vision`, and `image`.                                                                                                                        |
| `scenes`           | Narrows a capability suite to these scene ids. Selected modalities require `modality-bays` in the list.                                                                   |
| `full`             | `true` runs every scene, embeddings, vision, and all image surfaces. It cannot be narrowed with `scenes`.                                                                 |
| `offlineContracts` | `true` runs only the local recovery and dyno contracts, without an API key.                                                                                               |
| `requestModes`     | A JSON array of `"chat"`, `"responses"`, or `"both"`, such as `["chat","responses"]`. It overrides a suite's default modes.                                               |
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

The Garage selects both modes for the `text` and `vision` capabilities and for `full`. The library and the plain service-story demo still default to Chat Completions. `{"full":true,"requestModes":["responses"]}` is rejected because its chat-image surface requires Chat Completions; use individual capabilities instead. The registry does not verify Responses structured output, streamed tool aggregation or recovery contracts, even where the library has synthetic coverage. Embeddings and image generation are checked once per selection, not once per mode.

A sweep checks which models work. It runs the same embedding check or image prompt against each model you list and writes one result per model. `embeddingModels` entries are `model[@providerTag]`. `imageModels` entries are `model[@providerTag][?key=value&...]`, where the image keys are `resolution`, `quality`, `aspect-ratio`, and `output-format`. A sweep also accepts `imageQuality` and `provider`. Results keep input order, so match each result to its request entry.

## Defaults and models

The defaults live in `GarageProperties` and `openrouter-spring-ai-samples/src/main/resources/application.yml`. Each model has an environment variable, such as `GARAGE_FOREMAN_MODEL`.

Default models: foreman `openai/gpt-oss-120b`; specialist and fallback `openai/gpt-oss-20b`; embedding `openai/text-embedding-3-small`; vision `google/gemini-2.5-flash`; image `google/gemini-2.5-flash-image`. An empty body runs `service-story` with the foreman and specialist. The other models apply when you select their capability. `garage.request-modes` takes the enum names `OPENAI_CHAT_COMPLETIONS` and `OPENAI_RESPONSES`. It defaults to Chat Completions only.

`garage.*` properties bind once at startup, and unknown `garage.*` properties fail binding. Request fields override them for one run without changing the next. With no explicit selection, `garage.stream=true` adds `streaming-dispatch` to the service-story demo. Explicit scene, capability, full, or offline selections take precedence.

A capability does not imply a free model. The Garage reports what a run cost but enforces no ceiling. To cap spending, set a credit limit on the OpenRouter API key; it is the only control that stops a request before it is billed. Structured-output probes need providers that support all requested parameters, including the JSON schema.

## What a run does

![The service-story scene: the Foreman model runs a tool loop through ChatClient with inspect_vehicle_profile, hand_to_specialist, score_repair_plan and log_to_jobsheet, hands one job to a Specialist model, and writes service-record.md to disk. The scene checks that the record is written, every tool is invoked, usage is present, the answer is not empty, and synchronous observations are recorded. Modality bays: a triage matcher using embeddings, a digital inspection bay with image input in both request modes, and a paint bay for image generation. The run API: POST /api/runs returns 202 Accepted with a Location; GET /api/runs/{id} shows status, scene outcomes and cost, with evidence and report files. Evidence per feature in garage-run.json, weakest to strongest: CONFIGURED, EXECUTED, OBSERVED, ASSERTED.](images/garage.svg)

Each scene checks its own outcome, and the run fails if a check fails. The checks test tool execution and response contracts, not the model's prose. The synchronous `service-story` scene checks that:

- all four tools ran and the service record was written;
- the answer is not empty, and the response has an id, a served model, token usage, cost and cached-token counts;
- the sync observations were retained;
- reasoning text or reasoning-token usage appeared in any Foreman tool-loop round. The final answer need not repeat it.

The modality bays correlate completed chat, embedding and image observations with the scene operation. Image streams publish their evidence after completion, error or cancellation.

## Evidence levels

Each feature collects evidence in four stages, from weakest to strongest:

1. `CONFIGURED`: the scene selected the feature and recorded the options it uses.
2. `EXECUTED`: the call or tool ran.
3. `OBSERVED`: the outcome was recorded: response, usage, telemetry or wire details.
4. `ASSERTED`: the scene's checks passed.

A feature is complete for a run only when it reaches all four stages and has no recorded error. A feature is covered only when every selected, applicable request mode is complete. Mixed outcomes are `partial`. A failed, missing or incomplete required mode fails the run. Unsupported modes stay explicit as `unsupported-in-mode`.

CI makes no OpenRouter calls and holds no OpenRouter key. Live verification is a manual Garage run with your own key; a workflow definition or badge is not that evidence. No current all-capabilities live success is asserted here.

## Evidence and privacy

Each scene run writes `capability-report.md`, `garage-run.json`, and a bundle `README.md` to its own directory under `garage.output-dir`. Garage diagnostic JSON, Markdown, and sweep files retain only allowlisted fields and fixed labels, numeric measurements, booleans, and generated operation identifiers. Free-form text, model and provider names, paths, tool payloads, exception messages, and unknown objects are omitted or redacted. Raw diagnostic payload retention is not supported. Evidence also records timing, errors, counts, sizes, and available usage and cost, with the same sanitization for telemetry snapshots and persisted reports. Authored service records and generated media are separate outputs and may contain customer or model content. These protections do not sanitize console logs.

Tool-loop evidence requires model-selected tools and correlated tool results sent in a follow-up request. A text-only answer cannot certify the service story. Structured-output probes test `responseFormat` and `outputSchema` independently.
