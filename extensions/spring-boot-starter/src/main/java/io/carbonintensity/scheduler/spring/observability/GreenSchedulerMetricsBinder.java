package io.carbonintensity.scheduler.spring.observability;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ToDoubleFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.ScheduledExecution;
import io.carbonintensity.scheduler.Scheduler;
import io.carbonintensity.scheduler.Trigger;
import io.carbonintensity.scheduler.observability.CarbonImpactResult;
import io.carbonintensity.scheduler.observability.GreenObserved;
import io.carbonintensity.scheduler.runtime.MutableScheduledMethod;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

/**
 * Exports the {@code green.scheduler.job.*} Micrometer metric family (CIIO-282) for every {@code @GreenScheduled}
 * job whose method also carries {@link GreenObserved} - metrics are opt-in per job, as documented on that
 * annotation.
 * <p>
 * One instance is shared across the whole application context (see {@code GreenSchedulerMetricsAutoConfiguration}):
 * it lazily registers a single {@link Scheduler.EventListener} with the {@link Scheduler} the first time a job is
 * bound, and keeps per-identity state (tags, the last sampled execution, whether carbon-impact is enabled) in
 * plain concurrent maps keyed by trigger identity.
 */
public final class GreenSchedulerMetricsBinder implements SchedulingMetricsInstrumenter {

    private static final Logger log = LoggerFactory.getLogger(GreenSchedulerMetricsBinder.class);

    static final String METRIC_PREFIX = "green.scheduler.job.";

    // Mirrors the literal detail message SkipConcurrentExecutionInvoker fires - core has no dedicated skip-reason
    // enum/type, so this string match is the only signal available. Any other detail is assumed to come from a
    // SkipPredicate (whose detail is the predicate's class name).
    private static final String CONCURRENT_EXECUTION_SKIP_DETAIL = "The scheduled method should not be executed concurrently";

    private final MeterRegistry registry;
    private final Clock clock;
    private final String instanceId;
    // tags + observed are set together, once, at bind time, and never change afterward - one map, one lookup in
    // the hot event-listener path, instead of two.
    private final Map<String, BoundJob> boundJobsByIdentity = new ConcurrentHashMap<>();
    // Written from CpuTimeSamplingInvoker on the job-executor thread (a different lifecycle: once per run, not
    // once at bind time), so kept as its own map rather than folded into BoundJob.
    private final Map<String, ExecutionSample> lastExecutionByIdentity = new ConcurrentHashMap<>();
    private final AtomicBoolean listenerRegistered = new AtomicBoolean(false);
    private final Scheduler.EventListener eventListener = new MetricsEventListener();

    public GreenSchedulerMetricsBinder(MeterRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
        this.instanceId = resolveInstanceId();
    }

    @Override
    public void instrumentInvoker(MutableScheduledMethod method) {
        if (method.getGreenObserved().isEmpty()) {
            return;
        }
        method.setInvoker(new CpuTimeSamplingInvoker(method.getInvoker(), clock, lastExecutionByIdentity));
    }

    @Override
    public void bindMetrics(MutableScheduledMethod method, Scheduler scheduler) {
        Optional<GreenObserved> observedOpt = method.getGreenObserved();
        if (observedOpt.isEmpty()) {
            return;
        }
        ensureListenerRegistered(scheduler);

        GreenObserved observed = observedOpt.get();
        List<GreenScheduled> schedules = method.getSchedules();
        for (int i = 0; i < schedules.size(); i++) {
            GreenScheduled schedule = schedules.get(i);
            String identity = identityOf(schedule, method.getMethodDescription(), i + 1);
            Trigger trigger = scheduler.getScheduledJob(identity);
            if (trigger == null) {
                log.warn("Could not find the just-scheduled trigger '{}' for {} - no metrics will be exported for it",
                        identity, method.getMethodDescription());
                continue;
            }
            bindTrigger(identity, trigger, schedule, observed);
        }
    }

    private void ensureListenerRegistered(Scheduler scheduler) {
        if (listenerRegistered.compareAndSet(false, true)) {
            scheduler.addJobListener(eventListener);
        }
    }

    private void bindTrigger(String identity, Trigger trigger, GreenScheduled schedule, GreenObserved observed) {
        Tags tags = Tags.of("identity", identity, "strategy", strategyOf(schedule), "zone",
                schedule.carbonIntensityZone(), "instance", instanceId);
        boundJobsByIdentity.put(identity, new BoundJob(tags, observed));

        // Always-on: registered as soon as @GreenObserved is present, regardless of carbonImpact.
        registerGauge("last_fire_time", trigger, t -> epochSeconds(t.getPreviousFireTime()), tags,
                "Epoch seconds of the last time this job's trigger fired");
        registerGauge("next_fire_time", trigger, t -> epochSeconds(t.getNextFireTime()), tags,
                "Epoch seconds of the next time this job's trigger is scheduled to fire");
        registerGauge("overdue", trigger, t -> t.isOverdue() ? 1.0 : 0.0, tags,
                "1 if this job's last execution is overdue, 0 otherwise");
        // Unit: nanoseconds (ThreadMXBean's native unit) - Micrometer gauges carry no enforced unit.
        registerGauge("cpu_time", lastExecutionByIdentity, samples -> cpuTimeNanosOf(samples, identity), tags,
                "ThreadMXBean CPU time of this job's last run, in nanoseconds");
        // `window` (mode=fallback|optimized), only applicable to strategy=fixed_window, is intentionally not
        // implemented: core exposes no signal distinguishing a fixed-window execution that fell back to its plain
        // interval/cron trigger from one that used an actually-planned green window. See the PR description for
        // CIIO-349 for the gap this leaves.

        if (observed.carbonImpact()) {
            registerGauge("carbon_impact_computed_at", trigger,
                    t -> t.getLastCarbonImpact().map(r -> epochSeconds(r.computedAt())).orElse(Double.NaN), tags,
                    "Epoch seconds of the last successful carbon-impact batch run for this job");
        }
    }

