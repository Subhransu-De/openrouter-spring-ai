package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.subhransu.openrouter.springai.chat.OpenRouterToolExecutionExceptionProcessor;
import de.subhransu.openrouter.springai.chat.OpenRouterToolFailurePolicy;
import io.micrometer.observation.ObservationRegistry;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.aop.scope.ScopedObject;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.context.support.SimpleThreadScope;
import org.springframework.test.util.ReflectionTestUtils;

class OpenRouterToolCallingAutoConfigurationTests {

	private static final String PROCESSOR_FIELD = "toolExecutionExceptionProcessor";

	private static final String UNVERIFIABLE_POLICY = "does not expose a verifiable provider-visible failure policy";

	private static final String REVIEWED_FAILURE = "reviewed failure";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withConfiguration(
			AutoConfigurations.of(OpenRouterToolCallingAutoConfiguration.class, ToolCallingAutoConfiguration.class));

	@Test
	void delegatingManagerCanDeclareItsActualSafePolicy() {
		this.contextRunner
			.withBean(ToolCallingManager.class,
					() -> new PolicyManager(new OpenRouterToolExecutionExceptionProcessor()))
			.run(context -> {
				assertThat(context).hasNotFailed().hasSingleBean(ToolCallingManager.class);
				assertThat(context.getBean(ToolCallingManager.class)
					.resolveToolDefinitions(ToolCallingChatOptions.builder().build())).isEmpty();
			});
	}

	@Test
	void customManagerCanDeclareAnApplicationOwnedProcessor() {
		ToolExecutionExceptionProcessor processor = exception -> REVIEWED_FAILURE;
		this.contextRunner.withBean(ToolExecutionExceptionProcessor.class, () -> processor)
			.withBean(ToolCallingManager.class, () -> new PolicyManager(processor))
			.run(context -> assertThat(context).hasNotFailed().hasSingleBean(ToolCallingManager.class));
	}

