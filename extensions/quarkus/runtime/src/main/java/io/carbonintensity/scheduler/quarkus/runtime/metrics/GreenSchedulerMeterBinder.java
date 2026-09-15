package io.carbonintensity.scheduler.quarkus.runtime.metrics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.jboss.logging.Logger;

import io.carbonintensity.scheduler.ScheduledExecution;
import io.carbonintensity.scheduler.Scheduler;
import io.carbonintensity.scheduler.Scheduler.SkipReason;
import io.carbonintensity.scheduler.Trigger;
import io.carbonintensity.scheduler.observability.CarbonImpactResult;
import io.carbonintensity.scheduler.observability.GreenObserved;
import io.carbonintensity.scheduler.quarkus.factory.GreenSchedulerProperties;
import io.carbonintensity.scheduler.quarkus.runtime.QuarkusScheduler;
import io.carbonintensity.scheduler.runtime.SchedulerConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.quarkus.runtime.StartupEvent;

/**
 * Exports {@code green.scheduler.job.*} Micrometer meters for every {@code @GreenObserved} job - see the
 * {@code GreenObserved} Javadoc for the full metric family and {@code CIIO-282} for the design rationale.
 * <p>
 * Only ever instantiated when Micrometer is actually on the classpath - see the deployment module's capability
 * check - so {@link MeterRegistry} is a plain, mandatory injection point here, not an optional one.
 * <p>
 * Depends on {@link QuarkusScheduler} purely to force CDI initialization order: by the time this bean's
 * constructor runs, every {@code @GreenScheduled}/{@code @GreenObserved} method has already been registered with
 * the {@link Scheduler}, and {@link QuarkusScheduler#getLastCpuTimeSeconds()} is safe to read from a {@code cpu_time}
 * gauge callback.
 * <p>
 * All Counter/Timer/DistributionSummary instances a job could ever need are built once, at startup, into that job's
 * {@link JobMeters} - the {@link Scheduler.EventListener} callbacks below run on every single job execution, so they
 * only ever do a map lookup followed by a plain {@code record()}/{@code increment()} call, never Micrometer builder
 * work.
 */
@Singleton
public class GreenSchedulerMeterBinder {

    private static final Logger LOG = Logger.getLogger(GreenSchedulerMeterBinder.class);

    private static final String METRIC_PREFIX = "green.scheduler.job.";

    private final MeterRegistry registry;
    private final Scheduler scheduler;
    private final Map<String, Double> lastCpuTimeSecondsByIdentity;
    private final Clock clock;
    private final boolean publishPercentileHistograms;
    private final String instanceTag;
    private final Map<String, JobMeters> metersByIdentity = new HashMap<>();

    @Inject
    public GreenSchedulerMeterBinder(MeterRegistry registry, Scheduler scheduler, QuarkusScheduler quarkusScheduler,
            SchedulerConfig schedulerConfig, GreenSchedulerProperties properties) {
        this(registry, scheduler, quarkusScheduler.getLastCpuTimeSeconds(), schedulerConfig.getClock(),
                properties.metrics().publishPercentileHistograms().orElse(false), InstanceTag.resolve());
    }

    /**
     * Test seam: bypasses the need for a real {@link QuarkusScheduler} (whose constructor requires a fully wired
     * {@code SchedulerContext}) by accepting the CPU-time map and instance tag directly.
     */
    GreenSchedulerMeterBinder(MeterRegistry registry, Scheduler scheduler, Map<String, Double> lastCpuTimeSecondsByIdentity,
            Clock clock, boolean publishPercentileHistograms, String instanceTag) {
        this.registry = registry;
        this.scheduler = scheduler;
        this.lastCpuTimeSecondsByIdentity = lastCpuTimeSecondsByIdentity;
        this.clock = clock;
        this.publishPercentileHistograms = publishPercentileHistograms;
        this.instanceTag = instanceTag;
    }

