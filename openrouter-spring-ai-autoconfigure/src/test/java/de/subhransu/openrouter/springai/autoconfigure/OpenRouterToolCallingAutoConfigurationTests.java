package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.subhransu.openrouter.springai.chat.OpenRouterToolCallingManagers;
import de.subhransu.openrouter.springai.chat.OpenRouterToolExecutionExceptionProcessor;
import de.subhransu.openrouter.springai.chat.OpenRouterToolFailurePolicy;
import io.micrometer.observation.ObservationRegistry;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.aop.scope.ScopedObject;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.SmartFactoryBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanFactory;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.context.support.SimpleThreadScope;
import org.springframework.test.util.ReflectionTestUtils;

class OpenRouterToolCallingAutoConfigurationTests {

	private static final String MISSING_POLICY = "does not declare a provider-visible tool failure policy";

	private static final String UNDECLARED_PROCESSOR = "is not an application-declared bean";

	private static final String OPT_OUT = "allow-unsafe-tool-failure-results=true";

	private static final String CUSTOM_MANAGER = "customToolCallingManager";

	private static final String REVIEWED_FAILURE = "reviewed failure";

	private static final String SECRET = "jdbc:postgresql://db-internal.example:5432/orders";

	private static final ApplicationContextInitializer<ConfigurableApplicationContext> GLOBAL_LAZY_INITIALIZATION = (
			context) -> context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor());

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
				assertThat(context.getStartupFailure()).hasStackTraceContaining(UNDECLARED_PROCESSOR);
			});
		}
	}

	@Test
	void inlineThrowingProcessorMustBeDeclaredAsABean() {
		this.contextRunner.withBean(ToolCallingManager.class, () -> OpenRouterToolCallingManagers
			.withFailurePolicy(DefaultToolExecutionExceptionProcessor.builder().alwaysThrow(true).build(), builder -> {
			})).run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class)
					.hasStackTraceContaining(UNDECLARED_PROCESSOR)
					.hasStackTraceContaining("Declare the processor as a bean")
					.hasStackTraceContaining(OPT_OUT);
			});
	}

	@Test
	void declaredThrowingProcessorKeepsSpringAiThrowingBehavior() {
		this.contextRunner.withUserConfiguration(ThrowingProcessorConfiguration.class).run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(ToolCallingManager.class);
			assertThatThrownBy(() -> failingToolResult(context.getBean(ToolCallingManager.class)))
				.isInstanceOf(ToolExecutionException.class);
		});
	}

	@Test
	void installsSafeProcessorBeforeSpringBuildsTheToolCallingManager() {
		this.contextRunner.withUserConfiguration(ObservationConfiguration.class).run(context -> {
			assertThat(context).hasSingleBean(ToolExecutionExceptionProcessor.class)
				.hasSingleBean(OpenRouterToolExecutionExceptionProcessor.class)
				.hasSingleBean(ToolCallingManager.class);
			assertThat(failingToolResult(context.getBean(ToolCallingManager.class)))
				.isEqualTo(OpenRouterToolExecutionExceptionProcessor.DEFAULT_FAILURE_PAYLOAD);
			assertThat(ReflectionTestUtils.getField(context.getBean(ToolExecutionExceptionProcessor.class),
					"observationRegistry"))
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
	void plainSpringAiManagerFailsWithMigrationGuidance() {
		this.contextRunner.withUserConfiguration(CustomManagerConfiguration.class).run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class)
				.hasStackTraceContaining(MISSING_POLICY)
				.hasStackTraceContaining("OpenRouterToolCallingManagers.withFailurePolicy")
				.hasStackTraceContaining("implement OpenRouterToolFailurePolicy")
				.hasStackTraceContaining(OPT_OUT);
		});
	}

	@Test
	void factoryManagerCanInstallTheAutomaticallyConfiguredSafeProcessor() {
		this.contextRunner.withUserConfiguration(CustomManagerUsingAutoProcessorConfiguration.class).run(context -> {
			assertThat(context).hasNotFailed()
				.hasSingleBean(ToolCallingManager.class)
				.hasSingleBean(OpenRouterToolExecutionExceptionProcessor.class);
			assertThat(failingToolResult(context.getBean(ToolCallingManager.class)))
				.isEqualTo(OpenRouterToolExecutionExceptionProcessor.DEFAULT_FAILURE_PAYLOAD);
		});
	}

	@Test
	void declaredProcessorThatTheManagerDoesNotInstallStillFailsClosed() {
		this.contextRunner.withUserConfiguration(UnusedCustomProcessorConfiguration.class).run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class)
				.hasStackTraceContaining(UNDECLARED_PROCESSOR);
		});
	}

	@Test
	void managerRegisteredWithoutABeanDefinitionIsStillValidated() {
		this.contextRunner
			.withInitializer(context -> context.getBeanFactory()
				.registerSingleton("manualToolCallingManager", ToolCallingManager.builder().build()))
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class)
					.hasStackTraceContaining(MISSING_POLICY);
			});
	}

	@Test
	void throwOnErrorDoesNotExcuseAManagerWithoutPolicy() {
		this.contextRunner.withUserConfiguration(CustomManagerConfiguration.class)
			.withPropertyValues("spring.ai.tools.throw-exception-on-error=true")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class)
					.hasStackTraceContaining(MISSING_POLICY);
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
				.hasStackTraceContaining(MISSING_POLICY);
		});
	}

	@Test
	void inactiveScopedManagerDoesNotPreventApplicationStartup() {
		this.contextRunner.withUserConfiguration(InactiveScopedManagerConfiguration.class)
			.run(context -> assertThat(context).hasNotFailed().hasBean(CUSTOM_MANAGER));
	}

	@Test
	void scopedManagerIsValidatedWhenItsTargetIsCreated() {
		this.contextRunner
			.withInitializer(context -> context.getBeanFactory().registerScope("inactive", new SimpleThreadScope()))
			.withUserConfiguration(InactiveScopedManagerConfiguration.class)
			.run(context -> {
				assertThat(context).hasNotFailed();
				ScopedObject proxy = context.getBean(CUSTOM_MANAGER, ScopedObject.class);
				assertThatThrownBy(proxy::getTargetObject).hasRootCauseInstanceOf(IllegalStateException.class)
					.hasStackTraceContaining(MISSING_POLICY);
			});
	}

	@Test
	void prototypeProcessorInstalledInCustomManagerIsAnExplicitOwnershipBoundary() {
		this.contextRunner.withUserConfiguration(CustomManagerAndPrototypeProcessorConfiguration.class).run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(ToolCallingManager.class);
			ToolCallingManager manager = context.getBean(ToolCallingManager.class);
			assertThat(failingToolResult(manager)).isEqualTo(REVIEWED_FAILURE);
			ToolExecutionExceptionProcessor processor = ((OpenRouterToolFailurePolicy) manager)
				.toolExecutionExceptionProcessor();
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
			assertThat(failingToolResult(context.getBean(ToolCallingManager.class))).isEqualTo(REVIEWED_FAILURE);
		});
	}

	@Test
	void processorInitializedByAnEarlierPostProcessorRemainsDeclared() {
		this.contextRunner.withUserConfiguration(EarlyProcessorConfiguration.class).run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(ToolCallingManager.class);
			assertThat(failingToolResult(context.getBean(ToolCallingManager.class))).isEqualTo(REVIEWED_FAILURE);
		});
	}

	@Test
	void plainManagerInheritedFromAParentContextFailsValidation() {
		new ApplicationContextRunner().withUserConfiguration(CustomManagerConfiguration.class).run(parent -> {
			this.contextRunner.withParent(parent).run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class)
					.hasStackTraceContaining(MISSING_POLICY);
			});
			assertThat(guards(parent)).isEmpty();
		});
	}

	@Test
	void prototypeManagerInAnUnguardedParentIsValidatedWhenCreated() {
		new ApplicationContextRunner().withUserConfiguration(PrototypeCustomManagerConfiguration.class).run(parent -> {
			this.contextRunner.withParent(parent).run(context -> {
				assertThat(context).hasNotFailed();
				assertThatThrownBy(() -> context.getBean(CUSTOM_MANAGER))
					.hasRootCauseInstanceOf(IllegalStateException.class)
					.hasStackTraceContaining(MISSING_POLICY);
			});
			assertThatThrownBy(() -> parent.getBean(CUSTOM_MANAGER)).hasRootCauseInstanceOf(IllegalStateException.class)
				.hasStackTraceContaining(MISSING_POLICY);
		});
	}

	@Test
	void runtimeTypedManagerInALazyUnguardedParentIsValidatedWhenCreated() {
		new ApplicationContextRunner().withInitializer(GLOBAL_LAZY_INITIALIZATION)
			.withUserConfiguration(RuntimeTypedPlainManagerConfiguration.class)
			.run(parent -> this.contextRunner.withParent(parent).run(context -> {
				assertThat(context).hasNotFailed();
				assertThatThrownBy(() -> context.getBean(CUSTOM_MANAGER))
					.hasRootCauseInstanceOf(IllegalStateException.class)
					.hasStackTraceContaining(MISSING_POLICY);
			}));
	}

	@Test
	void validManagersCreatedLaterInAnUnguardedParentAreAccepted() {
		for (ApplicationContextRunner parentRunner : List.of(
				new ApplicationContextRunner().withUserConfiguration(PrototypePolicyManagerConfiguration.class),
				new ApplicationContextRunner().withInitializer(GLOBAL_LAZY_INITIALIZATION)
					.withUserConfiguration(InUseFactoryProcessorConfiguration.class),
				new ApplicationContextRunner().withInitializer(GLOBAL_LAZY_INITIALIZATION)
					.withUserConfiguration(RuntimeTypedPolicyManagerConfiguration.class))) {
			parentRunner.run(parent -> this.contextRunner.withParent(parent).run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(failingToolResult(context.getBean(CUSTOM_MANAGER, ToolCallingManager.class)))
					.isEqualTo(REVIEWED_FAILURE);
			}));
		}
	}

	@Test
	void inheritedManagerValidationCreatesNoUnusedFactoryProcessor() {
		for (boolean failingUnusedFactory : new boolean[] { false, true }) {
			AtomicInteger unusedProducts = new AtomicInteger();
			parentWithUnusedFactoryProcessor(unusedProducts, failingUnusedFactory)
				.run(parent -> this.contextRunner.withParent(parent).run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(failingToolResult(context.getBean(CUSTOM_MANAGER, ToolCallingManager.class)))
						.isEqualTo(REVIEWED_FAILURE);
					assertThat(unusedProducts).hasValue(0);
				}));
		}
	}

	@Test
	void factoryProcessorCreatedBeforeAnyGuardWatchedTheParentFailsClosedWithoutCreatingOthers() {
		for (boolean failingUnusedFactory : new boolean[] { false, true }) {
			AtomicInteger unusedProducts = new AtomicInteger();
			parentWithUnusedFactoryProcessor(unusedProducts, failingUnusedFactory).run(parent -> {
				parent.getBean("customProcessor");
				this.contextRunner.withParent(parent).run(context -> {
					assertThat(context).hasNotFailed();
					assertThatThrownBy(() -> context.getBean(CUSTOM_MANAGER))
						.hasRootCauseInstanceOf(IllegalStateException.class)
						.hasStackTraceContaining(UNDECLARED_PROCESSOR);
				});
				assertThat(unusedProducts).hasValue(0);
			});
		}
	}

	@Test
	void managerSupplierThatOnlyTouchesAnUnusedProcessorFactoryCreatesNoProduct() {
		for (boolean failingUnusedFactory : new boolean[] { false, true }) {
			AtomicInteger unusedProducts = new AtomicInteger();
			new ApplicationContextRunner()
				.withBean("unusedProcessor", ProcessorFactoryBean.class,
						() -> new ProcessorFactoryBean(unusedProducts, failingUnusedFactory))
				.withInitializer(context -> ((GenericApplicationContext) context).registerBean(CUSTOM_MANAGER,
						ToolCallingManager.class, () -> {
							context.getBean("&unusedProcessor");
							return OpenRouterToolCallingManagers
								.withFailurePolicy(new OpenRouterToolExecutionExceptionProcessor(), builder -> {
								});
						}, definition -> definition.setScope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)))
				.run(parent -> this.contextRunner.withParent(parent).run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(CUSTOM_MANAGER)).isInstanceOf(OpenRouterToolFailurePolicy.class);
					assertThat(unusedProducts).hasValue(0);
				}));
		}
	}

	@Test
	void rawSingletonProcessorThatIsAlsoAFactoryBeanIsAccepted() {
		new ApplicationContextRunner().withInitializer(context -> {
			GenericApplicationContext parent = (GenericApplicationContext) context;
			parent.getBeanFactory().registerSingleton("dualRoleProcessor", new DualRoleProcessorFactoryBean());
			parent.registerBean(CUSTOM_MANAGER, ToolCallingManager.class, () -> OpenRouterToolCallingManagers
				.withFailurePolicy((ToolExecutionExceptionProcessor) parent.getBean("&dualRoleProcessor"), builder -> {
				}), definition -> definition.setScope(ConfigurableBeanFactory.SCOPE_PROTOTYPE));
		}).run(parent -> this.contextRunner.withParent(parent).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(failingToolResult(context.getBean(CUSTOM_MANAGER, ToolCallingManager.class)))
				.isEqualTo(REVIEWED_FAILURE);
		}));
	}

	@Test
	void factoryProcessorThatTheParentAlreadyCreatedFailsClosed() {
		new ApplicationContextRunner().withUserConfiguration(InUseFactoryProcessorConfiguration.class)
			.run(parent -> this.contextRunner.withParent(parent)
				.withUserConfiguration(CustomManagerUsingAutoProcessorConfiguration.class)
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).hasStackTraceContaining(UNDECLARED_PROCESSOR);
				}));
	}

	@Test
	void factoryProcessorInTheSameContextIsAccepted() {
		for (ApplicationContextRunner runner : List.of(this.contextRunner,
				this.contextRunner.withInitializer(GLOBAL_LAZY_INITIALIZATION))) {
			runner.withUserConfiguration(FactoryProcessorConfiguration.class).run(context -> {
				assertThat(context).hasNotFailed().doesNotHaveBean(OpenRouterToolExecutionExceptionProcessor.class);
				assertThat(failingToolResult(context.getBean(CUSTOM_MANAGER, ToolCallingManager.class)))
					.isEqualTo(REVIEWED_FAILURE);
			});
		}
	}

	@Test
	void parentFactoryProcessorDoesNotApproveAManagerThatInstallsAnotherProcessor() {
		new ApplicationContextRunner().withUserConfiguration(FactoryProcessorNotInstalledConfiguration.class)
			.run(parent -> this.contextRunner.withParent(parent).run(context -> {
				assertThat(context).hasNotFailed();
				assertThatThrownBy(() -> context.getBean(CUSTOM_MANAGER))
					.hasRootCauseInstanceOf(IllegalStateException.class)
					.hasStackTraceContaining(UNDECLARED_PROCESSOR);
			}));
	}

	@Test
	void uncachedParentFactoryProcessorIsNotCreatedAgainForValidation() {
		for (boolean smartFactory : new boolean[] { true, false }) {
			AtomicInteger products = new AtomicInteger();
			new ApplicationContextRunner()
				.withBean("customProcessor", ProcessorFactoryBean.class,
						() -> smartFactory ? new SmartProcessorFactoryBean(products)
								: new PrototypeProcessorFactoryBean(products))
				.run(parent -> this.contextRunner.withParent(parent)
					.withUserConfiguration(CustomManagerUsingAutoProcessorConfiguration.class)
					.run(context -> {
						assertThat(context).hasFailed();
						assertThat(context.getStartupFailure()).hasStackTraceContaining(UNDECLARED_PROCESSOR);
						assertThat(products).hasValue(1);
					}));
		}
	}

	@Test
	void parentFactoryProcessorReachedThroughAnotherBeanFailsClosed() {
		new ApplicationContextRunner().withUserConfiguration(IndirectFactoryProcessorConfiguration.class)
			.run(parent -> this.contextRunner.withParent(parent).run(context -> {
				assertThat(context).hasNotFailed();
				assertThatThrownBy(() -> context.getBean(CUSTOM_MANAGER))
					.hasRootCauseInstanceOf(IllegalStateException.class)
					.hasStackTraceContaining(UNDECLARED_PROCESSOR);
			}));
	}

	@Test
	void eachUnguardedAncestorGetsOneGuardThatRetainsNoChild() {
		new ApplicationContextRunner()
			.run(grandparent -> new ApplicationContextRunner().withParent(grandparent).run(parent -> {
				List<WeakReference<ApplicationContext>> children = new ArrayList<>();
				for (int child = 0; child < 2; child++) {
					this.contextRunner.withParent(parent).run(context -> {
						assertThat(context).hasNotFailed();
						children.add(new WeakReference<>(context.getSourceApplicationContext()));
					});
				}
				assertThat(guards(parent)).hasSize(1);
				assertThat(guards(grandparent)).hasSize(1);
				for (WeakReference<ApplicationContext> child : children) {
					assertCollected(child);
				}
			}));
	}

	@Test
	void processorDeclaredInAParentContextCanBeInstalledByAChildManager() {
		ToolExecutionExceptionProcessor processor = exception -> REVIEWED_FAILURE;
		new ApplicationContextRunner().withBean(ToolExecutionExceptionProcessor.class, () -> processor).run(parent -> {
			this.contextRunner.withParent(parent)
				.withUserConfiguration(CustomManagerUsingAutoProcessorConfiguration.class)
				.run(context -> {
					assertThat(context).hasNotFailed().doesNotHaveBean(OpenRouterToolExecutionExceptionProcessor.class);
					assertThat(failingToolResult(context.getBean(ToolCallingManager.class)))
						.isEqualTo(REVIEWED_FAILURE);
				});
			this.contextRunner.withParent(parent)
				.withBean(ToolCallingManager.class, () -> OpenRouterToolCallingManagers
					.withFailurePolicy(exception -> exception.getMessage(), builder -> {
					}))
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).hasStackTraceContaining(UNDECLARED_PROCESSOR);
				});
		});
	}

	@Test
	void autoConfiguredParentManagerKeepsTheParentProcessor() {
		new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class))
			.withBean(ToolExecutionExceptionProcessor.class, () -> exception -> REVIEWED_FAILURE)
			.run(parent -> this.contextRunner.withParent(parent).run(context -> {
				assertThat(context).hasNotFailed().doesNotHaveBean(OpenRouterToolExecutionExceptionProcessor.class);
				assertThat(failingToolResult(context.getBean(ToolCallingManager.class))).isEqualTo(REVIEWED_FAILURE);
			}));
	}

	@Test
	void parentWithItsOwnGuardRemainsResponsibleForItsManagers() {
		this.contextRunner.withUserConfiguration(CustomManagerAndPrototypeProcessorConfiguration.class).run(parent -> {
			this.contextRunner.withParent(parent).run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(failingToolResult(context.getBean(ToolCallingManager.class))).isEqualTo(REVIEWED_FAILURE);
			});
			assertThat(guards(parent)).containsExactly(parent.getBean(OpenRouterToolCallingManagerGuard.class));
		});
	}

	@Test
	void runtimeTypedManagerIsValidatedWhenGlobalLazyInitializationCreatesIt() {
		this.contextRunner.withUserConfiguration(RuntimeTypedPlainManagerConfiguration.class).run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasStackTraceContaining(MISSING_POLICY);
		});
		ApplicationContextRunner lazyRunner = this.contextRunner.withInitializer(GLOBAL_LAZY_INITIALIZATION);
		lazyRunner.withUserConfiguration(RuntimeTypedPlainManagerConfiguration.class).run(context -> {
			assertThat(context).hasNotFailed();
			assertThatThrownBy(() -> context.getBean(CUSTOM_MANAGER))
				.hasRootCauseInstanceOf(IllegalStateException.class)
				.hasStackTraceContaining(MISSING_POLICY);
		});
		lazyRunner.withUserConfiguration(RuntimeTypedPolicyManagerConfiguration.class).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(failingToolResult(context.getBean(CUSTOM_MANAGER, ToolCallingManager.class)))
				.isEqualTo(REVIEWED_FAILURE);
		});
	}

	@Test
	void springThrowOnErrorSettingKeepsItsThrowingProcessor() {
		this.contextRunner.withPropertyValues("spring.ai.tools.throw-exception-on-error=true").run(context -> {
			assertThat(context).doesNotHaveBean(OpenRouterToolExecutionExceptionProcessor.class)
				.hasSingleBean(DefaultToolExecutionExceptionProcessor.class);
			assertThatThrownBy(() -> failingToolResult(context.getBean(ToolCallingManager.class)))
				.isInstanceOf(ToolExecutionException.class);
		});
	}

	@Test
	void anotherChatProviderKeepsSpringAIDefaults() {
		this.contextRunner.withPropertyValues("spring.ai.model.chat=other").run(context -> {
			assertThat(context).doesNotHaveBean(OpenRouterToolExecutionExceptionProcessor.class)
				.hasSingleBean(DefaultToolExecutionExceptionProcessor.class);
		});
	}

	/**
	 * Run one failing tool call through the manager and return the tool result that would
	 * be sent to the provider.
	 */
	private static String failingToolResult(ToolCallingManager manager) {
		ToolCallback failingTool = FunctionToolCallback.builder("lookup", (Map<String, Object> input) -> {
			throw new IllegalStateException("Could not connect to " + SECRET);
		}).description("Always fails").inputType(Map.class).build();
		Prompt prompt = new Prompt(List.of(new UserMessage("go")),
				ToolCallingChatOptions.builder().toolCallbacks(failingTool).build());
		AssistantMessage toolCall = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "lookup", "{}")))
			.build();
		ToolExecutionResult result = manager.executeToolCalls(prompt,
				new ChatResponse(List.of(new Generation(toolCall))));
		List<Message> history = result.conversationHistory();
		ToolResponseMessage response = (ToolResponseMessage) history.get(history.size() - 1);
		return response.getResponses().get(0).responseData();
	}

	private static ApplicationContextRunner parentWithUnusedFactoryProcessor(AtomicInteger unusedProducts,
			boolean unusedFactoryFails) {
		return new ApplicationContextRunner()
			.withBean("unusedProcessor", ProcessorFactoryBean.class,
					() -> new ProcessorFactoryBean(unusedProducts, unusedFactoryFails))
			.withBean("customProcessor", ProcessorFactoryBean.class,
					() -> new ProcessorFactoryBean(new AtomicInteger(), false))
			.withInitializer(
					context -> ((GenericApplicationContext) context).registerBean(CUSTOM_MANAGER,
							ToolCallingManager.class,
							() -> OpenRouterToolCallingManagers.withFailurePolicy(
									context.getBean("customProcessor", ToolExecutionExceptionProcessor.class),
									builder -> {
									}),
							definition -> definition.setScope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)));
	}

	private static List<OpenRouterToolCallingManagerGuard> guards(ConfigurableApplicationContext context) {
		return ((AbstractBeanFactory) context.getBeanFactory()).getBeanPostProcessors()
			.stream()
			.filter(OpenRouterToolCallingManagerGuard.class::isInstance)
			.map(OpenRouterToolCallingManagerGuard.class::cast)
			.toList();
	}

	private static void assertCollected(WeakReference<?> reference) throws InterruptedException {
		for (int attempt = 0; attempt < 50 && reference.get() != null; attempt++) {
			System.gc();
			Thread.sleep(20);
		}
		assertThat(reference.get()).isNull();
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
	static class ThrowingProcessorConfiguration {

		@Bean
		ToolExecutionExceptionProcessor throwingProcessor() {
			return DefaultToolExecutionExceptionProcessor.builder().alwaysThrow(true).build();
		}

		@Bean
		ToolCallingManager customToolCallingManager(ToolExecutionExceptionProcessor processor) {
			return OpenRouterToolCallingManagers.withFailurePolicy(processor, builder -> {
			});
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
			return OpenRouterToolCallingManagers.withFailurePolicy(processor, builder -> {
			});
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class CustomManagerUsingAutoProcessorConfiguration {

		@Bean
		ToolCallingManager customToolCallingManager(ToolExecutionExceptionProcessor processor) {
			return OpenRouterToolCallingManagers.withFailurePolicy(processor, builder -> {
			});
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
			return OpenRouterToolCallingManagers.withFailurePolicy(exception -> exception.getMessage(), builder -> {
			});
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
	static class PrototypePolicyManagerConfiguration {

		@Bean
		ToolExecutionExceptionProcessor customProcessor() {
			return exception -> REVIEWED_FAILURE;
		}

		@Bean
		@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
		ToolCallingManager customToolCallingManager(ToolExecutionExceptionProcessor processor) {
			return OpenRouterToolCallingManagers.withFailurePolicy(processor, builder -> {
			});
		}

	}

	private static final class DualRoleProcessorFactoryBean
			implements FactoryBean<String>, ToolExecutionExceptionProcessor {

		@Override
		public String getObject() {
			throw new IllegalStateException("The factory product must not be requested");
		}

		@Override
		public Class<?> getObjectType() {
			return String.class;
		}

		@Override
		public String process(ToolExecutionException exception) {
			return REVIEWED_FAILURE;
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class InUseFactoryProcessorConfiguration {

		@Bean
		FactoryBean<?> customProcessor() {
			return new RuntimeTypedFactoryBean((ToolExecutionExceptionProcessor) exception -> REVIEWED_FAILURE);
		}

		@Bean
		List<ToolExecutionExceptionProcessor> processorUsers(ToolExecutionExceptionProcessor processor) {
			return List.of(processor);
		}

		@Bean
		@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
		ToolCallingManager customToolCallingManager(ToolExecutionExceptionProcessor processor) {
			return OpenRouterToolCallingManagers.withFailurePolicy(processor, builder -> {
			});
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class FactoryProcessorConfiguration {

		@Bean
		FactoryBean<ToolExecutionExceptionProcessor> customProcessor() {
			return new ProcessorFactoryBean(new AtomicInteger(), false);
		}

		@Bean
		@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
		ToolCallingManager customToolCallingManager(ToolExecutionExceptionProcessor processor) {
			return OpenRouterToolCallingManagers.withFailurePolicy(processor, builder -> {
			});
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class FactoryProcessorNotInstalledConfiguration {

		@Bean
		FactoryBean<ToolExecutionExceptionProcessor> customProcessor() {
			return new ProcessorFactoryBean(new AtomicInteger(), false);
		}

		@Bean
		@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
		ToolCallingManager customToolCallingManager(ToolExecutionExceptionProcessor processor) {
			Objects.requireNonNull(processor, "processor");
			return OpenRouterToolCallingManagers.withFailurePolicy(exception -> exception.getMessage(), builder -> {
			});
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class IndirectFactoryProcessorConfiguration {

		@Bean
		FactoryBean<ToolExecutionExceptionProcessor> customProcessor() {
			return new ProcessorFactoryBean(new AtomicInteger(), false);
		}

		@Bean
		Supplier<ToolExecutionExceptionProcessor> processorLookup(ToolExecutionExceptionProcessor processor) {
			return () -> processor;
		}

		@Bean
		@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
		ToolCallingManager customToolCallingManager(Supplier<ToolExecutionExceptionProcessor> processorLookup) {
			return OpenRouterToolCallingManagers.withFailurePolicy(processorLookup.get(), builder -> {
			});
		}

	}

	private static class ProcessorFactoryBean implements FactoryBean<ToolExecutionExceptionProcessor> {

		private final ToolExecutionExceptionProcessor processor = exception -> REVIEWED_FAILURE;

		private final AtomicInteger products;

		private final boolean fails;

		ProcessorFactoryBean(AtomicInteger products, boolean fails) {
			this.products = products;
			this.fails = fails;
		}

		@Override
		public ToolExecutionExceptionProcessor getObject() {
			this.products.incrementAndGet();
			if (this.fails) {
				throw new IllegalStateException("Synthetic processor factory failure");
			}
			return this.processor;
		}

		@Override
		public Class<?> getObjectType() {
			return ToolExecutionExceptionProcessor.class;
		}

	}

	private static final class SmartProcessorFactoryBean extends ProcessorFactoryBean
			implements SmartFactoryBean<ToolExecutionExceptionProcessor> {

		SmartProcessorFactoryBean(AtomicInteger products) {
			super(products, false);
		}

	}

	private static final class PrototypeProcessorFactoryBean extends ProcessorFactoryBean {

		PrototypeProcessorFactoryBean(AtomicInteger products) {
			super(products, false);
		}

		@Override
		public boolean isSingleton() {
			return false;
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
			return OpenRouterToolCallingManagers.withFailurePolicy(processor, builder -> {
			});
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class RuntimeTypedPlainManagerConfiguration {

		@Bean
		FactoryBean<?> customToolCallingManager() {
			return new RuntimeTypedFactoryBean(ToolCallingManager.builder().build());
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class RuntimeTypedPolicyManagerConfiguration {

		@Bean
		ToolExecutionExceptionProcessor customProcessor() {
			return exception -> REVIEWED_FAILURE;
		}

		@Bean
		FactoryBean<?> customToolCallingManager(ToolExecutionExceptionProcessor processor) {
			return new RuntimeTypedFactoryBean(OpenRouterToolCallingManagers.withFailurePolicy(processor, builder -> {
			}));
		}

	}

	private static final class RuntimeTypedFactoryBean implements FactoryBean<Object> {

		private final Object object;

		RuntimeTypedFactoryBean(Object object) {
			this.object = object;
		}

		@Override
		public Object getObject() {
			return this.object;
		}

		@Override
		public Class<?> getObjectType() {
			return this.object.getClass();
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
			return OpenRouterToolCallingManagers.withFailurePolicy(processor, builder -> {
			});
		}

	}

}
