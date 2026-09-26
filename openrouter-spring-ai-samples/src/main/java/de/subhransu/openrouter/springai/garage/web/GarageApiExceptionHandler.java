package de.subhransu.openrouter.springai.garage.web;

import de.subhransu.openrouter.springai.garage.GarageRunService;
import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/** Maps selection and scheduling failures of the Garage API to problem details. */
// Ordered ahead of Boot's generic problem-details handler so request errors name their cause.
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = GarageRunController.class)
class GarageApiExceptionHandler {

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ProblemDetail unreadableRequest(HttpMessageNotReadableException ex) {
    String detail = "The request body is not valid JSON for this endpoint";
    for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
      if (cause instanceof UnrecognizedPropertyException unknown) {
        detail = "Unknown request field '" + unknown.getPropertyName() + "'";
        break;
      }
    }
    return problem(HttpStatus.BAD_REQUEST, "Unreadable Garage request", detail);
  }

  // Only request validation throws IllegalArgumentException before a run is scheduled.
  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail invalidSelection(IllegalArgumentException ex) {
    return problem(HttpStatus.BAD_REQUEST, "Invalid Garage selection", ex.getMessage());
  }

  @ExceptionHandler(GarageRunService.RunActiveException.class)
  ProblemDetail runActive(GarageRunService.RunActiveException ex) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, "Garage run in progress", ex.getMessage());
    problem.setProperty("activeRun", "/api/runs/" + ex.activeRunId());
    return problem;
  }

  @ExceptionHandler(GarageRunService.MissingApiKeyException.class)
  ProblemDetail missingApiKey(GarageRunService.MissingApiKeyException ex) {
    return problem(HttpStatus.UNPROCESSABLE_CONTENT, "OpenRouter API key missing", ex.getMessage());
  }

  private static ProblemDetail problem(HttpStatus status, String title, @Nullable String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(title);
    return problem;
  }
}