    void onStart(@Observes StartupEvent event) {
        for (Trigger trigger : scheduler.getScheduledJobs()) {
            trigger.getGreenObserved().ifPresent(observed -> bindJob(trigger, observed));
        }
        scheduler.addJobListener(new MeterUpdatingListener());
        LOG.debugf("Bound Micrometer meters for %d @GreenObserved job(s)", metersByIdentity.size());
    }

    private void bindJob(Trigger trigger, GreenObserved observed) {
        Tags tags = commonTags(trigger);
        bindAlwaysOnGauges(trigger, observed, tags);
        metersByIdentity.put(trigger.getId(), new JobMeters(trigger, observed, tags));
    }

    private void bindAlwaysOnGauges(Trigger trigger, GreenObserved observed, Tags tags) {
        Gauge.builder(METRIC_PREFIX + "last_fire_time", trigger, t -> toEpochSeconds(t.getPreviousFireTime()))
                .tags(tags)
                .description("Epoch seconds of this job's last fire time")
                .register(registry);
        Gauge.builder(METRIC_PREFIX + "next_fire_time", trigger, t -> toEpochSeconds(t.getNextFireTime()))
                .tags(tags)
                .description("Epoch seconds of this job's next scheduled fire time")
                .register(registry);
        Gauge.builder(METRIC_PREFIX + "overdue", trigger, t -> t.isOverdue() ? 1.0 : 0.0)
                .tags(tags)
                .description("Whether this job's last execution is overdue (1) or not (0)")
                .register(registry);
        Gauge.builder(METRIC_PREFIX + "cpu_time", lastCpuTimeSecondsByIdentity,
                map -> map.getOrDefault(trigger.getId(), Double.NaN))
                .tags(tags)
                .description("CPU time consumed by this job's most recent run, in seconds")
                .register(registry);

        if (observed.carbonImpact()) {
            Gauge.builder(METRIC_PREFIX + "carbon_impact_computed_at", trigger,
                    t -> t.getLastCarbonImpact().map(CarbonImpactResult::computedAt)
                            .map(GreenSchedulerMeterBinder::toEpochSeconds)
                            .orElse(Double.NaN))
                    .tags(tags)
                    .description("Epoch seconds of the last successful daily carbon-impact batch run for this job")
                    .register(registry);
        }
    }

    private DistributionSummary distributionSummary(String name, String noun, Tags tags) {
        return DistributionSummary.builder(METRIC_PREFIX + name)
                .baseUnit("grams")
                .tags(tags)
                .description("Carbon " + noun + " (gCO2eq) per job run, computed retrospectively")
                .register(registry);
    }

    private Timer timer(String name, Tags tags) {
        return Timer.builder(METRIC_PREFIX + name)
                .tags(tags)
                .publishPercentileHistogram(publishPercentileHistograms)
                .register(registry);
    }

    private Counter counter(String name, Tags tags) {
        return Counter.builder(METRIC_PREFIX + name).tags(tags).register(registry);
    }

    private Tags commonTags(Trigger trigger) {
        return Tags.of(
                "identity", trigger.getId(),
                "strategy", strategyTag(trigger.getStrategy()),
                "zone", Optional.ofNullable(trigger.getCarbonIntensityZone()).orElse("unknown"),
                "instance", instanceTag);
    }

    private static String strategyTag(Trigger.Strategy strategy) {
        return strategy == null ? "unknown" : strategy.name().toLowerCase(Locale.ROOT);
    }

    private static double toEpochSeconds(Instant instant) {
        return instant == null ? Double.NaN : instant.getEpochSecond();
    }

    /**
     * Every Counter/Timer/DistributionSummary a single {@code @GreenObserved} job could ever need, pre-built once at
     * bind time - see the class-level Javadoc for why.
     */
    private final class JobMeters {

