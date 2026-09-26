package de.subhransu.openrouter.springai.garage.scenes;

import tools.jackson.databind.ObjectMapper;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.garage.GarageOptionsFactory;
import de.subhransu.openrouter.springai.garage.GarageProperties;
import de.subhransu.openrouter.springai.garage.run.GarageRunPlan;
import de.subhransu.openrouter.springai.garage.evidence.GarageEvidence;
import de.subhransu.openrouter.springai.garage.evidence.GarageTelemetry;
import de.subhransu.openrouter.springai.garage.evidence.GarageTransportEvidence;
import java.nio.file.Path;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

/** Dependencies and run identity supplied to each scene execution. */
public record SceneContext(
    GarageRunPlan plan,
    OpenRouterRequestMode requestMode,
    Path outputDirectory,
    ChatModel chatModel,
    ChatClient chatClient,
    GarageProperties properties,
    GarageOptionsFactory optionsFactory,
    ObjectMapper objectMapper,
    GarageEvidence evidence,
    GarageTelemetry telemetry,
    GarageTransportEvidence transportEvidence,
    ObservationRegistry observationRegistry) {}
