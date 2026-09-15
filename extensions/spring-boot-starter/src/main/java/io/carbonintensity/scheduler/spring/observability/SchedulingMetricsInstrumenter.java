package io.carbonintensity.scheduler.spring.observability;

import io.carbonintensity.scheduler.Scheduler;
import io.carbonintensity.scheduler.Trigger;
import io.carbonintensity.scheduler.runtime.MutableScheduledMethod;
import io.carbonintensity.scheduler.spring.GreenSchedulerAutoConfiguration;

/**
 * Bridges {@code @GreenObserved} job scheduling to a metrics backend, without
 * {@link GreenSchedulerAutoConfiguration} ever needing a compile/load-time reference to the metrics backend's own
 * types - Micrometer's, in the only implementation today: {@link GreenSchedulerMetricsBinder}.
 * <p>
 * This keeps Micrometer genuinely optional: if it is absent from the consumer's classpath, no implementation bean
 * exists, {@link GreenSchedulerAutoConfiguration} resolves an {@code @Autowired(required = false)} field of this
 * interface type to {@code null}, and neither it nor the JVM classloader ever needs to resolve a single Micrometer
 * class.
 */
public interface SchedulingMetricsInstrumenter {

    /**
     * Called once per scheduled method, right before it is handed to {@link Scheduler}, so implementations can wrap
     * {@link MutableScheduledMethod#getInvoker()} (e.g. to bracket CPU-time sampling around the actual invocation).
     * <p>
     * This only has an effect if called before scheduling: core fixes the invoker chain for each
     * {@link Trigger} at schedule time.
     *
     * @param method the method about to be scheduled
     */
    void instrumentInvoker(MutableScheduledMethod method);

    /**
     * Called once per scheduled method, right after it was handed to {@code scheduler}, so implementations can look
     * up the resulting {@link Trigger}(s) - one per {@code @GreenScheduled} entry - via
     * {@link Scheduler#getScheduledJob(String)} and bind metrics to them.
     *
     * @param method the method that was just scheduled
     * @param scheduler the scheduler it was scheduled on
     */
    void bindMetrics(MutableScheduledMethod method, Scheduler scheduler);
}
