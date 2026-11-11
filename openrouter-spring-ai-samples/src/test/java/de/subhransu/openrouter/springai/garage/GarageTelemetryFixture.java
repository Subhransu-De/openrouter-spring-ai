package de.subhransu.openrouter.springai.garage;

import de.subhransu.openrouter.springai.garage.evidence.GarageEvidence;
import de.subhransu.openrouter.springai.garage.evidence.GarageTelemetry;
import de.subhransu.openrouter.springai.garage.evidence.GarageTelemetryConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Wires the telemetry the way the application does, through
 * {@link GarageTelemetryConfiguration}, so tests never need a widened constructor.
 */
final class GarageTelemetryFixture {

  private final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();

  GarageTelemetryFixture(GarageEvidence evidence) {
    this.context.registerBean(GarageEvidence.class, () -> evidence);
    this.context.register(GarageTelemetryConfiguration.class);
    this.context.refresh();
  }

  GarageTelemetry telemetry() {
    return this.context.getBean(GarageTelemetry.class);
  }

  SimpleMeterRegistry meters() {
    return this.context.getBean(SimpleMeterRegistry.class);
  }

  /** A registry whose handlers are the meter handler and the telemetry. */
  ObservationRegistry observations() {
    return this.context.getBean(ObservationRegistry.class);
  }

  /** Releases the context; call it from an {@code @AfterEach} method. */
  void close() {
    this.context.close();
  }

}
