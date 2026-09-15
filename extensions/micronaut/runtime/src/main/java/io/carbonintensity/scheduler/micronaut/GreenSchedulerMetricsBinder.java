package io.carbonintensity.scheduler.micronaut;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.inject.Singleton;

import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.ScheduledExecution;
import io.carbonintensity.scheduler.Scheduler;
import io.carbonintensity.scheduler.Trigger;
import io.carbonintensity.scheduler.observability.CarbonImpactResult;
import io.carbonintensity.scheduler.observability.GreenObserved;
import io.carbonintensity.scheduler.runtime.ScheduledInvoker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micronaut.context.annotation.Requires;

/**
 * Exports the {@code green.scheduler.job.*} Micrometer metrics (see CIIO-282) for every {@code @GreenScheduled}
 * job that also carries {@link GreenObserved}.
 * <p>
 * Only active when a {@link MeterRegistry} bean is available, i.e. when the application has added Micrometer
 * (typically {@code micronaut-micrometer}) itself - {@code green-scheduler-micronaut} never forces it onto the
 * classpath (the {@code micrometer-core} dependency is declared {@code optional}). Without a {@link MeterRegistry}
 * bean, this class is never instantiated and {@link GreenScheduledMethodProcessor} falls back to scheduling jobs
 * without any metrics wiring.
 * <p>
 * Fire-time/status gauges are bound directly to a job's {@link Trigger} using Micrometer's function-gauge pattern,
 * so no separate polling thread is needed. Execution/drift/skipped counters and timers, and the carbon-impact
 * distribution summaries, are recorded from this class acting as a {@link Scheduler.EventListener}.
 */
@Singleton
@Requires(beans = MeterRegistry.class)
class GreenSchedulerMetricsBinder implements Scheduler.EventListener {

    private static final String METRIC_PREFIX = "green.scheduler.job.";
    private static final String TAG_IDENTITY = "identity";
    private static final String TAG_STRATEGY = "strategy";
    private static final String TAG_ZONE = "zone";
    private static final String TAG_INSTANCE = "instance";
    private static final String TAG_OUTCOME = "outcome";
    private static final String TAG_REASON = "reason";

    /**
     * The exact detail message {@code SkipConcurrentExecutionInvoker} passes to
     * {@code Events.fireJobExecutionSkipped} - core exposes no dedicated skip-reason type, so this string is the
     * only signal available to distinguish a concurrency skip from a {@code SkipPredicate} skip (whose detail is
     * the predicate's class name instead). Fragile by nature: if core ever changes this literal, this mapping
     * silently falls back to {@code skip_predicate} for concurrency skips too.
     */
    private static final String CONCURRENT_EXECUTION_SKIP_DETAIL = "The scheduled method should not be executed concurrently";

    private static final ThreadMXBean THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();

    /**
     * A lightweight, best-effort instance/hostname identifier for the {@code instance} tag - telling instances
     * apart on a dashboard, not a coordination mechanism (which instance is "leader" is explicitly out of scope,
     * see CIIO-282). Resolved once per JVM.
     */
    private static final String INSTANCE_ID = resolveInstanceId();

    private final MeterRegistry registry;
    private final Map<String, ObservedJob> observedJobs = new ConcurrentHashMap<>();

    GreenSchedulerMetricsBinder(MeterRegistry registry, Scheduler scheduler) {
        this.registry = registry;
        scheduler.addJobListener(this);
    }

    /**
     * Wraps a job's invoker so that the wall-clock and CPU time of each run are captured for the {@code execution}
     * timer and {@code cpu_time} gauge.
     * <p>
     * The bracket is placed as close to the actual method invocation as this extension's hook point allows: the
     * returned invoker becomes {@code method.getInvoker()}, i.e. the innermost invoker core wraps with its own
     * concurrency/skip-predicate/status-emitter decorators. For a synchronous (blocking) scheduled method - the
     * only kind the Micronaut extension currently produces, see {@code ExecutableMethodInvoker#isBlocking()} - the
     * returned {@link CompletionStage} is already complete by the time {@code invoke} returns, so this is a clean
     * same-thread bracket. It would under-measure a hypothetical invoker whose real work continued asynchronously
     * after returning.
     */
    ScheduledInvoker trackInvocations(ScheduledInvoker delegate) {
        return new MetricsTrackingInvoker(delegate);
    }

