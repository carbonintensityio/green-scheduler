package io.carbonintensity.scheduler;

/**
 * Represents a strategy to handle concurrent execution of a scheduled method.
 * <p>
 * Note that this strategy only considers executions within the same application instance. It's not intended to work
 * across the cluster.
 */
public enum ConcurrentExecution {

    /**
     * The scheduled method can be executed concurrently, i.e., it is executed every time the trigger is fired.
     */
    PROCEED,

    /**
     * The scheduled method is never executed concurrently, i.e., a method execution is skipped until the previous
     * invocation completes.
     */
    SKIP,

}
