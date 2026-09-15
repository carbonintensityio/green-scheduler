package io.carbonintensity.scheduler.spring.observability;

import java.lang.management.ThreadMXBean;

/**
 * A single job run's wall-clock duration and CPU time, as sampled by {@link CpuTimeSamplingInvoker}.
 *
 * @param elapsedNanos wall-clock duration of the invocation, in nanoseconds
 * @param cpuNanos {@link ThreadMXBean} CPU time consumed by the invoking thread during the invocation, in
 *        nanoseconds, or {@code -1} if the JVM does not support current-thread CPU time measurement
 */
record ExecutionSample(long elapsedNanos, long cpuNanos) {
}
