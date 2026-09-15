package io.carbonintensity.scheduler.spring.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import io.carbonintensity.scheduler.ScheduledExecution;
import io.carbonintensity.scheduler.Scheduler;
import io.carbonintensity.scheduler.Trigger;
import io.carbonintensity.scheduler.observability.CarbonImpactResult;
import io.carbonintensity.scheduler.runtime.MutableScheduledMethod;
import io.carbonintensity.scheduler.spring.TestObservedNoCarbonImpactJob;
import io.carbonintensity.scheduler.spring.TestObservedScheduledJob;
import io.carbonintensity.scheduler.spring.TestScheduledJob;
import io.carbonintensity.scheduler.spring.factory.ScheduledMethodFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class GreenSchedulerMetricsBinderTests {

    private static final String IDENTITY = "observedJob";

    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    final GreenSchedulerMetricsBinder binder = new GreenSchedulerMetricsBinder(registry, clock);
    final ScheduledMethodFactory factory = new ScheduledMethodFactory();

    private MutableScheduledMethod observedMethod() throws NoSuchMethodException {
        var job = new TestObservedScheduledJob();
        var method = TestObservedScheduledJob.class.getMethod("run");
        return factory.create(job, method);
    }

    private MutableScheduledMethod plainMethod() throws NoSuchMethodException {
        var job = new TestScheduledJob();
        var method = TestScheduledJob.class.getMethod("run");
        return factory.create(job, method);
    }

    private MutableScheduledMethod observedNoCarbonImpactMethod() throws NoSuchMethodException {
        var job = new TestObservedNoCarbonImpactJob();
        var method = TestObservedNoCarbonImpactJob.class.getMethod("run");
        return factory.create(job, method);
    }

    @Test
    void givenNonObservedMethod_whenInstrumentInvoker_thenInvokerLeftUntouched() throws NoSuchMethodException {
        var method = plainMethod();
        var originalInvoker = method.getInvoker();

        binder.instrumentInvoker(method);

        assertThat(method.getInvoker()).isSameAs(originalInvoker);
    }

    @Test
    void givenObservedMethod_whenInstrumentInvoker_thenInvokerIsWrapped() throws NoSuchMethodException {
        var method = observedMethod();
        var originalInvoker = method.getInvoker();

        binder.instrumentInvoker(method);

        assertThat(method.getInvoker()).isNotSameAs(originalInvoker);
    }

    @Test
    void givenNonObservedMethod_whenBindMetrics_thenNothingIsRegistered() throws NoSuchMethodException {
        var method = plainMethod();
        Scheduler scheduler = mock(Scheduler.class);

        binder.bindMetrics(method, scheduler);

        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void givenTriggerNotYetVisible_whenBindMetrics_thenNoMetricsAndNoException() throws NoSuchMethodException {
        var method = observedMethod();
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.getScheduledJob(IDENTITY)).thenReturn(null);

        binder.bindMetrics(method, scheduler);

        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void givenObservedMethod_whenBindMetrics_thenGaugesRegisteredWithSharedTags() throws NoSuchMethodException {
        var method = observedMethod();
        Trigger trigger = mock(Trigger.class);
        when(trigger.getPreviousFireTime()).thenReturn(Instant.ofEpochSecond(100));
        when(trigger.getNextFireTime()).thenReturn(Instant.ofEpochSecond(200));
        when(trigger.isOverdue()).thenReturn(true);
        when(trigger.getLastCarbonImpact())
                .thenReturn(
                        Optional.of(new CarbonImpactResult(LocalDate.of(2026, 1, 1), 10.0, 2.0, Instant.ofEpochSecond(300))));
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.getScheduledJob(IDENTITY)).thenReturn(trigger);

        binder.bindMetrics(method, scheduler);

        assertThat(registry.get("green.scheduler.job.last_fire_time")
                .tag("identity", IDENTITY).tag("strategy", "successive").tag("zone", "nl")
                .gauge().value()).isEqualTo(100.0);
        assertThat(registry.get("green.scheduler.job.next_fire_time").gauge().value()).isEqualTo(200.0);
        assertThat(registry.get("green.scheduler.job.overdue").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("green.scheduler.job.cpu_time").gauge().value()).isNaN();
        assertThat(registry.get("green.scheduler.job.carbon_impact_computed_at").gauge().value()).isEqualTo(300.0);
        verify(scheduler).addJobListener(any());
    }

    @Test
    void givenCarbonImpactDisabled_whenBindMetrics_thenCarbonImpactComputedAtGaugeNotRegisteredButAlwaysOnMetricsAre()
            throws NoSuchMethodException {
        var method = observedNoCarbonImpactMethod();
        Trigger trigger = mock(Trigger.class);
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.getScheduledJob("observedNoImpactJob")).thenReturn(trigger);

        binder.bindMetrics(method, scheduler);

        assertThat(registry.find("green.scheduler.job.carbon_impact_computed_at").gauge()).isNull();
        assertThat(registry.get("green.scheduler.job.last_fire_time")
                .tag("identity", "observedNoImpactJob").tag("strategy", "cron").gauge()).isNotNull();
    }

    @Test
    void givenSuccessfulExecution_whenListenerFires_thenExecutionAndDriftRecorded() throws NoSuchMethodException {
        Scheduler.EventListener listener = registerAndCaptureListener();

        Trigger trigger = mock(Trigger.class);
        when(trigger.getId()).thenReturn(IDENTITY);
        ScheduledExecution execution = mock(ScheduledExecution.class);
        when(execution.getTrigger()).thenReturn(trigger);
        when(execution.getScheduledFireTime()).thenReturn(Instant.ofEpochSecond(100));
        when(execution.getFireTime()).thenReturn(Instant.ofEpochSecond(105));

        listener.jobExecutionSuccessful(execution);

        assertThat(registry.get("green.scheduler.job.execution").tag("outcome", "success").timer().count()).isEqualTo(1);
        assertThat(registry.get("green.scheduler.job.drift").timer().totalTime(TimeUnit.SECONDS)).isCloseTo(5.0, within(0.01));
    }

    @Test
    void givenFailedExecution_whenListenerFires_thenOutcomeIsException() throws NoSuchMethodException {
        Scheduler.EventListener listener = registerAndCaptureListener();

        Trigger trigger = mock(Trigger.class);
        when(trigger.getId()).thenReturn(IDENTITY);
        ScheduledExecution execution = mock(ScheduledExecution.class);
        when(execution.getTrigger()).thenReturn(trigger);
        when(execution.getScheduledFireTime()).thenReturn(Instant.ofEpochSecond(100));
        when(execution.getFireTime()).thenReturn(Instant.ofEpochSecond(100));

        listener.jobExecutionFailed(execution, new RuntimeException("boom"));

        assertThat(registry.get("green.scheduler.job.execution").tag("outcome", "exception").timer().count()).isEqualTo(1);
    }

    @Test
    void givenSkippedForConcurrency_whenListenerFires_thenReasonIsConcurrentExecution() throws NoSuchMethodException {
        Scheduler.EventListener listener = registerAndCaptureListener();
        ScheduledExecution execution = executionFor(IDENTITY);

        listener.jobExecutionSkipped(execution, "The scheduled method should not be executed concurrently");

        assertThat(registry.get("green.scheduler.job.skipped").tag("reason", "concurrent_execution").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void givenSkippedForPredicate_whenListenerFires_thenReasonIsSkipPredicate() throws NoSuchMethodException {
        Scheduler.EventListener listener = registerAndCaptureListener();
        ScheduledExecution execution = executionFor(IDENTITY);

        listener.jobExecutionSkipped(execution, "com.example.MySkipPredicate");

        assertThat(registry.get("green.scheduler.job.skipped").tag("reason", "skip_predicate").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void givenCarbonImpactCalculated_whenListenerFires_thenSummariesRecorded() throws NoSuchMethodException {
        Scheduler.EventListener listener = registerAndCaptureListener();
        Trigger trigger = mock(Trigger.class);
        when(trigger.getId()).thenReturn(IDENTITY);

        listener.jobCarbonImpactCalculated(trigger,
                new CarbonImpactResult(LocalDate.of(2026, 1, 1), 10.0, 2.5, Instant.ofEpochSecond(300)));

        assertThat(registry.get("green.scheduler.job.carbon_impact").summary().totalAmount()).isEqualTo(10.0);
        assertThat(registry.get("green.scheduler.job.carbon_savings").summary().totalAmount()).isEqualTo(2.5);
    }

    @Test
    void givenEventForUnboundIdentity_whenListenerFires_thenNothingRecorded() throws NoSuchMethodException {
        Scheduler.EventListener listener = registerAndCaptureListener();
        ScheduledExecution execution = executionFor("some-other-job");

        listener.jobExecutionSkipped(execution, "irrelevant");

        assertThat(registry.getMeters()).noneMatch(meter -> meter.getId().getName().equals("green.scheduler.job.skipped"));
    }

    private ScheduledExecution executionFor(String identity) {
        Trigger trigger = mock(Trigger.class);
        when(trigger.getId()).thenReturn(identity);
        ScheduledExecution execution = mock(ScheduledExecution.class);
        when(execution.getTrigger()).thenReturn(trigger);
        return execution;
    }

    private Scheduler.EventListener registerAndCaptureListener() throws NoSuchMethodException {
        var method = observedMethod();
        Trigger trigger = mock(Trigger.class);
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.getScheduledJob(IDENTITY)).thenReturn(trigger);
        binder.bindMetrics(method, scheduler);

        ArgumentCaptor<Scheduler.EventListener> captor = ArgumentCaptor.forClass(Scheduler.EventListener.class);
        verify(scheduler).addJobListener(captor.capture());
        return captor.getValue();
    }

}
