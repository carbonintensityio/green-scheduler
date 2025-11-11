package io.carbonintensity.scheduler.spring.factory;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import io.carbonintensity.scheduler.ScheduledExecution;
import io.carbonintensity.scheduler.runtime.ScheduledInvoker;

/**
 * {@link ScheduledInvoker} method invoker implementation.
 * This class simply invokes {@link io.carbonintensity.scheduler.GreenScheduled} annotated method.
 */
public class MethodScheduledInvoker implements ScheduledInvoker {

    private final Object bean;
    private final Method scheduledMethod;

    public MethodScheduledInvoker(Object bean, Method scheduledMethod) {
        this.bean = bean;
        this.scheduledMethod = scheduledMethod;
    }

    /**
     * Invokes spring bean method.
     *
     * @param scheduledExecution execution context
     * @return CompletionStage with null value when the method has been invoked successfully otherwise returns a caught
     *         exception.
     */
    @Override
    public CompletionStage<Void> invoke(ScheduledExecution scheduledExecution) {
        try {
            scheduledMethod.invoke(bean);
            return CompletableFuture.completedStage(null);
        } catch (Exception e) {
            return CompletableFuture.failedStage(e);
        }
    }
}
