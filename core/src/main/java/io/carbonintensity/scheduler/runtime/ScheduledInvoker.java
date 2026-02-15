package io.carbonintensity.scheduler.runtime;

import java.util.concurrent.CompletionStage;

import io.carbonintensity.scheduler.ScheduledExecution;

/**
 * Interface for invoking the scheduledTask
 *
 * @see io.carbonintensity.scheduler.runtime.SimpleScheduler.ScheduledTask
 */
public interface ScheduledInvoker {

    /**
     * @param execution the execution to invoke
     *
     * @return the result
     * @throws Exception when the invocation fails
     */
    CompletionStage<Void> invoke(ScheduledExecution execution) throws Exception;

    /**
     * A blocking invoker is executed on the main executor for blocking tasks.
     * A non-blocking invoker is executed on the event loop.
     *
     * @return {@code true} if the scheduled method is blocking, {@code false} otherwise
     */
    default boolean isBlocking() {
        return true;
    }
}