        private final Timer executionSuccess;
        private final Timer executionException;
        private final Timer drift;
        private final Counter skippedConcurrentExecution;
        private final Counter skippedPredicate;
        private final Counter windowOptimized;
        private final Counter windowFallback;
        private final DistributionSummary carbonImpact;
        private final DistributionSummary carbonSavings;

        JobMeters(Trigger trigger, GreenObserved observed, Tags tags) {
            this.executionSuccess = timer("execution", tags.and("outcome", "success"));
            this.executionException = timer("execution", tags.and("outcome", "exception"));
            this.drift = timer("drift", tags);
            this.skippedConcurrentExecution = counter("skipped", tags.and("reason", "concurrent_execution"));
            this.skippedPredicate = counter("skipped", tags.and("reason", "skip_predicate"));

            boolean fixedWindow = trigger.getStrategy() == Trigger.Strategy.FIXED_WINDOW;
            this.windowOptimized = fixedWindow ? counter("window", tags.and("mode", "optimized")) : null;
            this.windowFallback = fixedWindow ? counter("window", tags.and("mode", "fallback")) : null;

            if (observed.carbonImpact()) {
                this.carbonImpact = distributionSummary("carbon_impact", "impact", tags);
                this.carbonSavings = distributionSummary("carbon_savings", "savings", tags);
            } else {
                this.carbonImpact = null;
                this.carbonSavings = null;
            }
        }

        void recordSkipped(SkipReason reason) {
            (reason == SkipReason.CONCURRENT_EXECUTION ? skippedConcurrentExecution : skippedPredicate).increment();
        }

        void recordCarbonImpactCalculated(CarbonImpactResult result) {
            if (carbonImpact != null) {
                carbonImpact.record(result.impactGrams());
                carbonSavings.record(result.savingsGrams());
            }
        }

        void recordCompletedExecution(ScheduledExecution execution, boolean successful) {
            Duration executionDuration = Duration.between(execution.getFireTime(), clock.instant());
            if (!executionDuration.isNegative()) {
                (successful ? executionSuccess : executionException).record(executionDuration);
            }

            Duration jobDrift = Duration.between(execution.getScheduledFireTime(), execution.getFireTime()).abs();
            drift.record(jobDrift);

            if (windowOptimized != null) {
                execution.getTrigger().getLastWindowFireMode()
                        .ifPresent(mode -> (mode == Trigger.WindowFireMode.OPTIMIZED ? windowOptimized : windowFallback)
                                .increment());
            }
        }
    }

    /**
     * Pushes the Counter/Timer meters driven by {@link Scheduler.EventListener} events, as opposed to the always-on
     * Gauges above, which are polled directly from {@link Trigger} state. A job with no entry in
     * {@link #metersByIdentity} was never {@code @GreenObserved} - every callback here is a no-op for it.
     */
    private final class MeterUpdatingListener implements Scheduler.EventListener {

        @Override
        public void jobExecutionSuccessful(ScheduledExecution execution) {
            JobMeters meters = metersByIdentity.get(execution.getTrigger().getId());
            if (meters != null) {
                meters.recordCompletedExecution(execution, true);
            }
        }

        @Override
        public void jobExecutionFailed(ScheduledExecution execution, Throwable throwable) {
            JobMeters meters = metersByIdentity.get(execution.getTrigger().getId());
            if (meters != null) {
                meters.recordCompletedExecution(execution, false);
            }
        }

        @Override
        public void jobExecutionSkipped(ScheduledExecution execution, SkipReason reason, String detail) {
            JobMeters meters = metersByIdentity.get(execution.getTrigger().getId());
            if (meters != null) {
                meters.recordSkipped(reason);
            }
        }

        @Override
        public void jobCarbonImpactCalculated(Trigger trigger, CarbonImpactResult result) {
            JobMeters meters = metersByIdentity.get(trigger.getId());
            if (meters != null) {
                meters.recordCarbonImpactCalculated(result);
            }
        }
    }
}