    private <T> void registerGauge(String metricName, T state, ToDoubleFunction<T> valueFunction, Tags tags,
            String description) {
        Gauge.builder(METRIC_PREFIX + metricName, state, valueFunction)
                .tags(tags)
                .description(description)
                .register(registry);
    }

    private static double cpuTimeNanosOf(Map<String, ExecutionSample> samples, String identity) {
        ExecutionSample sample = samples.get(identity);
        return sample == null || sample.cpuNanos() < 0 ? Double.NaN : (double) sample.cpuNanos();
    }

    private static String identityOf(GreenScheduled schedule, String methodDescription, int oneBasedIndex) {
        // Mirrors SimpleScheduler#scheduleMethod's own nameSequence + "_" + methodDescription fallback exactly, so
        // that Scheduler#getScheduledJob(identity) resolves to the trigger core just created for this schedule
        // entry.
        return schedule.identity().isEmpty() ? oneBasedIndex + "_" + methodDescription : schedule.identity();
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

    private static double epochSeconds(Instant instant) {
        return instant == null ? Double.NaN : instant.getEpochSecond() + instant.getNano() / 1_000_000_000.0;
    }

    private static String resolveInstanceId() {
        // Checked first, and cheap: HOSTNAME is set by every common container runtime, avoiding
        // InetAddress.getLocalHost() - a DNS/hostname lookup that can block noticeably (a known JVM/container
        // gotcha) during this bean's construction, i.e. application startup.
        String hostnameEnv = System.getenv("HOSTNAME");
        if (hostnameEnv != null && !hostnameEnv.isBlank()) {
            return hostnameEnv;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }

    /**
     * The always-on execution/drift/skipped metrics, plus the carbon-impact distribution summaries, all funnel
     * through this single listener - registered once, globally, on the shared {@link Scheduler}. Every callback
     * first checks {@link #boundJobsByIdentity} to silently ignore events for jobs that never opted into
     * {@link GreenObserved}.
     */
    private final class MetricsEventListener implements Scheduler.EventListener {

        @Override
        public void jobExecutionSuccessful(ScheduledExecution execution) {
            recordExecution(execution, "success");
        }

        @Override
        public void jobExecutionFailed(ScheduledExecution execution, Throwable throwable) {
            // Core's EventListener only distinguishes success vs. failed(-with-throwable); there is no separate
            // "failure without exception" signal to map to outcome=failure, so every failure is reported as
            // outcome=exception.
            recordExecution(execution, "exception");
        }

        @Override
        public void jobExecutionSkipped(ScheduledExecution execution, String detail) {
            BoundJob bound = boundJobsByIdentity.get(execution.getTrigger().getId());
            if (bound == null) {
                return;
            }
            String reason = CONCURRENT_EXECUTION_SKIP_DETAIL.equals(detail) ? "concurrent_execution" : "skip_predicate";
            registry.counter(METRIC_PREFIX + "skipped", bound.tags().and("reason", reason)).increment();
        }

        @Override
        public void jobCarbonImpactCalculated(Trigger trigger, CarbonImpactResult result) {
            BoundJob bound = boundJobsByIdentity.get(trigger.getId());
            if (bound == null || !bound.observed().carbonImpact()) {
                return;
            }
            registry.summary(METRIC_PREFIX + "carbon_impact", bound.tags()).record(result.impactGrams());
            registry.summary(METRIC_PREFIX + "carbon_savings", bound.tags()).record(result.savingsGrams());
        }

        private void recordExecution(ScheduledExecution execution, String outcome) {
            String identity = execution.getTrigger().getId();
            BoundJob bound = boundJobsByIdentity.get(identity);
            if (bound == null) {
                return;
            }
            ExecutionSample sample = lastExecutionByIdentity.get(identity);
            long elapsedNanos = sample == null ? 0L : sample.elapsedNanos();
            registry.timer(METRIC_PREFIX + "execution", bound.tags().and("outcome", outcome))
                    .record(Duration.ofNanos(elapsedNanos));
            registry.timer(METRIC_PREFIX + "drift", bound.tags())
                    .record(Duration.between(execution.getScheduledFireTime(), execution.getFireTime()));
        }
    }

    /**
     * A job's identity-derived tags and its {@link GreenObserved} configuration, bound once and never mutated
     * afterward - see {@link #bindTrigger}.
     */
    private record BoundJob(Tags tags, GreenObserved observed) {
    }
}