	@Test
	void explicitPolicyStillRejectsNullAndUndeclaredReturningProcessors() {
		for (ToolExecutionExceptionProcessor processor : new ToolExecutionExceptionProcessor[] { null,
				exception -> REVIEWED_FAILURE }) {
			this.contextRunner.withBean(ToolCallingManager.class, () -> new PolicyManager(processor)).run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).hasStackTraceContaining(UNVERIFIABLE_POLICY);
			});
		}
	}

	@Test
	void explicitPolicyCanDeclareAThrowingProcessor() {
		this.contextRunner
			.withBean(ToolCallingManager.class,
					() -> new PolicyManager(DefaultToolExecutionExceptionProcessor.builder().alwaysThrow(true).build()))
			.run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	void missingUpstreamFieldReportsTheSupportedVersionAndPublicEscapeHatch() {
		assertThatThrownBy(() -> SpringAiToolFailurePolicyAdapter.readField(Object.class,
				"toolExecutionExceptionProcessor", new Object()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContainingAll("Spring AI 2.0.1", "OpenRouterToolFailurePolicy",
					"application-declared ToolExecutionExceptionProcessor", "allow-unsafe-tool-failure-results=true");
	}

	@Test
	void registersNativeReflectionHintsForInspectedSpringAiFields() {
		RuntimeHints hints = new RuntimeHints();
		new OpenRouterToolCallingRuntimeHints().registerHints(hints, getClass().getClassLoader());

		assertThat(
				hints.reflection().getTypeHint(DefaultToolCallingManager.class).fields().map(field -> field.getName()))
			.containsExactly(PROCESSOR_FIELD);
		assertThat(hints.reflection()
			.getTypeHint(DefaultToolExecutionExceptionProcessor.class)
			.fields()
			.map(field -> field.getName())).containsExactly("alwaysThrow");
	}

	@Test
	void installsSafeProcessorBeforeSpringBuildsTheToolCallingManager() {
		this.contextRunner.withUserConfiguration(ObservationConfiguration.class).run(context -> {
			assertThat(context).hasSingleBean(ToolExecutionExceptionProcessor.class)
				.hasSingleBean(OpenRouterToolExecutionExceptionProcessor.class)
				.hasSingleBean(ToolCallingManager.class);
			ToolExecutionExceptionProcessor processor = context.getBean(ToolExecutionExceptionProcessor.class);
			assertThat(ReflectionTestUtils.getField(context.getBean(ToolCallingManager.class), PROCESSOR_FIELD))
				.isSameAs(processor);
			assertThat(ReflectionTestUtils.getField(processor, "observationRegistry"))
				.isSameAs(context.getBean(ObservationRegistry.class));
		});
	}

	@Test
	void userProcessorIsTheExplicitCustomizationHook() {
		this.contextRunner.withUserConfiguration(CustomProcessorConfiguration.class).run(context -> {
			assertThat(context).hasSingleBean(ToolExecutionExceptionProcessor.class)
				.doesNotHaveBean(OpenRouterToolExecutionExceptionProcessor.class);
			assertThat(context.getBean(ToolExecutionExceptionProcessor.class))
				.isSameAs(context.getBean(CustomProcessorConfiguration.class).processor);
		});
	}

	@Test
	void customManagerWithoutAnExplicitProcessorFailsClosed() {
		this.contextRunner.withUserConfiguration(CustomManagerConfiguration.class).run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class)
				.hasStackTraceContaining(UNVERIFIABLE_POLICY)
				.hasStackTraceContaining("allow-unsafe-tool-failure-results=true");
		});
	}

	@Test
	void customManagerCanInstallTheAutomaticallyConfiguredSafeProcessor() {
		this.contextRunner.withUserConfiguration(CustomManagerUsingAutoProcessorConfiguration.class).run(context -> {
			assertThat(context).hasNotFailed()
				.hasSingleBean(ToolCallingManager.class)
				.hasSingleBean(OpenRouterToolExecutionExceptionProcessor.class);
			assertThat(ReflectionTestUtils.getField(context.getBean(ToolCallingManager.class), PROCESSOR_FIELD))
				.isSameAs(context.getBean(OpenRouterToolExecutionExceptionProcessor.class));
		});
	}

	@Test
	void declaredProcessorThatTheManagerDoesNotInstallStillFailsClosed() {
		this.contextRunner.withUserConfiguration(UnusedCustomProcessorConfiguration.class).run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class)
				.hasStackTraceContaining(UNVERIFIABLE_POLICY);
		});
	}

	@Test
	void throwOnErrorDoesNotExcuseAnUnmanagedReturningProcessor() {
		this.contextRunner.withUserConfiguration(CustomManagerConfiguration.class)
			.withPropertyValues("spring.ai.tools.throw-exception-on-error=true")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class)
					.hasStackTraceContaining(UNVERIFIABLE_POLICY);
			});
	}

	@Test
	void unsafeCustomManagerRequiresAnExplicitPropertyOptIn() {
		this.contextRunner.withUserConfiguration(CustomManagerConfiguration.class)
			.withPropertyValues("spring.ai.openrouter.chat.allow-unsafe-tool-failure-results=true")
			.run(context -> assertThat(context).hasNotFailed().hasSingleBean(ToolCallingManager.class));
	}

	@Test
	void prototypeCustomManagerCannotEscapeValidation() {
		this.contextRunner.withUserConfiguration(PrototypeCustomManagerConfiguration.class).run(context -> {
			assertThat(context).hasNotFailed();
			assertThatThrownBy(() -> context.getBean(ToolCallingManager.class))
				.hasRootCauseInstanceOf(IllegalStateException.class)
				.hasStackTraceContaining(UNVERIFIABLE_POLICY);
		});
	}

	@Test
	void inactiveScopedManagerDoesNotPreventApplicationStartup() {
		this.contextRunner.withUserConfiguration(InactiveScopedManagerConfiguration.class)
			.run(context -> assertThat(context).hasNotFailed().hasBean("customToolCallingManager"));
	}

	@Test
	void scopedManagerIsValidatedWhenItsTargetIsCreated() {
		this.contextRunner
			.withInitializer(context -> context.getBeanFactory().registerScope("inactive", new SimpleThreadScope()))
			.withUserConfiguration(InactiveScopedManagerConfiguration.class)
			.run(context -> {
				assertThat(context).hasNotFailed();
				ScopedObject proxy = context.getBean("customToolCallingManager", ScopedObject.class);
				assertThatThrownBy(proxy::getTargetObject).hasRootCauseInstanceOf(IllegalStateException.class)
					.hasStackTraceContaining(UNVERIFIABLE_POLICY);
			});
	}

	@Test
	void prototypeProcessorInstalledInCustomManagerIsAnExplicitOwnershipBoundary() {
		this.contextRunner.withUserConfiguration(CustomManagerAndPrototypeProcessorConfiguration.class).run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(ToolCallingManager.class);
			Object processor = ReflectionTestUtils.getField(context.getBean(ToolCallingManager.class), PROCESSOR_FIELD);
			assertThat(processor).isInstanceOf(ToolExecutionExceptionProcessor.class);
			Set<?> trackedProcessors = (Set<?>) ReflectionTestUtils
				.getField(context.getBean(OpenRouterToolCallingManagerGuard.class), "transientDeclaredProcessors");
			assertThat(trackedProcessors).singleElement()
				.isInstanceOfSatisfying(WeakReference.class,
						reference -> assertThat(reference.get()).isSameAs(processor));
		});
	}

	@Test
	void replacementChatModelDoesNotActivateOpenRouterToolPolicy() {
		this.contextRunner
			.withUserConfiguration(CustomManagerConfiguration.class, ReplacementChatModelConfiguration.class)
			.run(context -> assertThat(context).hasNotFailed()
				.hasSingleBean(ChatModel.class)
				.doesNotHaveBean(OpenRouterToolCallingManagerGuard.class)
				.doesNotHaveBean(OpenRouterToolExecutionExceptionProcessor.class));
	}

	@Test
	void customManagerAndProcessorAreAnExplicitOwnershipBoundary() {
		this.contextRunner.withUserConfiguration(CustomManagerAndProcessorConfiguration.class).run(context -> {
			assertThat(context).hasNotFailed()
				.hasSingleBean(ToolCallingManager.class)
				.hasSingleBean(ToolExecutionExceptionProcessor.class)
				.doesNotHaveBean(OpenRouterToolExecutionExceptionProcessor.class);
			assertThat(ReflectionTestUtils.getField(context.getBean(ToolCallingManager.class), PROCESSOR_FIELD))
				.isSameAs(context.getBean(ToolExecutionExceptionProcessor.class));
		});
	}

	@Test
	void processorInitializedByAnEarlierPostProcessorRemainsDeclared() {
		this.contextRunner.withUserConfiguration(EarlyProcessorConfiguration.class).run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(ToolCallingManager.class);
			assertThat(ReflectionTestUtils.getField(context.getBean(ToolCallingManager.class), PROCESSOR_FIELD))
				.isSameAs(context.getBean(ToolExecutionExceptionProcessor.class));
		});
	}

	@Test
	void springThrowOnErrorSettingKeepsItsThrowingProcessor() {
		this.contextRunner.withPropertyValues("spring.ai.tools.throw-exception-on-error=true").run(context -> {
			assertThat(context).doesNotHaveBean(OpenRouterToolExecutionExceptionProcessor.class)
				.hasSingleBean(DefaultToolExecutionExceptionProcessor.class);
		});
	}

	@Test
	void anotherChatProviderKeepsSpringAIDefaults() {
		this.contextRunner.withPropertyValues("spring.ai.model.chat=other").run(context -> {
			assertThat(context).doesNotHaveBean(OpenRouterToolExecutionExceptionProcessor.class)
				.hasSingleBean(DefaultToolExecutionExceptionProcessor.class);
		});
	}

	private static final class PolicyManager implements ToolCallingManager, OpenRouterToolFailurePolicy {

		private final ToolExecutionExceptionProcessor processor;

		private final ToolCallingManager delegate;

		PolicyManager(ToolExecutionExceptionProcessor processor) {
			this.processor = processor;
			this.delegate = processor == null ? ToolCallingManager.builder().build()
					: ToolCallingManager.builder().toolExecutionExceptionProcessor(processor).build();
		}

		@Override
		public ToolExecutionExceptionProcessor toolExecutionExceptionProcessor() {
			return this.processor;
		}

		@Override
		public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
			return this.delegate.resolveToolDefinitions(options);
		}

		@Override
		public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
			return this.delegate.executeToolCalls(prompt, response);
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class ObservationConfiguration {

		@Bean
		ObservationRegistry observationRegistry() {
			return ObservationRegistry.create();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class CustomProcessorConfiguration {

		private final ToolExecutionExceptionProcessor processor = exception -> exception.getMessage();

		@Bean
		ToolExecutionExceptionProcessor customProcessor() {
			return this.processor;
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class CustomManagerConfiguration {

		@Bean
		ToolCallingManager customToolCallingManager() {
			return ToolCallingManager.builder().build();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class ReplacementChatModelConfiguration {

		@Bean
		ChatModel replacementChatModel() {
			return prompt -> null;
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class CustomManagerAndProcessorConfiguration {

		@Bean
		ToolExecutionExceptionProcessor customProcessor() {
			return exception -> REVIEWED_FAILURE;
		}

		@Bean
		ToolCallingManager customToolCallingManager(ToolExecutionExceptionProcessor processor) {
			return ToolCallingManager.builder().toolExecutionExceptionProcessor(processor).build();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class CustomManagerUsingAutoProcessorConfiguration {

		@Bean
		ToolCallingManager customToolCallingManager(ToolExecutionExceptionProcessor processor) {
			return ToolCallingManager.builder().toolExecutionExceptionProcessor(processor).build();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class UnusedCustomProcessorConfiguration {

		@Bean
		ToolExecutionExceptionProcessor customProcessor() {
			return exception -> REVIEWED_FAILURE;
		}

		@Bean
		ToolCallingManager customToolCallingManager() {
			return ToolCallingManager.builder().build();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class PrototypeCustomManagerConfiguration {

		@Bean
		@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
		ToolCallingManager customToolCallingManager() {
			return ToolCallingManager.builder().build();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class InactiveScopedManagerConfiguration {

		@Bean
		@Scope(value = "inactive", proxyMode = ScopedProxyMode.INTERFACES)
		ToolCallingManager customToolCallingManager() {
			return ToolCallingManager.builder().build();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class EarlyProcessorConfiguration {

		@Bean
		static BeanPostProcessor earlierPostProcessor(ToolExecutionExceptionProcessor processor) {
			Objects.requireNonNull(processor, "processor");
			return new BeanPostProcessor() {
			};
		}

		@Bean
		ToolExecutionExceptionProcessor customProcessor() {
			return exception -> REVIEWED_FAILURE;
		}

		@Bean
		ToolCallingManager customToolCallingManager(ToolExecutionExceptionProcessor processor) {
			return ToolCallingManager.builder().toolExecutionExceptionProcessor(processor).build();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class CustomManagerAndPrototypeProcessorConfiguration {

		@Bean
		@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
		ToolExecutionExceptionProcessor customProcessor() {
			return exception -> REVIEWED_FAILURE;
		}

		@Bean
		ToolCallingManager customToolCallingManager(ToolExecutionExceptionProcessor processor) {
			return ToolCallingManager.builder().toolExecutionExceptionProcessor(processor).build();
		}

	}

}
