# Decisions

`OpenRouterDecisionModel` implements Spring AI's `DecisionModel` with OpenRouter's [Decisions API](https://openrouter.ai/docs/guides/features/decisions). It is opt-in: set `spring.ai.model.decision=openrouter` and a decision model, such as `spring.ai.openrouter.decision.model=typesafe/jev-1.14`. To list the decision models, query `https://openrouter.ai/api/v1/models?output_modalities=decisions`.

**Experimental:** the Decisions API is in alpha (`POST /api/alpha/decisions`) and OpenRouter may change its shape, so this model can change in a minor release.

The examples use an injected `DecisionModel decisionModel`.

## Yes or no

The answer is the probability of yes. Pick the threshold that fits the decision.

```java
DecisionResponse response =
    decisionModel.call(ticket, Map.of("urgent", YesNoQuestion.of("Is this ticket urgent?")));
boolean urgent = response.getYesNo("urgent").isYes(0.8);
```

## Several questions at once

One call answers all questions about the same input, in the order they were added.

```java
DecisionResponse response =
    decisionModel.call(
        DecisionPrompt.builder()
            .text(ticket)
            .question(
                "refund",
                YesNoQuestion.builder()
                    .text("Is the customer asking for a refund?")
                    .whenYes("Asks for money back")
                    .build())
            .question(
                "team",
                ChoiceQuestion.builder()
                    .text("Which team should handle this ticket?")
                    .option("billing", "Invoices, charges and refunds")
                    .option("technical", "Bugs, outages and integrations")
                    .option("sales")
                    .build())
            .question("urgency", ScoreQuestion.of("How urgent is this?", "Low", "Medium", "High"))
            .build());
String team = response.getChoice("team").getValue();
Map<String, Double> split = response.getChoice("team").getProbabilities();
int urgency = response.getScore("urgency").getNearestLevel();
```

## Refusals

A provider can decline one question and answer the others. A refusal is not a "no", so fail closed when the decision gates something sensitive.

```java
if (response.getAnswer("urgent") instanceof YesNoAnswer answer) {
  boolean urgent = answer.isYes();
} else {
  // Refused: route the ticket to a person.
}
```

## Fields, conversations and images

Fields are sent as a JSON object in field order, and a conversation as its messages with their tool calls and results. Images go in a content input.

```java
Map<String, Object> fields = new LinkedHashMap<>();
fields.put("customer_tier", "enterprise");
fields.put("ticket", ticket);
decisionModel.call(DecisionInput.fields(fields), questions);

List<Message> conversation =
    List.of(new UserMessage("My brakes grind."), new AssistantMessage("Book a brake inspection."));
decisionModel.call(DecisionInput.conversation(conversation), questions);

decisionModel.call(
    DecisionInput.content(
        TextPart.of("Photo from the vehicle inspection:"),
        MediaPart.of(new Media(MimeTypeUtils.IMAGE_JPEG, new FileSystemResource("bumper.jpg")))),
    questions);
```

## Decision options

Choose the model, provider routing and observability fields for one request.

```java
DecisionResponse response =
    decisionModel.call(
        DecisionPrompt.builder()
            .text(ticket)
            .question("urgent", YesNoQuestion.of("Is this ticket urgent?"))
            .options(
                OpenRouterDecisionOptions.builder()
                    .model("liquid/d1.2")
                    .provider(OpenRouterProviderPreferences.builder().dataCollection("deny").build())
                    .sessionId("ticket-4711")
                    .build())
            .build());
```

## Usage and cost

```java
OpenRouterUsage usage = OpenRouterUsage.from(response);
Double cost = usage.getCost();
```

## Good to know

- **Question types:** a `YesNoQuestion` is sent as a `noul`, with `whenYes` and `whenNo` as its `true` and `false` criteria. A `ChoiceQuestion` is sent as a `choice`, with each option's description, or `null` when it has none. A `ScoreQuestion` is sent as a `score` with its levels from the lowest, each as its label, or as a `label` and `description` object.
- **No box questions:** OpenRouter's `box` question, which locates an object in an image, has no Spring AI question type, so `DecisionModel` cannot ask it.
- **Answers that do not fit:** a missing or extra answer, an answer of another type than its question, or a value outside the options, levels or probability range fails with `OpenRouterProtocolException`. It is never read as a verdict.
- **Yes or no confidence:** some providers send a `confidence` with a `noul` answer. Spring AI's `YesNoAnswer` holds only the probability, so the confidence is not kept.
- **Inputs:** images are sent as `image_url` parts and only in a content input, because OpenRouter reads images only at the top level of the state. Fields and conversations must be text. Audio and video inputs and the `assets` map are not sent. Reasoning parts of a conversation are left out.
- **Metadata:** the response id, model and usage are in `response.getMetadata()`, and the upstream provider name is under `OpenRouterMetadata.PROVIDER`.
- **Observations:** calls are observed with Spring AI's decision observation, so the standard `gen_ai.client.operation` and token usage meters apply.
- **Endpoint:** the request goes to `../alpha/decisions` relative to `spring.ai.openrouter.base-url`, which is `https://openrouter.ai/api/alpha/decisions` by default.
