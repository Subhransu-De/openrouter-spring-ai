package de.subhransu.openrouter.springai.decision.mapper;

import de.subhransu.openrouter.springai.api.dto.DecisionsResponse;
import de.subhransu.openrouter.springai.api.dto.Usage;
import de.subhransu.openrouter.springai.chat.OpenRouterMetadata;
import de.subhransu.openrouter.springai.chat.mapper.UsageMapper;
import de.subhransu.openrouter.springai.errors.OpenRouterDiagnosticSanitizer;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.decision.DecisionPrompt;
import org.springframework.ai.decision.DecisionResponse;
import org.springframework.ai.decision.DecisionResponseMetadata;
import org.springframework.ai.decision.answer.ChoiceAnswer;
import org.springframework.ai.decision.answer.DecisionAnswer;
import org.springframework.ai.decision.answer.RefusalAnswer;
import org.springframework.ai.decision.answer.ScoreAnswer;
import org.springframework.ai.decision.answer.YesNoAnswer;
import org.springframework.ai.decision.question.ChoiceQuestion;
import org.springframework.ai.decision.question.DecisionQuestion;
import org.springframework.ai.decision.question.ScoreQuestion;
import org.springframework.ai.decision.question.YesNoQuestion;

/**
 * Maps a Decisions API response to the {@link DecisionResponse} for its prompt.
 *
 * <p>
 * A {@code noul} becomes a {@link YesNoAnswer}, a {@code choice} a {@link ChoiceAnswer}
 * and a {@code score} a {@link ScoreAnswer}, each with the probabilities and confidence
 * OpenRouter reports; a {@code refusal} becomes a {@link RefusalAnswer}. The response id,
 * model and usage, with its cost, go into the response metadata, and the upstream
 * provider name under {@link OpenRouterMetadata#PROVIDER}.
 *
 * <p>
 * A response that does not answer the prompt fails with an
 * {@link OpenRouterProtocolException} instead of being read as a verdict: a missing or
 * extra answer, an answer of another type than its question, such as a {@code box}, or a
 * value outside the question's options, levels or probability range, or a null
 * probability. The message names the mismatch with credentials redacted and keeps no
 * cause, so no raw answer value travels with it.
 *
 * @author Subhransu De
 */
public final class OpenRouterDecisionResponseMapper {

	private static final String ENDPOINT = "/alpha/decisions";

	private final OpenRouterDiagnosticSanitizer sanitizer;

	public OpenRouterDecisionResponseMapper(OpenRouterDiagnosticSanitizer sanitizer) {
		this.sanitizer = sanitizer;
	}

	// The cause is dropped on purpose: its message can carry raw answer values.
	@SuppressWarnings("PMD.PreserveStackTrace")
	public DecisionResponse map(DecisionPrompt prompt, DecisionsResponse response) {
		Usage usage = response.usage();
		DecisionResponseMetadata metadata = DecisionResponseMetadata.builder()
			.id(response.id())
			.model(response.model())
			.usage(usage != null ? UsageMapper.map(usage) : new EmptyUsage())
			.build();
		String provider = response.provider();
		if (provider != null) {
			metadata.put(OpenRouterMetadata.PROVIDER, provider);
		}
		DecisionResponse.Builder builder = DecisionResponse.builder(prompt).metadata(metadata);
		Map<String, DecisionsResponse.@Nullable Answer> reported = response.answers();
		Map<String, DecisionsResponse.@Nullable Answer> answers = reported != null ? reported : Map.of();
		try {
			answers
				.forEach((name, answer) -> builder.answer(name, answer(name, answer, prompt.getQuestions().get(name))));
			return builder.build();
		}
		catch (IllegalArgumentException | IllegalStateException ex) {
			throw new OpenRouterProtocolException(OpenRouterProtocolException.Kind.CONTRACT, ENDPOINT,
					"OpenRouter decisions response does not answer the prompt: "
							+ this.sanitizer.sanitize(ex.getMessage()));
		}
	}

	private static DecisionAnswer answer(String name, DecisionsResponse.@Nullable Answer answer,
			@Nullable DecisionQuestion question) {
		if (question == null) {
			throw new IllegalStateException("the prompt has no question named '" + name + "'");
		}
		if (answer == null) {
			throw new IllegalStateException(answerTo(name) + " is null");
		}
		String type = answer.type();
		if ("refusal".equals(type)) {
			return RefusalAnswer.builder().build();
		}
		if (question instanceof YesNoQuestion && "noul".equals(type)) {
			return YesNoAnswer.builder().probability(required(name, "noul", answer.noul())).build();
		}
		if (question instanceof ChoiceQuestion && "choice".equals(type)) {
			return ChoiceAnswer.builder()
				.value(required(name, "choice", answer.choice()))
				.probabilities(probabilities(name, answer))
				.confidence(answer.confidence())
				.build();
		}
		if (question instanceof ScoreQuestion scoreQuestion && "score".equals(type)) {
			return ScoreAnswer.builder()
				.value(required(name, "score", answer.score()))
				.probabilities(levelProbabilities(name, answer, scoreQuestion.getLevels().size()))
				.confidence(answer.confidence())
				.build();
		}
		throw new IllegalStateException(answerTo(name) + " has type " + type + ", which does not answer a "
				+ question.getClass().getSimpleName());
	}

	// An omitted distribution stays empty; a null entry is not a probability.
	private static Map<String, Double> probabilities(String name, DecisionsResponse.Answer answer) {
		Map<String, Double> probabilities = new LinkedHashMap<>();
		Map<String, @Nullable Double> reported = answer.probabilities();
		if (reported != null) {
			reported.forEach((option, probability) -> probabilities.put(option,
					required(name, "probability for '" + option + "'", probability)));
		}
		return probabilities;
	}

	// OpenRouter keys level probabilities by index; an empty report stays empty, as the
	// ScoreAnswer contract promises, and unreported levels count as zero. A key that is
	// not an index fails without its parse error, which repeats the raw key.
	@SuppressWarnings("PMD.PreserveStackTrace")
	private static List<Double> levelProbabilities(String name, DecisionsResponse.Answer answer, int levels) {
		Map<String, Double> reported = probabilities(name, answer);
		if (reported.isEmpty()) {
			return List.of();
		}
		List<Double> probabilities = new ArrayList<>(Collections.nCopies(levels, 0.0));
		reported.forEach((key, probability) -> {
			int level;
			try {
				level = Integer.parseInt(key);
			}
			catch (NumberFormatException ex) {
				throw new IllegalStateException(answerTo(name) + " reports a probability for level '" + key + "'");
			}
			if (level < 0 || level >= levels) {
				throw new IllegalStateException(
						answerTo(name) + " reports a probability for level " + level + " of " + levels);
			}
			probabilities.set(level, probability);
		});
		return probabilities;
	}

	private static String answerTo(String name) {
		return "the answer to question '" + name + "'";
	}

	private static <T> T required(String name, String field, @Nullable T value) {
		if (value == null) {
			throw new IllegalStateException(answerTo(name) + " has no " + field);
		}
		return value;
	}

}