    /**
     * Registers the always-on fire-time/status gauges for one {@code @GreenScheduled} entry, plus the
     * carbon-impact gauge/summaries when {@link GreenObserved#carbonImpact()} is enabled. Must be called after the
     * job has actually been scheduled, since a real {@link Trigger} instance is required.
     */
    void registerJob(Trigger trigger, GreenScheduled schedule, GreenObserved observed) {
        String id = trigger.getId();
        Tags tags = Tags.of(
                TAG_IDENTITY, id,
                TAG_STRATEGY, strategyOf(schedule),
                TAG_ZONE, schedule.carbonIntensityZone(),
                TAG_INSTANCE, INSTANCE_ID);
        ObservedJob job = new ObservedJob(tags, observed.carbonImpact());
        observedJobs.put(id, job);

        Gauge.builder(METRIC_PREFIX + "last_fire_time", trigger, t -> epochSeconds(t.getPreviousFireTime()))
                .tags(tags).strongReference(true).register(registry);
        Gauge.builder(METRIC_PREFIX + "next_fire_time", trigger, t -> epochSeconds(t.getNextFireTime()))
                .tags(tags).strongReference(true).register(registry);
        Gauge.builder(METRIC_PREFIX + "overdue", trigger, t -> t.isOverdue() ? 1.0 : 0.0)
                .tags(tags).strongReference(true).register(registry);
        Gauge.builder(METRIC_PREFIX + "cpu_time", job.lastRun(), ref -> {
            JobRunSample sample = ref.get();
            return sample == null ? 0.0 : sample.cpuTimeNanos();
        }).tags(tags).strongReference(true).register(registry);

        // The "window" counter (mode=fallback|optimized, fixed_window strategy only) from the CIIO-282 spec is
        // deliberately not wired up: core exposes no signal on Trigger/ScheduledExecution distinguishing whether a
        // FixedWindowTrigger execution used the carbon-aware planner or fell back to its cron schedule (see
        // FixedWindowTrigger#evaluate in SimpleScheduler). Registering it with a guessed value would be worse than
        // omitting it - see the PR description for this gap.

        if (observed.carbonImpact()) {
            Gauge.builder(METRIC_PREFIX + "carbon_impact_computed_at", trigger,
                    t -> t.getLastCarbonImpact().map(result -> epochSeconds(result.computedAt())).orElse(Double.NaN))
                    .tags(tags).strongReference(true).register(registry);
        }
    }

    @Override
    public void jobExecutionSuccessful(ScheduledExecution execution) {
        recordExecution(execution, "success");
    }

    @Override
    public void jobExecutionFailed(ScheduledExecution execution, Throwable throwable) {
        recordExecution(execution, "failure");
    }

    private void recordExecution(ScheduledExecution execution, String outcome) {
        ObservedJob job = observedJobs.get(execution.getTrigger().getId());
        if (job == null) {
            return;
        }
        JobRunSample sample = job.lastRun().get();
        long wallTimeNanos = sample == null ? 0L : sample.wallTimeNanos();
        Timer.builder(METRIC_PREFIX + "execution")
                .tags(job.tags().and(TAG_OUTCOME, outcome))
                .register(registry)
                .record(wallTimeNanos, TimeUnit.NANOSECONDS);

        // A negative duration (fire time before the scheduled time) is not expected in practice, but if it ever
        // happens Micrometer's Timer implementations silently discard negative recordings rather than throwing.
        Duration drift = Duration.between(execution.getScheduledFireTime(), execution.getFireTime());
        Timer.builder(METRIC_PREFIX + "drift")
                .tags(job.tags())
                .register(registry)
                .record(drift);
    }

