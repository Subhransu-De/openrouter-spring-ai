package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.audio.OpenRouterAudioSpeechModel;
import de.subhransu.openrouter.springai.audio.OpenRouterAudioSpeechOptions;
import de.subhransu.openrouter.springai.audio.OpenRouterAudioTranscriptionModel;
import de.subhransu.openrouter.springai.audio.OpenRouterAudioTranscriptionOptions;
import de.subhransu.openrouter.springai.audio.OpenRouterTimestampGranularity;
import de.subhransu.openrouter.springai.audio.OpenRouterTranscriptionFormat;
import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import de.subhransu.openrouter.springai.chat.OpenRouterTrace;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("configuration")
@Tag("configuration.beans")
class OpenRouterAudioAutoConfigurationTests {

	private static final String API_KEY = "spring.ai.openrouter.api-key=test-key";

	private static final String SPEECH = "spring.ai.openrouter.audio.speech.";

	private static final String TRANSCRIPTION = "spring.ai.openrouter.audio.transcription.";

	private static final String SELECT_SPEECH = "spring.ai.model.audio.speech=openrouter";

	private static final String SELECT_TRANSCRIPTION = "spring.ai.model.audio.transcription=openrouter";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(OpenRouterApiAutoConfiguration.class,
				OpenRouterAudioSpeechAutoConfiguration.class, OpenRouterAudioTranscriptionAutoConfiguration.class));

	@Test
	void audioModelsAreOptIn() {
		this.contextRunner.withPropertyValues(API_KEY)
			.run(context -> assertThat(context).hasNotFailed()
				.hasSingleBean(OpenRouterApi.class)
				.doesNotHaveBean(TextToSpeechModel.class)
				.doesNotHaveBean(TranscriptionModel.class));
		this.contextRunner.withPropertyValues(API_KEY, SELECT_SPEECH, SELECT_TRANSCRIPTION).run(context -> {
			assertThat(context).hasNotFailed()
				.hasSingleBean(OpenRouterAudioSpeechModel.class)
				.hasSingleBean(OpenRouterAudioTranscriptionModel.class);
			assertThat(context.getBean(TextToSpeechModel.class)).isInstanceOf(OpenRouterAudioSpeechModel.class);
			assertThat(context.getBean(TranscriptionModel.class)).isInstanceOf(OpenRouterAudioTranscriptionModel.class);
		});
	}

	@Test
	void anotherProvidersAudioModelIsNotDuplicatedWhenTheSelectorIsUnset() {
		// Spring AI's own audio providers match an unset selector too, and back off only
		// for
		// their own type. Opt-in keeps an application that already uses one at one bean.
		TextToSpeechModel speech = mock(TextToSpeechModel.class);
		TranscriptionModel transcription = mock(TranscriptionModel.class);
		this.contextRunner.withPropertyValues(API_KEY)
			.withBean(TextToSpeechModel.class, () -> speech)
			.withBean(TranscriptionModel.class, () -> transcription)
			.run(context -> {
				assertThat(context).hasNotFailed()
					.hasSingleBean(TextToSpeechModel.class)
					.hasSingleBean(TranscriptionModel.class);
				assertThat(context.getBean(TextToSpeechModel.class)).isSameAs(speech);
			});
	}

	@Test
	void applicationModelsWin() {
		TextToSpeechModel speech = mock(TextToSpeechModel.class);
		TranscriptionModel transcription = mock(TranscriptionModel.class);
		this.contextRunner.withPropertyValues(API_KEY, SELECT_SPEECH, SELECT_TRANSCRIPTION)
			.withBean(TextToSpeechModel.class, () -> speech)
			.withBean(TranscriptionModel.class, () -> transcription)
			.run(context -> {
				assertThat(context).hasNotFailed()
					.doesNotHaveBean(OpenRouterAudioSpeechModel.class)
					.doesNotHaveBean(OpenRouterAudioTranscriptionModel.class);
				assertThat(context.getBean(TextToSpeechModel.class)).isSameAs(speech);
				assertThat(context.getBean(TranscriptionModel.class)).isSameAs(transcription);
			});
	}

	@Test
	void anotherSelectedProviderTurnsEachModelOff() {
		this.contextRunner
			.withPropertyValues(API_KEY, "spring.ai.model.audio.speech=other",
					"spring.ai.model.audio.transcription=other")
			.run(context -> assertThat(context).hasNotFailed()
				.doesNotHaveBean(TextToSpeechModel.class)
				.doesNotHaveBean(TranscriptionModel.class));
	}

	@ParameterizedTest
	@ValueSource(strings = { "speech", "transcription" })
	void anExplicitAudioSelectionAloneCreatesTheClient(String modality) {
		ApplicationContextRunner otherModalitiesOff = this.contextRunner.withPropertyValues("spring.ai.model.chat=none",
				"spring.ai.model.embedding=none", "spring.ai.model.image=none");
		otherModalitiesOff.withPropertyValues("spring.ai.model.audio." + modality + "=openrouter")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("OpenRouter API key"));
		otherModalitiesOff.withPropertyValues(API_KEY, "spring.ai.model.audio." + modality + "=openrouter")
			.run(context -> {
				assertThat(context).hasNotFailed().hasSingleBean(OpenRouterApi.class);
				Class<?> selected = "speech".equals(modality) ? TextToSpeechModel.class : TranscriptionModel.class;
				assertThat(context).hasSingleBean(selected);
			});
	}

	@Test
	void unsetAudioSelectorsDoNotCreateTheClientWhenOtherModelsAreOff() {
		// Before the audio models existed, turning chat, embeddings and images off meant
		// that no OpenRouter key was needed. That must still hold.
		this.contextRunner
			.withPropertyValues("spring.ai.model.chat=none", "spring.ai.model.embedding=none",
					"spring.ai.model.image=none")
			.run(context -> assertThat(context).hasNotFailed()
				.doesNotHaveBean(OpenRouterApi.class)
				.doesNotHaveBean(TextToSpeechModel.class)
				.doesNotHaveBean(TranscriptionModel.class));
	}

	@Tag("configuration.properties")
	@Test
	void bindsSpeechPropertiesIntoModelDefaults() {
		this.contextRunner
			.withPropertyValues(API_KEY, SELECT_SPEECH, SPEECH + "model=synthetic/speech", SPEECH + "voice=alloy",
					SPEECH + "format=mp3", SPEECH + "speed=1.25", SPEECH + "instructions=Calm.",
					SPEECH + "user=synthetic-user", SPEECH + "session-id=garage-session",
					SPEECH + "trace.trace-name=garage", SPEECH + "trace.metadata.bay=3",
					SPEECH + "provider.data-collection=deny")
			.run(context -> {
				assertThat(context).hasNotFailed();
				OpenRouterAudioSpeechOptions options = context.getBean(OpenRouterAudioSpeechModel.class).getOptions();
				assertThat(options.getModel()).isEqualTo("synthetic/speech");
				assertThat(options.getVoice()).isEqualTo("alloy");
				assertThat(options.getFormat()).isEqualTo("mp3");
				assertThat(options.getSpeed()).isEqualTo(1.25);
				assertThat(options.getInstructions()).isEqualTo("Calm.");
				assertThat(options.getUser()).isEqualTo("synthetic-user");
				assertThat(options.getSessionId()).isEqualTo("garage-session");
				assertThat(options.getTrace())
					.isEqualTo(OpenRouterTrace.builder().traceName("garage").metadata(Map.of("bay", "3")).build());
				assertThat(options.getProvider())
					.isEqualTo(OpenRouterProviderPreferences.builder().dataCollection("deny").build());
			});
	}

	@Tag("configuration.properties")
	@Test
	void bindsTranscriptionPropertiesIntoModelDefaults() {
		this.contextRunner
			.withPropertyValues(API_KEY, SELECT_TRANSCRIPTION, TRANSCRIPTION + "model=synthetic/transcription",
					TRANSCRIPTION + "language=en", TRANSCRIPTION + "temperature=0.2",
					TRANSCRIPTION + "response-format=verbose-json",
					TRANSCRIPTION + "timestamp-granularities=word,segment", TRANSCRIPTION + "diarize=true",
					TRANSCRIPTION + "keyterms=rotor,brake pad", TRANSCRIPTION + "audio-format=wav",
					TRANSCRIPTION + "send-audio-url=true", TRANSCRIPTION + "session-id=garage-session",
					TRANSCRIPTION + "trace.trace-id=run-7", TRANSCRIPTION + "provider.data-collection=deny",
					TRANSCRIPTION + "max-inline-audio-size=2MB")
			.run(context -> {
				assertThat(context).hasNotFailed();
				OpenRouterAudioTranscriptionOptions options = context.getBean(OpenRouterAudioTranscriptionModel.class)
					.getOptions();
				assertThat(options.getModel()).isEqualTo("synthetic/transcription");
				assertThat(options.getLanguage()).isEqualTo("en");
				assertThat(options.getTemperature()).isEqualTo(0.2);
				assertThat(options.getResponseFormat()).isEqualTo(OpenRouterTranscriptionFormat.VERBOSE_JSON);
				assertThat(options.getTimestampGranularities()).containsExactly(OpenRouterTimestampGranularity.WORD,
						OpenRouterTimestampGranularity.SEGMENT);
				assertThat(options.getDiarize()).isTrue();
				assertThat(options.getKeyterms()).containsExactly("rotor", "brake pad");
				assertThat(options.getAudioFormat()).isEqualTo("wav");
				assertThat(options.getSendAudioUrl()).isTrue();
				assertThat(options.getSessionId()).isEqualTo("garage-session");
				assertThat(options.getTrace()).isEqualTo(OpenRouterTrace.builder().traceId("run-7").build());
				assertThat(options.getProvider())
					.isEqualTo(OpenRouterProviderPreferences.builder().dataCollection("deny").build());
				assertThat(context.getBean(OpenRouterAudioTranscriptionProperties.class).maxInlineAudioBytes())
					.isEqualTo(2 * 1024 * 1024);
			});
	}

	@Tag("configuration.properties")
	@ParameterizedTest
	@ValueSource(strings = { SPEECH + "format=wav", SPEECH + "speed=0", TRANSCRIPTION + "temperature=2",
			TRANSCRIPTION + "audio-format=wav;base64", TRANSCRIPTION + "max-inline-audio-size=0B",
			TRANSCRIPTION + "max-inline-audio-size=2GB", TRANSCRIPTION + "response-format=text" })
	void invalidDefaultsFailAtStartup(String property) {
		this.contextRunner.withPropertyValues(API_KEY, SELECT_SPEECH, SELECT_TRANSCRIPTION, property)
			.run(context -> assertThat(context).hasFailed());
	}

}
