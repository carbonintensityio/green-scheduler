package io.carbonintensity.scheduler.quarkus.runtime.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.carbonintensity.scheduler.ScheduledExecution;
import io.carbonintensity.scheduler.Scheduler;
import io.carbonintensity.scheduler.Trigger;
import io.carbonintensity.scheduler.observability.CarbonImpactResult;
import io.carbonintensity.scheduler.observability.GreenObserved;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.runtime.StartupEvent;

/**
 * Unit tests for {@link GreenSchedulerMeterBinder}: meter names/tags/values for the always-on Gauges and the
 * {@link Scheduler.EventListener}-driven Counters/Timers - see the {@code green.scheduler.job.*} metric family
 * design in CIIO-282. Uses the package-private test-seam constructor so no real Quarkus/CDI container or
 * {@code QuarkusScheduler} is needed.
 */
class GreenSchedulerMeterBinderTest {

    private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String INSTANCE = "test-instance";

    private SimpleMeterRegistry registry;
    private FakeScheduler scheduler;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        scheduler = new FakeScheduler();
    }

    private GreenSchedulerMeterBinder newBinder() {
        return newBinder(new ConcurrentHashMap<>());
    }

    private GreenSchedulerMeterBinder newBinder(java.util.Map<String, Double> cpuTimeSecondsByIdentity) {
        return new GreenSchedulerMeterBinder(registry, scheduler, cpuTimeSecondsByIdentity, CLOCK, false, INSTANCE);
    }

    private static Tags fixedWindowTags(String identity) {
        return Tags.of("identity", identity, "strategy", "fixed_window", "zone", "NL", "instance", INSTANCE);
    }

    @Test
    void nonObservedJobsGetNoMeters() {
        FakeTrigger trigger = new FakeTrigger("plain-job");
        trigger.greenObserved = Optional.empty();
        scheduler.jobs.add(trigger);

        newBinder().onStart(new StartupEvent());

        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void alwaysOnGaugesAreRegisteredForAnObservedJob() {
        FakeTrigger trigger = new FakeTrigger("fixed-job");
        trigger.previousFireTime = NOW.minusSeconds(3600);
        trigger.nextFireTime = NOW.plusSeconds(3600);
        trigger.overdue = false;
        scheduler.jobs.add(trigger);

        java.util.Map<String, Double> cpuTimes = new ConcurrentHashMap<>();
        cpuTimes.put("fixed-job", 0.25);
        newBinder(cpuTimes).onStart(new StartupEvent());

        Tags tags = fixedWindowTags("fixed-job");
        assertThat(registry.get("green.scheduler.job.last_fire_time").tags(tags).gauge().value())
                .isEqualTo(NOW.minusSeconds(3600).getEpochSecond());
        assertThat(registry.get("green.scheduler.job.next_fire_time").tags(tags).gauge().value())
                .isEqualTo(NOW.plusSeconds(3600).getEpochSecond());
        assertThat(registry.get("green.scheduler.job.overdue").tags(tags).gauge().value()).isEqualTo(0.0);
        assertThat(registry.get("green.scheduler.job.cpu_time").tags(tags).gauge().value()).isEqualTo(0.25);

        // carbonImpact is disabled on this job, so none of the carbon-impact-only meters exist
        assertThat(registry.find("green.scheduler.job.carbon_impact_computed_at").gauge()).isNull();
        assertThat(registry.find("green.scheduler.job.carbon_impact").summary()).isNull();
        assertThat(registry.find("green.scheduler.job.carbon_savings").summary()).isNull();
    }

    @Test
    void cpuTimeGaugeIsNaNWhenNoSampleRecordedYet() {
        FakeTrigger trigger = new FakeTrigger("fixed-job");
        scheduler.jobs.add(trigger);

        newBinder().onStart(new StartupEvent());

        Gauge cpuTime = registry.get("green.scheduler.job.cpu_time").tags(fixedWindowTags("fixed-job")).gauge();
        assertThat(cpuTime.value()).isNaN();
    }

    @Test
    void carbonImpactMetersAreOnlyRegisteredWhenEnabled() {
        FakeTrigger trigger = new FakeTrigger("carbon-job");
        trigger.greenObserved = Optional.of(fakeGreenObserved(true));
        scheduler.jobs.add(trigger);

        newBinder().onStart(new StartupEvent());

        Tags tags = fixedWindowTags("carbon-job");
        Gauge computedAt = registry.get("green.scheduler.job.carbon_impact_computed_at").tags(tags).gauge();
        assertThat(computedAt.value()).isNaN(); // no batch run yet

        DistributionSummary impact = registry.get("green.scheduler.job.carbon_impact").tags(tags).summary();
        DistributionSummary savings = registry.get("green.scheduler.job.carbon_savings").tags(tags).summary();
        assertThat(impact.count()).isZero();
        assertThat(savings.count()).isZero();
    }

    @Test
    void carbonImpactComputedAtReflectsTheTriggersLastResult() {
        FakeTrigger trigger = new FakeTrigger("carbon-job");
        trigger.greenObserved = Optional.of(fakeGreenObserved(true));
        Instant computedAt = NOW.minusSeconds(600);
        trigger.lastCarbonImpact = Optional.of(new CarbonImpactResult(NOW.minusSeconds(86400).atZone(ZoneOffset.UTC)
                .toLocalDate(), 100.0, 20.0, computedAt));
        scheduler.jobs.add(trigger);

        newBinder().onStart(new StartupEvent());

        Gauge computedAtGauge = registry.get("green.scheduler.job.carbon_impact_computed_at")
                .tags(fixedWindowTags("carbon-job")).gauge();
        assertThat(computedAtGauge.value()).isEqualTo(computedAt.getEpochSecond());
    }

    @Test
    void successfulExecutionRecordsExecutionAndDriftTimers() {
        FakeTrigger trigger = new FakeTrigger("fixed-job");
        scheduler.jobs.add(trigger);
        newBinder().onStart(new StartupEvent());

        Instant fireTime = NOW.minusSeconds(5);
        Instant scheduledFireTime = fireTime.minusSeconds(2);
        scheduler.listener.jobExecutionSuccessful(new FakeExecution(trigger, fireTime, scheduledFireTime));

        Tags tags = fixedWindowTags("fixed-job");
        Timer execution = registry.get("green.scheduler.job.execution").tags(tags).tags("outcome", "success").timer();
        assertThat(execution.count()).isEqualTo(1);
        assertThat(execution.totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(5.0);

        Timer drift = registry.get("green.scheduler.job.drift").tags(tags).timer();
        assertThat(drift.count()).isEqualTo(1);
        assertThat(drift.totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(2.0);
    }

    @Test
    void failedExecutionUsesExceptionOutcome() {
        FakeTrigger trigger = new FakeTrigger("fixed-job");
        scheduler.jobs.add(trigger);
        newBinder().onStart(new StartupEvent());

        scheduler.listener.jobExecutionFailed(new FakeExecution(trigger, NOW.minusSeconds(1), NOW.minusSeconds(1)),
                new RuntimeException("boom"));

        Tags tags = fixedWindowTags("fixed-job");
        // both outcome variants are pre-registered per job at bind time (so dashboards show 0 instead of a missing
        // series) - only the exception one actually got a recording here
        Timer success = registry.get("green.scheduler.job.execution").tags(tags).tags("outcome", "success").timer();
        assertThat(success.count()).isZero();
        Timer exception = registry.get("green.scheduler.job.execution").tags(tags).tags("outcome", "exception").timer();
        assertThat(exception.count()).isEqualTo(1);
    }

    @Test
    void skippedExecutionIncrementsSkippedCounterWithConcurrentExecutionReason() {
        FakeTrigger trigger = new FakeTrigger("fixed-job");
        scheduler.jobs.add(trigger);
        newBinder().onStart(new StartupEvent());

        scheduler.listener.jobExecutionSkipped(new FakeExecution(trigger, NOW, NOW), Scheduler.SkipReason.CONCURRENT_EXECUTION,
                "The scheduled method should not be executed concurrently");

        Counter skipped = registry.get("green.scheduler.job.skipped")
                .tags(fixedWindowTags("fixed-job")).tags("reason", "concurrent_execution").counter();
        assertThat(skipped.count()).isEqualTo(1.0);
    }

    @Test
    void skippedExecutionIncrementsSkippedCounterWithSkipPredicateReason() {
        FakeTrigger trigger = new FakeTrigger("fixed-job");
        scheduler.jobs.add(trigger);
        newBinder().onStart(new StartupEvent());

        scheduler.listener.jobExecutionSkipped(new FakeExecution(trigger, NOW, NOW), Scheduler.SkipReason.SKIP_PREDICATE,
                "some.custom.SkipPredicate");

        Counter skipped = registry.get("green.scheduler.job.skipped")
                .tags(fixedWindowTags("fixed-job")).tags("reason", "skip_predicate").counter();
        assertThat(skipped.count()).isEqualTo(1.0);
    }

    @Test
    void carbonImpactCalculatedRecordsDistributionSummaries() {
        FakeTrigger trigger = new FakeTrigger("carbon-job");
        trigger.greenObserved = Optional.of(fakeGreenObserved(true));
        scheduler.jobs.add(trigger);
        newBinder().onStart(new StartupEvent());

        CarbonImpactResult result = new CarbonImpactResult(NOW.atZone(ZoneOffset.UTC).toLocalDate(), 150.0, 30.0, NOW);
        scheduler.listener.jobCarbonImpactCalculated(trigger, result);

        Tags tags = fixedWindowTags("carbon-job");
        DistributionSummary impact = registry.get("green.scheduler.job.carbon_impact").tags(tags).summary();
        DistributionSummary savings = registry.get("green.scheduler.job.carbon_savings").tags(tags).summary();
        assertThat(impact.totalAmount()).isEqualTo(150.0);
        assertThat(savings.totalAmount()).isEqualTo(30.0);
    }

    @Test
    void fixedWindowExecutionIncrementsWindowCounterWithMode() {
        FakeTrigger trigger = new FakeTrigger("fixed-job");
        trigger.lastWindowFireMode = Optional.of(Trigger.WindowFireMode.OPTIMIZED);
        scheduler.jobs.add(trigger);
        newBinder().onStart(new StartupEvent());

        scheduler.listener.jobExecutionSuccessful(new FakeExecution(trigger, NOW, NOW));

        Counter window = registry.get("green.scheduler.job.window")
                .tags(fixedWindowTags("fixed-job")).tags("mode", "optimized").counter();
        assertThat(window.count()).isEqualTo(1.0);
    }

    @Test
    void successiveStrategyNeverGetsAWindowCounter() {
        FakeTrigger trigger = new FakeTrigger("successive-job");
        trigger.strategy = Trigger.Strategy.SUCCESSIVE;
        scheduler.jobs.add(trigger);
        newBinder().onStart(new StartupEvent());

        scheduler.listener.jobExecutionSuccessful(new FakeExecution(trigger, NOW, NOW));

        assertThat(registry.find("green.scheduler.job.window").counter()).isNull();
    }

    private static GreenObserved fakeGreenObserved(boolean carbonImpact) {
        return new GreenObserved() {
            @Override
            public Class<? extends Annotation> annotationType() {
                return GreenObserved.class;
            }

            @Override
            public boolean carbonImpact() {
                return carbonImpact;
            }
        };
    }

    /**
     * A minimal {@link Trigger} carrying just enough state for the binder's tag/value logic - a fixed-window job by
     * default, since that is the strategy the {@code window} counter needs to be exercised against.
     */
    private static final class FakeTrigger implements Trigger {

        private final String id;
        Trigger.Strategy strategy = Trigger.Strategy.FIXED_WINDOW;
        String zone = "NL";
        Optional<GreenObserved> greenObserved = Optional.of(fakeGreenObserved(false));
        Instant previousFireTime;
        Instant nextFireTime;
        boolean overdue;
        Optional<CarbonImpactResult> lastCarbonImpact = Optional.empty();
        Optional<Trigger.WindowFireMode> lastWindowFireMode = Optional.empty();

        FakeTrigger(String id) {
            this.id = id;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public Instant getNextFireTime() {
            return nextFireTime;
        }

        @Override
        public Instant getPreviousFireTime() {
            return previousFireTime;
        }

        @Override
        public boolean isOverdue() {
            return overdue;
        }

        @Override
        public Optional<GreenObserved> getGreenObserved() {
            return greenObserved;
        }

        @Override
        public String getCarbonIntensityZone() {
            return zone;
        }

        @Override
        public Trigger.Strategy getStrategy() {
            return strategy;
        }

        @Override
        public Optional<CarbonImpactResult> getLastCarbonImpact() {
            return lastCarbonImpact;
        }

        @Override
        public Optional<Trigger.WindowFireMode> getLastWindowFireMode() {
            return lastWindowFireMode;
        }
    }

    private static final class FakeExecution implements ScheduledExecution {

        private final Trigger trigger;
        private final Instant fireTime;
        private final Instant scheduledFireTime;

        FakeExecution(Trigger trigger, Instant fireTime, Instant scheduledFireTime) {
            this.trigger = trigger;
            this.fireTime = fireTime;
            this.scheduledFireTime = scheduledFireTime;
        }

        @Override
        public Trigger getTrigger() {
            return trigger;
        }

        @Override
        public Instant getFireTime() {
            return fireTime;
        }

        @Override
        public Instant getScheduledFireTime() {
            return scheduledFireTime;
        }
    }

    private static final class FakeScheduler implements Scheduler {

        final List<Trigger> jobs = new ArrayList<>();
        EventListener listener;

        @Override
        public void pause() {
        }

        @Override
        public void pause(String identity) {
        }

        @Override
        public void resume() {
        }

        @Override
        public void resume(String identity) {
        }

        @Override
        public boolean isPaused(String identity) {
            return false;
        }

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public List<Trigger> getScheduledJobs() {
            return jobs;
        }

        @Override
        public Trigger getScheduledJob(String identity) {
            return jobs.stream().filter(t -> t.getId().equals(identity)).findFirst().orElse(null);
        }

        @Override
        public JobDefinition newJob(String identity) {
            throw new UnsupportedOperationException("Not needed for this test");
        }

        @Override
        public Trigger unscheduleJob(String identity) {
            return null;
        }

        @Override
        public void addJobListener(EventListener listener) {
            this.listener = listener;
        }

        @Override
        public boolean removeJobListener(EventListener listener) {
            this.listener = null;
            return true;
        }
    }
}