    @Override
    public void jobExecutionSkipped(ScheduledExecution execution, String detail) {
        ObservedJob job = observedJobs.get(execution.getTrigger().getId());
        if (job == null) {
            return;
        }
        String reason = CONCURRENT_EXECUTION_SKIP_DETAIL.equals(detail) ? "concurrent_execution" : "skip_predicate";
        Counter.builder(METRIC_PREFIX + "skipped")
                .tags(job.tags().and(TAG_REASON, reason))
                .register(registry)
                .increment();
    }

    @Override
    public void jobCarbonImpactCalculated(Trigger trigger, CarbonImpactResult result) {
        ObservedJob job = observedJobs.get(trigger.getId());
        if (job == null || !job.carbonImpactEnabled()) {
            return;
        }
        DistributionSummary.builder(METRIC_PREFIX + "carbon_impact")
                .baseUnit("grams")
                .tags(job.tags())
                .register(registry)
                .record(result.impactGrams());
        DistributionSummary.builder(METRIC_PREFIX + "carbon_savings")
                .baseUnit("grams")
                .tags(job.tags())
                .register(registry)
                .record(result.savingsGrams());
    }

    private static double epochSeconds(Instant instant) {
        if (instant == null) {
            return Double.NaN;
        }
        return instant.getEpochSecond() + instant.getNano() / 1_000_000_000.0;
    }

    private static String resolveInstanceId() {
        String hostname = System.getenv("HOSTNAME");
        if (hostname != null && !hostname.isBlank()) {
            return hostname;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }

    private static String strategyOf(GreenScheduled schedule) {
        if (!schedule.fixedWindow().isEmpty()) {
            return "fixed_window";
        }
        if (!schedule.successive().isEmpty()) {
            return "successive";
        }
        return "cron";
    }

    private final class MetricsTrackingInvoker implements ScheduledInvoker {

        private final ScheduledInvoker delegate;

        MetricsTrackingInvoker(ScheduledInvoker delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletionStage<Void> invoke(ScheduledExecution execution) throws Exception {
            long cpuBefore = THREAD_MX_BEAN.getCurrentThreadCpuTime();
            // System.nanoTime() is a monotonic stopwatch, not a wall-clock timestamp query - it measures elapsed
            // duration only, the same role it plays inside Micrometer's own Timer.Sample. It is not covered by
            // (nor a violation of) the "always use the injected Clock" rule for timestamps in this feature.
            long wallBefore = System.nanoTime();
            try {
                return delegate.invoke(execution);
            } finally {
                long cpuTimeNanos = Math.max(0, THREAD_MX_BEAN.getCurrentThreadCpuTime() - cpuBefore);
                long wallTimeNanos = Math.max(0, System.nanoTime() - wallBefore);
                ObservedJob job = observedJobs.get(execution.getTrigger().getId());
                if (job != null) {
                    job.lastRun().set(new JobRunSample(cpuTimeNanos, wallTimeNanos));
                }
            }
        }

        @Override
        public boolean isBlocking() {
            return delegate.isBlocking();
        }
    }

    private record JobRunSample(long cpuTimeNanos, long wallTimeNanos) {
    }

    /**
     * A job's metrics tags plus its most recently recorded CPU/wall-time sample, keyed by trigger identity in
     * {@link #observedJobs}. {@code lastRun} starts empty and is updated in place by
     * {@link MetricsTrackingInvoker#invoke} - it is the same mutable cell the {@code cpu_time} gauge reads from, so
     * a single map lookup by trigger id is enough for both the tags and the latest sample.
     */
    private record ObservedJob(Tags tags, boolean carbonImpactEnabled, AtomicReference<JobRunSample> lastRun) {

        ObservedJob(Tags tags, boolean carbonImpactEnabled) {
            this(tags, carbonImpactEnabled, new AtomicReference<>());
        }
    }
}
