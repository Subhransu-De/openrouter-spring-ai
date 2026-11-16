# The Garage demo

[`openrouter-spring-ai-samples`](../openrouter-spring-ai-samples) is a small story. A garage foreman model inspects a customer's car, delegates one job to a specialist model, and writes a service record. A normal run stays easy to read. A `full` run is a capability tour across Chat Completions and experimental Responses. The scenes, endpoints, request fields and defaults are in the samples source and its `application.yml`.

## Run the Garage

```bash
mvn -B -pl openrouter-spring-ai-samples -am -DskipTests package
java -jar openrouter-spring-ai-samples/target/openrouter-spring-ai-samples-0.2.0-SNAPSHOT.jar
curl -si -X POST http://127.0.0.1:8080/api/runs -H 'Content-Type: application/json' -d '{"offlineContracts": true}'
```

The Garage listens on `127.0.0.1:8080`. Anyone who can reach the port can start runs that make billable API calls, so keep it on the loopback address unless your own access control sits in front of it. Live scenes need `OPENROUTER_API_KEY` in the environment; offline contracts make no API call.

The Garage reports what a run cost but enforces no ceiling. To cap spending, set a credit limit on the OpenRouter API key.

## What a selection runs

| Selection                                                | Request modes                        | Selected work                                                        |
| -------------------------------------------------------- | ------------------------------------ | -------------------------------------------------------------------- |
| Empty body                                               | Chat Completions                     | `service-story`                                                      |
| `{"requestModes":["chat"]}`                              | Chat Completions                     | `service-story`                                                      |
| `{"requestModes":["responses"]}`                         | Responses                            | `service-story`                                                      |
| `{"capabilities":["text"]}`                              | Both                                 | Text, tools, streaming, structured-output and offline text contracts |
| `{"capabilities":["text"],"requestModes":["chat"]}`      | Chat Completions                     | Same suite, chat only                                                |
| `{"capabilities":["text"],"requestModes":["responses"]}` | Responses                            | Same suite, Responses only                                           |
| `{"full":true}`                                          | Both                                 | Every scene, embeddings, vision and all image surfaces               |
| `{"capabilities":["embedding"]}`                         | Chat Completions (not mode-specific) | Embeddings only                                                      |
| `{"capabilities":["vision"]}`                            | Both                                 | Image input only; add `"requestModes":["chat"]` to narrow it         |
| `{"capabilities":["image"]}`                             | Chat Completions (not mode-specific) | Synchronous image generation only                                    |

`GarageReportWriterTests` checks this table against the run planner.

## What the evidence proves

Each feature collects evidence in four stages, weakest to strongest: `CONFIGURED`, `EXECUTED`, `OBSERVED`, `ASSERTED`. A feature passes in a mode when it reaches all four with no recorded error. The checks test tool execution and response contracts, not the model's prose. A text-only answer cannot certify the service story: tool-loop evidence requires model-selected tools and their results sent in a follow-up request.

The registry does not verify Responses structured output, streamed tool aggregation or recovery contracts, even where the library has synthetic coverage.

CI makes no OpenRouter calls and holds no OpenRouter key. Live verification is a manual Garage run with your own key; a workflow definition or badge is not that evidence. No current all-capabilities live success is asserted here.

## Evidence and privacy

Evidence files under `garage.output-dir` keep only allowlisted fields, fixed labels, numbers, booleans and generated identifiers. Authored service records and generated media are separate outputs and may contain customer or model content. Console logs are not sanitized.
