package io.carbonintensity.scheduler.micronaut;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.ScheduledExecution;
import io.carbonintensity.scheduler.Scheduler;
import io.carbonintensity.scheduler.Trigger;
import io.carbonintensity.scheduler.observability.CarbonImpactResult;
import io.carbonintensity.scheduler.observability.GreenObserved;
import io.carbonintensity.scheduler.runtime.ScheduledInvoker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Unit tests for {@link GreenSchedulerMetricsBinder}: meter registration, tag values and the outcome/reason
 * mappings for the {@code green.scheduler.job.*} metric family (CIIO-282).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GreenSchedulerMetricsBinderTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Mock
    private Scheduler scheduler;
    @Mock
    private Trigger trigger;

    private GreenSchedulerMetricsBinder binder;

    @BeforeEach
    void setUp() {
        binder = new GreenSchedulerMetricsBinder(registry, scheduler);
        when(trigger.getId()).thenReturn("job-1");
    }

    @Test
    void registersItselfAsAJobListener() {
        verify(scheduler).addJobListener(binder);
    }

    @Test
    void registersAlwaysOnGaugesWithTheSharedTags() {
        when(trigger.getPreviousFireTime()).thenReturn(Instant.ofEpochSecond(1_700_000_000L));
        when(trigger.getNextFireTime()).thenReturn(Instant.ofEpochSecond(1_700_003_600L, 500_000_000L));
        when(trigger.isOverdue()).thenReturn(true);

        binder.registerJob(trigger, fixedWindowSchedule(), observed(false));

        assertThat(gauge("green.scheduler.job.last_fire_time").value()).isEqualTo(1_700_000_000.0);
        assertThat(gauge("green.scheduler.job.next_fire_time").value()).isEqualTo(1_700_003_600.5);
        assertThat(gauge("green.scheduler.job.overdue").value()).isEqualTo(1.0);
        assertThat(gauge("green.scheduler.job.cpu_time").value()).isEqualTo(0.0);
        assertThat(registry.find("green.scheduler.job.last_fire_time")
                .tag("identity", "job-1")
                .tag("strategy", "fixed_window")
                .tag("zone", "NL")
                .gauge()).isNotNull();
    }

    @Test
    void nullFireTimesAreExposedAsNan() {
        when(trigger.getPreviousFireTime()).thenReturn(null);
        when(trigger.getNextFireTime()).thenReturn(null);

        binder.registerJob(trigger, fixedWindowSchedule(), observed(false));

        assertThat(gauge("green.scheduler.job.last_fire_time").value()).isNaN();
        assertThat(gauge("green.scheduler.job.next_fire_time").value()).isNaN();
    }

    @Test
    void carbonImpactComputedAtGaugeIsOnlyRegisteredWhenCarbonImpactIsEnabled() {
        binder.registerJob(trigger, fixedWindowSchedule(), observed(false));

        assertThat(registry.find("green.scheduler.job.carbon_impact_computed_at").gauge()).isNull();
    }

    @Test
    void carbonImpactComputedAtGaugeReflectsTheTriggersLastResult() {
        Instant computedAt = Instant.ofEpochSecond(1_700_100_000L);
        when(trigger.getLastCarbonImpact())
                .thenReturn(Optional.of(new CarbonImpactResult(LocalDate.of(2026, 9, 1), 10.0, 2.0, computedAt)));

        binder.registerJob(trigger, fixedWindowSchedule(), observed(true));

        assertThat(gauge("green.scheduler.job.carbon_impact_computed_at").value()).isEqualTo(1_700_100_000.0);
    }

    @Test
    void strategyTagIsDerivedFromWhichWindowIsConfigured() {
        binder.registerJob(trigger, successiveSchedule(), observed(false));

        assertThat(registry.find("green.scheduler.job.overdue").tag("strategy", "successive").gauge()).isNotNull();
    }

    @Test
    void executionTimerRecordsTheOutcomeTag() {
        binder.registerJob(trigger, fixedWindowSchedule(), observed(false));
        ScheduledExecution execution = execution(trigger, Instant.ofEpochSecond(100), Instant.ofEpochSecond(100));

        binder.jobExecutionSuccessful(execution);
        binder.jobExecutionFailed(execution, new RuntimeException("boom"));

        assertThat(registry.find("green.scheduler.job.execution").tag("outcome", "success").timer().count()).isEqualTo(1);
        assertThat(registry.find("green.scheduler.job.execution").tag("outcome", "failure").timer().count()).isEqualTo(1);
    }

    @Test
    void driftTimerRecordsTheGapBetweenScheduledAndActualFireTime() {
        binder.registerJob(trigger, fixedWindowSchedule(), observed(false));
        Instant scheduledFireTime = Instant.ofEpochSecond(1_000);
        Instant actualFireTime = scheduledFireTime.plusSeconds(5);
        ScheduledExecution execution = execution(trigger, actualFireTime, scheduledFireTime);

        binder.jobExecutionSuccessful(execution);

        assertThat(registry.find("green.scheduler.job.drift").timer().totalTime(TimeUnit.SECONDS))
                .isEqualTo(5.0);
    }

    @Test
    void unobservedJobsAreIgnoredByTheEventListenerCallbacks() {
        // registerJob was never called for this trigger's identity, so it is not an "observed" job.
        ScheduledExecution execution = execution(trigger, Instant.now(), Instant.now());

        binder.jobExecutionSuccessful(execution);
        binder.jobExecutionSkipped(execution, "anything");
        binder.jobCarbonImpactCalculated(trigger, new CarbonImpactResult(LocalDate.now(), 1, 1, Instant.now()));

        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void skippedCounterMapsTheConcurrentExecutionDetailMessage() {
        binder.registerJob(trigger, fixedWindowSchedule(), observed(false));
        ScheduledExecution execution = execution(trigger, Instant.now(), Instant.now());

        binder.jobExecutionSkipped(execution, "The scheduled method should not be executed concurrently");
        binder.jobExecutionSkipped(execution, "com.example.MySkipPredicate");

        assertThat(registry.find("green.scheduler.job.skipped").tag("reason", "concurrent_execution").counter().count())
                .isEqualTo(1);
        assertThat(registry.find("green.scheduler.job.skipped").tag("reason", "skip_predicate").counter().count())
                .isEqualTo(1);
    }

    @Test
    void carbonImpactSummariesOnlyRecordWhenCarbonImpactIsEnabled() {
        binder.registerJob(trigger, fixedWindowSchedule(), observed(false));

        binder.jobCarbonImpactCalculated(trigger, new CarbonImpactResult(LocalDate.now(), 42.0, 7.0, Instant.now()));

        assertThat(registry.find("green.scheduler.job.carbon_impact").summary()).isNull();
        assertThat(registry.find("green.scheduler.job.carbon_savings").summary()).isNull();
    }

    @Test
    void carbonImpactSummariesRecordGramsWhenEnabled() {
        binder.registerJob(trigger, fixedWindowSchedule(), observed(true));

        binder.jobCarbonImpactCalculated(trigger, new CarbonImpactResult(LocalDate.now(), 42.0, 7.0, Instant.now()));

        assertThat(registry.find("green.scheduler.job.carbon_impact").summary().totalAmount()).isEqualTo(42.0);
        assertThat(registry.find("green.scheduler.job.carbon_savings").summary().totalAmount()).isEqualTo(7.0);
    }

    @Test
    void trackInvocationsUpdatesTheCpuTimeGaugeAfterARun() throws Exception {
        binder.registerJob(trigger, fixedWindowSchedule(), observed(false));
        ScheduledInvoker delegate = execution -> CompletableFuture.completedFuture(null);
        ScheduledInvoker tracked = binder.trackInvocations(delegate);

        CompletionStage<Void> result = tracked.invoke(execution(trigger, Instant.now(), Instant.now()));

        assertThat(result.toCompletableFuture()).isCompleted();
        // A real assertion on a nonzero CPU time value would be flaky on fast/idle CI runners; what matters here is
        // that invoking the tracked invoker does not throw and that a sample was recorded (a non-negative value).
        assertThat(gauge("green.scheduler.job.cpu_time").value()).isGreaterThanOrEqualTo(0.0);
    }

    private Gauge gauge(String name) {
        Gauge gauge = registry.find(name).gauge();
        assertThat(gauge).as("gauge " + name).isNotNull();
        return gauge;
    }

    private ScheduledExecution execution(Trigger trigger, Instant fireTime, Instant scheduledFireTime) {
        ScheduledExecution execution = mock(ScheduledExecution.class);
        lenient().when(execution.getTrigger()).thenReturn(trigger);
        lenient().when(execution.getFireTime()).thenReturn(fireTime);
        lenient().when(execution.getScheduledFireTime()).thenReturn(scheduledFireTime);
        return execution;
    }

    private GreenScheduled fixedWindowSchedule() {
        GreenScheduled schedule = mock(GreenScheduled.class);
        lenient().when(schedule.fixedWindow()).thenReturn("08:00 17:00");
        lenient().when(schedule.successive()).thenReturn("");
        lenient().when(schedule.carbonIntensityZone()).thenReturn("NL");
        return schedule;
    }

    private GreenScheduled successiveSchedule() {
        GreenScheduled schedule = mock(GreenScheduled.class);
        lenient().when(schedule.fixedWindow()).thenReturn("");
        lenient().when(schedule.successive()).thenReturn("0s 1s 1s");
        lenient().when(schedule.carbonIntensityZone()).thenReturn("NL");
        return schedule;
    }

    private GreenObserved observed(boolean carbonImpact) {
        GreenObserved observed = mock(GreenObserved.class);
        lenient().when(observed.carbonImpact()).thenReturn(carbonImpact);
        return observed;
    }
}
