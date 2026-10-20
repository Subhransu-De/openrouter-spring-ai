package de.subhransu.openrouter.springai.autoconfigure;

import org.jspecify.annotations.Nullable;
import de.subhransu.openrouter.springai.chat.OpenRouterToolExecutionExceptionProcessor;
import de.subhransu.openrouter.springai.chat.OpenRouterToolFailurePolicy;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.springframework.aop.scope.ScopedObject;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;

final class OpenRouterToolCallingManagerGuard implements BeanPostProcessor, SmartInitializingSingleton {

	private static final String SPRING_TOOL_CALLING_AUTO_CONFIGURATION = "org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration";

	private final ConfigurableListableBeanFactory beanFactory;

	private final Set<ToolExecutionExceptionProcessor> singletonDeclaredProcessors = Collections
		.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

	private final ReferenceQueue<ToolExecutionExceptionProcessor> transientProcessorQueue = new ReferenceQueue<>();

	private final Set<IdentityWeakReference<ToolExecutionExceptionProcessor>> transientDeclaredProcessors = new HashSet<>();

	private volatile boolean singletonsValidated;

	OpenRouterToolCallingManagerGuard(ConfigurableListableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
		Arrays.stream(beanFactory.getSingletonNames())
			.map(beanFactory::getSingleton)
			.filter(ToolExecutionExceptionProcessor.class::isInstance)
			.map(ToolExecutionExceptionProcessor.class::cast)
			.forEach(this.singletonDeclaredProcessors::add);
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		if (bean instanceof ToolExecutionExceptionProcessor processor) {
			rememberProcessor(beanName, processor);
		}
		if (bean instanceof ToolCallingManager manager && !(bean instanceof ScopedObject)
				&& (this.singletonsValidated || !this.beanFactory.isSingleton(beanName))
				&& isCustomManager(this.beanFactory, beanName)) {
			validate(manager);
		}
		return bean;
	}

	@Override
	public void afterSingletonsInstantiated() {
		this.singletonDeclaredProcessors
			.addAll(this.beanFactory.getBeansOfType(ToolExecutionExceptionProcessor.class, false, false).values());
		validateCustomManagers(this.beanFactory);
		for (ConfigurableListableBeanFactory ancestor : ancestors()) {
			if (ancestor.getBeanNamesForType(OpenRouterToolCallingManagerGuard.class, false, false).length > 0) {
				break;
			}
			validateCustomManagers(ancestor);
		}
		this.singletonsValidated = true;
	}

	private void validateCustomManagers(ConfigurableListableBeanFactory factory) {
		factory.getBeansOfType(ToolCallingManager.class, false, false).forEach((name, manager) -> {
			if (!(manager instanceof ScopedObject) && isCustomManager(factory, name)) {
				validate(manager);
			}
		});
	}

	private List<ConfigurableListableBeanFactory> ancestors() {
		List<ConfigurableListableBeanFactory> ancestors = new ArrayList<>();
		BeanFactory parent = this.beanFactory.getParentBeanFactory();
		while (parent instanceof ConfigurableListableBeanFactory ancestor) {
			ancestors.add(ancestor);
			parent = ancestor.getParentBeanFactory();
		}
		return ancestors;
	}

	private void rememberProcessor(String beanName, ToolExecutionExceptionProcessor processor) {
		if (this.beanFactory.isSingleton(beanName)) {
			this.singletonDeclaredProcessors.add(processor);
			return;
		}
		synchronized (this.transientDeclaredProcessors) {
			purgeCollectedProcessors();
			this.transientDeclaredProcessors.add(new IdentityWeakReference<>(processor, this.transientProcessorQueue));
		}
	}

	private static boolean isCustomManager(ConfigurableListableBeanFactory factory, String beanName) {
		if (!factory.containsBeanDefinition(beanName)) {
			return true;
		}
		BeanDefinition definition = factory.getBeanDefinition(beanName);
		return !SPRING_TOOL_CALLING_AUTO_CONFIGURATION.equals(definition.getFactoryBeanName());
	}

	private void validate(ToolCallingManager manager) {
		if (!(manager instanceof OpenRouterToolFailurePolicy policy)) {
			throw missingPolicy(manager);
		}
		ToolExecutionExceptionProcessor actualProcessor = policy.toolExecutionExceptionProcessor();
		if (actualProcessor == null || !(isDeclaredProcessor(actualProcessor)
				|| actualProcessor instanceof OpenRouterToolExecutionExceptionProcessor)) {
			throw undeclaredProcessor(manager);
		}
	}

	private boolean isDeclaredProcessor(ToolExecutionExceptionProcessor processor) {
		if (this.singletonDeclaredProcessors.contains(processor)) {
			return true;
		}
		synchronized (this.transientDeclaredProcessors) {
			purgeCollectedProcessors();
			if (this.transientDeclaredProcessors.contains(new IdentityWeakReference<>(processor))) {
				return true;
			}
		}
		return isAncestorSingleton(processor);
	}

	private boolean isAncestorSingleton(ToolExecutionExceptionProcessor processor) {
		for (ConfigurableListableBeanFactory ancestor : ancestors()) {
			for (String name : ancestor.getBeanNamesForType(ToolExecutionExceptionProcessor.class, false, false)) {
				if (ancestor.containsSingleton(name) && ancestor.getBean(name) == processor) {
					return true;
				}
			}
		}
		return false;
	}

	private void purgeCollectedProcessors() {
		Reference<? extends ToolExecutionExceptionProcessor> reference;
		while ((reference = this.transientProcessorQueue.poll()) != null) {
			this.transientDeclaredProcessors.remove(reference);
		}
	}

	private static IllegalStateException missingPolicy(ToolCallingManager manager) {
		return new IllegalStateException("Custom ToolCallingManager " + manager.getClass().getName()
				+ " does not declare a provider-visible tool failure policy. Build it with "
				+ "OpenRouterToolCallingManagers.withFailurePolicy(...), implement OpenRouterToolFailurePolicy, or "
				+ "explicitly set spring.ai.openrouter.chat.allow-unsafe-tool-failure-results=true after auditing its "
				+ "behavior.");
	}

	private static IllegalStateException undeclaredProcessor(ToolCallingManager manager) {
		return new IllegalStateException("Custom ToolCallingManager " + manager.getClass().getName()
				+ " uses a ToolExecutionExceptionProcessor that is not an application-declared bean or an "
				+ "OpenRouterToolExecutionExceptionProcessor. Declare the processor as a bean and pass that bean to "
				+ "the manager, or explicitly set spring.ai.openrouter.chat.allow-unsafe-tool-failure-results=true "
				+ "after auditing its behavior.");
	}

	private static final class IdentityWeakReference<T> extends WeakReference<T> {

		private final int identityHashCode;

		IdentityWeakReference(T referent) {
			super(referent);
			this.identityHashCode = System.identityHashCode(referent);
		}

		IdentityWeakReference(T referent, ReferenceQueue<T> queue) {
			super(referent, queue);
			this.identityHashCode = System.identityHashCode(referent);
		}

		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other) {
				return true;
			}
			return other instanceof IdentityWeakReference<?> reference && get() != null && get() == reference.get();
		}

		@Override
		public int hashCode() {
			return this.identityHashCode;
		}

	}

}
