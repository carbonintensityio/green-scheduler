package io.carbonintensity.scheduler.quarkus.runtime;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Map;
import java.util.concurrent.CompletionStage;

import io.carbonintensity.scheduler.ScheduledExecution;
import io.carbonintensity.scheduler.runtime.ScheduledInvoker;

/**
 * Samples the executing thread's CPU time consumed by one job invocation - synchronous, local and cheap to measure,
 * unlike the retrospective carbon-impact figures, which is why it is always sampled for a
 * {@code @GreenObserved}-annotated job regardless of {@code carbonImpact}.
 * <p>
 * Best-effort by nature: if the JVM does not support per-thread CPU time, or the invocation completes on a
 * different thread than the one it started on (for example a {@code @NonBlocking} method resuming on the event loop
 * after an asynchronous gap), no sample is recorded for that run rather than a misleading one. The last valid sample
 * per job identity is kept, read by {@link io.carbonintensity.scheduler.quarkus.runtime.metrics} for the
 * {@code green.scheduler.job.cpu_time} gauge - this class itself has no Micrometer dependency, so it stays safe to
 * load regardless of whether Micrometer is on the classpath.
 */
final class CpuTimeSamplingInvoker implements ScheduledInvoker {

    private final ScheduledInvoker delegate;
    private final Map<String, Double> lastCpuTimeSecondsByIdentity;
    private final ThreadMXBean threadMXBean;

    CpuTimeSamplingInvoker(ScheduledInvoker delegate, Map<String, Double> lastCpuTimeSecondsByIdentity) {
        this.delegate = delegate;
        this.lastCpuTimeSecondsByIdentity = lastCpuTimeSecondsByIdentity;
        this.threadMXBean = ManagementFactory.getThreadMXBean();
    }

    @Override
    public CompletionStage<Void> invoke(ScheduledExecution execution) throws Exception {
        Thread startThread = Thread.currentThread();
        long startCpuNanos = threadMXBean.isCurrentThreadCpuTimeSupported() ? threadMXBean.getCurrentThreadCpuTime() : -1;
        return delegate.invoke(execution).whenComplete((result, throwable) -> {
            if (startCpuNanos < 0 || Thread.currentThread() != startThread) {
                return;
            }
            long endCpuNanos = threadMXBean.getCurrentThreadCpuTime();
            if (endCpuNanos >= startCpuNanos) {
                double seconds = (endCpuNanos - startCpuNanos) / 1_000_000_000.0;
                lastCpuTimeSecondsByIdentity.put(execution.getTrigger().getId(), seconds);
            }
        });
    }

    @Override
    public boolean isBlocking() {
        return delegate.isBlocking();
    }
}
