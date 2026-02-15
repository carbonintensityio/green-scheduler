package io.carbonintensity.scheduler.runtime;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import io.carbonintensity.scheduler.ScheduledExecution;

/**
 * Abstract base class for delegating invocation of scheduled tasks.
 *
 * @see ScheduledInvoker
 * @see ScheduledExecution
 * @see CompletionStage
 */
abstract class DelegateInvoker implements ScheduledInvoker {

    protected final ScheduledInvoker delegate;

    protected DelegateInvoker(ScheduledInvoker delegate) {
        this.delegate = delegate;
    }

    protected CompletionStage<Void> invokeDelegate(ScheduledExecution execution) {
        try {
            return delegate.invoke(execution);
        } catch (Exception e) {
            return CompletableFuture.failedStage(e);
        }
    }

    @Override
    public boolean isBlocking() {
        return delegate.isBlocking();
    }
}
