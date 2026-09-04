package io.carbonintensity.scheduler.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.carbonintensity.scheduler.Scheduler;
import io.carbonintensity.scheduler.test.helper.DisabledDummyCarbonIntensityApi;

/**
 * {@code pause()}/{@code resume()}/{@code isRunning()} had zero coverage at all (CIIO-340,
 * NO_COVERAGE mutants) - no existing test ever calls them. These are the scheduler-wide (not
 * per-job) lifecycle operations; the per-identity {@code pause(String)}/{@code resume(String)}/
 * {@code isPaused(String)} variants need a scheduled task and are left for a follow-up.
 */
class SimpleSchedulerLifecycleTest {

    private SimpleScheduler scheduler;

    @AfterEach
    void afterEach() {
        if (scheduler != null) {
            scheduler.close();
        }
    }

    private SimpleScheduler startedSchedulerWith(boolean enabled) {
        SchedulerConfig config = new SchedulerConfig();
        config.setEnabled(enabled);
        config.setCarbonIntensityApi(new DisabledDummyCarbonIntensityApi());
        scheduler = new SimpleScheduler(config);
        scheduler.start();
        return scheduler;
    }

    @Test
    void pauseStopsARunningSchedulerAndFiresSchedulerPaused() {
        SimpleScheduler scheduler = startedSchedulerWith(true);
        AtomicInteger pausedEvents = new AtomicInteger();
        scheduler.addJobListener(new Scheduler.EventListener() {
            @Override
            public void schedulerPaused() {
                pausedEvents.incrementAndGet();
            }
        });
        // start() on an enabled, NORMAL-mode scheduler already leaves it running - confirm the
        // precondition so the assertion after pause() actually proves a state change.
        assertThat(scheduler.isRunning()).isTrue();

        scheduler.pause();

        assertThat(scheduler.isRunning()).isFalse();
        assertThat(pausedEvents).hasValue(1);
    }

    @Test
    void resumeRestartsAPausedSchedulerAndFiresSchedulerResumed() {
        SimpleScheduler scheduler = startedSchedulerWith(true);
        AtomicInteger resumedEvents = new AtomicInteger();
        scheduler.addJobListener(new Scheduler.EventListener() {
            @Override
            public void schedulerResumed() {
                resumedEvents.incrementAndGet();
            }
        });
        scheduler.pause();
        assertThat(scheduler.isRunning()).isFalse();

        scheduler.resume();

        assertThat(scheduler.isRunning()).isTrue();
        assertThat(resumedEvents).hasValue(1);
    }

    @Test
    void pauseAndResumeAreNoOpsOnADisabledSchedulerAndFireNoEvent() {
        SimpleScheduler scheduler = startedSchedulerWith(false);
        AtomicInteger events = new AtomicInteger();
        scheduler.addJobListener(new Scheduler.EventListener() {
            @Override
            public void schedulerPaused() {
                events.incrementAndGet();
            }

            @Override
            public void schedulerResumed() {
                events.incrementAndGet();
            }
        });

        // Kills the "negated conditional" mutants on the !enabled guards in pause()/resume():
        // a disabled scheduler must log-and-return, not toggle running or fire an event.
        scheduler.pause();
        scheduler.resume();

        assertThat(scheduler.isRunning()).isFalse();
        assertThat(events).hasValue(0);
    }

    @Test
    void isRunningIsFalseWhenDisabledEvenAfterStart() {
        SimpleScheduler scheduler = startedSchedulerWith(false);

        assertThat(scheduler.isRunning()).isFalse();
    }
}
