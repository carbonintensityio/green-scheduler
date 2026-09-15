package io.carbonintensity.scheduler.spring.observability;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletionStage;

import io.carbonintensity.scheduler.ScheduledExecution;
import io.carbonintensity.scheduler.runtime.ScheduledInvoker;

/**
 * Wraps a job's raw invoker to bracket each run with wall-clock and {@link ThreadMXBean} CPU-time sampling, stashing
 * the result per trigger identity for the {@code execution}/{@code drift}/{@code cpu_time} metrics to pick up.
 * <p>
 * Wraps {@code ScheduledMethod#getInvoker()} directly - the innermost, raw invoker - so this bracket covers only the
 * actual business-method invocation, not the skip-predicate/concurrent-execution/status wrapping core layers on top
 * of it afterward (see {@code SimpleScheduler#initInvoker}).
 * <p>
 * For Spring, {@code MethodScheduledInvoker} invokes the business method synchronously via reflection and returns an
 * already-completed stage, so sampling taken immediately before and after the {@link #invoke(ScheduledExecution)}
 * call accurately brackets the real invocation. This would under-measure a delegate whose returned stage completes
 * asynchronously off-thread, but Spring's {@code @GreenScheduled} methods are synchronous/void today.
 * <p>
 * Wall-clock timing uses the scheduler's own injected {@link Clock} rather than {@code System.nanoTime()}, trading
 * some precision (the {@link Clock} in use is typically millisecond-granular) for staying consistent with the rest
 * of this extension's "never call {@code Instant.now()} directly" rule and for deterministic testability.
 */
final class CpuTimeSamplingInvoker implements ScheduledInvoker {

    private static final ThreadMXBean THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();

    private final ScheduledInvoker delegate;
    private final Clock clock;
    private final Map<String, ExecutionSample> lastExecutionByIdentity;

    CpuTimeSamplingInvoker(ScheduledInvoker delegate, Clock clock, Map<String, ExecutionSample> lastExecutionByIdentity) {
        this.delegate = delegate;
        this.clock = clock;
        this.lastExecutionByIdentity = lastExecutionByIdentity;
    }

    @Override
    public CompletionStage<Void> invoke(ScheduledExecution execution) throws Exception {
        String identity = execution.getTrigger().getId();
        boolean cpuTimeSupported = THREAD_MX_BEAN.isCurrentThreadCpuTimeSupported();
        Instant start = clock.instant();
        long startCpuNanos = cpuTimeSupported ? THREAD_MX_BEAN.getCurrentThreadCpuTime() : -1;
        try {
            return delegate.invoke(execution);
        } finally {
            long elapsedNanos = Duration.between(start, clock.instant()).toNanos();
            long cpuNanos = cpuTimeSupported ? THREAD_MX_BEAN.getCurrentThreadCpuTime() - startCpuNanos : -1;
            lastExecutionByIdentity.put(identity, new ExecutionSample(elapsedNanos, cpuNanos));
        }
    }

    @Override
    public boolean isBlocking() {
        return delegate.isBlocking();
    }
}
