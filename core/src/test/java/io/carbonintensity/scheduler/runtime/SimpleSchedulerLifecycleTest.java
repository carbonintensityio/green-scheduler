package io.carbonintensity.scheduler.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.Scheduler;
import io.carbonintensity.scheduler.Trigger;
import io.carbonintensity.scheduler.test.helper.AnnotationUtil;
import io.carbonintensity.scheduler.test.helper.DisabledDummyCarbonIntensityApi;

/**
 * {@code pause()}/{@code resume()}/{@code isRunning()} had zero coverage at all (CIIO-340,
 * NO_COVERAGE mutants) - no existing test ever calls them. These are the scheduler-wide (not
 * per-job) lifecycle operations.
 * <p>
 * This class also covers the per-identity {@code pause(String)}/{@code resume(String)}/
 * {@code isPaused(String)} variants and {@code unscheduleJob(String)}, which need a scheduled
 * task and were originally left for a follow-up (CIIO-340).
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

    private SimpleScheduler enabledScheduler() {
        SchedulerConfig config = new SchedulerConfig();
        config.setCarbonIntensityApi(new DisabledDummyCarbonIntensityApi());
        scheduler = new SimpleScheduler(config);
        return scheduler;
    }

    private Trigger scheduleProgrammaticJob(SimpleScheduler scheduler, String identity, Runnable onRun) {
        return scheduler.newJob(identity)
                .setCarbonIntensityZone("NL")
                .setMinimumGap(Duration.ofSeconds(1))
                .setMaximumGap(Duration.ofSeconds(1))
                .setDuration(Duration.ofSeconds(1))
                .setTask(execution -> onRun.run())
                .schedule();
    }

    // --- unscheduleJob(String) ---

    @Test
    void unscheduleJobRemovesAProgrammaticJobAndReturnsItsTrigger() {
        SimpleScheduler scheduler = enabledScheduler();
        Trigger trigger = scheduleProgrammaticJob(scheduler, "job-1", () -> {
        });
        assertThat(scheduler.getScheduledJob("job-1")).isSameAs(trigger);

        Trigger unscheduled = scheduler.unscheduleJob("job-1");

        assertThat(unscheduled).isSameAs(trigger);
        assertThat(scheduler.getScheduledJob("job-1")).isNull();
        assertThat(scheduler.getScheduledJobs()).doesNotContain(trigger);
    }

    @Test
    void unscheduleJobReturnsNullForAnUnknownIdentity() {
        SimpleScheduler scheduler = enabledScheduler();

        assertThat(scheduler.unscheduleJob("does-not-exist")).isNull();
    }

    @Test
    void unscheduleJobReturnsNullForAnEmptyIdentity() {
        SimpleScheduler scheduler = enabledScheduler();

        assertThat(scheduler.unscheduleJob("")).isNull();
    }

    @Test
    void unscheduleJobThrowsOnNullIdentity() {
        SimpleScheduler scheduler = enabledScheduler();

        assertThatThrownBy(() -> scheduler.unscheduleJob(null)).isInstanceOf(NullPointerException.class);
    }

    // Kills the "task.isProgrammatic" mutant: a job scheduled via an annotation (scheduleMethod)
    // must NOT be removable through unscheduleJob, which is documented as the counterpart of
    // newJob()/programmatic scheduling only.
    @Test
    void unscheduleJobIsANoOpForAnAnnotationScheduledJob() {
        SimpleScheduler scheduler = enabledScheduler();
        CountDownLatch cdl = new CountDownLatch(1);
        ScheduledInvoker invoker = execution -> {
            cdl.countDown();
            return CompletableFuture.completedStage(null);
        };
        GreenScheduled greenScheduled = AnnotationUtil.newGreenScheduled()
                .successive("1s 1s 1s")
                .carbonIntensityZone("NL")
                .duration("1s")
                .identity("annotation-job")
                .build();
        scheduler.scheduleMethod(new ImmutableScheduledMethod(invoker, this.getClass().getName(),
                "unscheduleJobIsANoOpForAnAnnotationScheduledJob", List.of(greenScheduled)));
        Trigger trigger = scheduler.getScheduledJob("annotation-job");
        assertThat(trigger).isNotNull();

        assertThat(scheduler.unscheduleJob("annotation-job")).isNull();

        assertThat(scheduler.getScheduledJob("annotation-job")).isSameAs(trigger);
    }

    // --- pause(String) / resume(String) / isPaused(String) ---

    @Test
    void pauseByIdentityPausesOnlyThatJobAndFiresJobPaused() {
        SimpleScheduler scheduler = enabledScheduler();
        Trigger pausedTrigger = scheduleProgrammaticJob(scheduler, "job-1", () -> {
        });
        scheduleProgrammaticJob(scheduler, "job-2", () -> {
        });
        List<Trigger> pausedEvents = new ArrayList<>();
        scheduler.addJobListener(new Scheduler.EventListener() {
            @Override
            public void jobPaused(Trigger trigger) {
                pausedEvents.add(trigger);
            }
        });

        scheduler.pause("job-1");

        assertThat(scheduler.isPaused("job-1")).isTrue();
        assertThat(scheduler.isPaused("job-2")).isFalse();
        assertThat(pausedEvents).containsExactly(pausedTrigger);
    }

    @Test
    void resumeByIdentityResumesOnlyThatJobAndFiresJobResumed() {
        SimpleScheduler scheduler = enabledScheduler();
        Trigger resumedTrigger = scheduleProgrammaticJob(scheduler, "job-1", () -> {
        });
        scheduleProgrammaticJob(scheduler, "job-2", () -> {
        });
        scheduler.pause("job-1");
        scheduler.pause("job-2");
        List<Trigger> resumedEvents = new ArrayList<>();
        scheduler.addJobListener(new Scheduler.EventListener() {
            @Override
            public void jobResumed(Trigger trigger) {
                resumedEvents.add(trigger);
            }
        });

        scheduler.resume("job-1");

        assertThat(scheduler.isPaused("job-1")).isFalse();
        assertThat(scheduler.isPaused("job-2")).isTrue();
        assertThat(resumedEvents).containsExactly(resumedTrigger);
    }

    @Test
    void pauseAndResumeByIdentityAreNoOpsForAnUnknownIdentity() {
        SimpleScheduler scheduler = enabledScheduler();
        AtomicInteger events = new AtomicInteger();
        scheduler.addJobListener(new Scheduler.EventListener() {
            @Override
            public void jobPaused(Trigger trigger) {
                events.incrementAndGet();
            }

            @Override
            public void jobResumed(Trigger trigger) {
                events.incrementAndGet();
            }
        });

        scheduler.pause("does-not-exist");
        scheduler.resume("does-not-exist");

        assertThat(events).hasValue(0);
        assertThat(scheduler.isPaused("does-not-exist")).isFalse();
    }

    @Test
    void pauseAndResumeByIdentityAreNoOpsForAnEmptyIdentity() {
        SimpleScheduler scheduler = enabledScheduler();
        AtomicInteger events = new AtomicInteger();
        scheduler.addJobListener(new Scheduler.EventListener() {
            @Override
            public void jobPaused(Trigger trigger) {
                events.incrementAndGet();
            }

            @Override
            public void jobResumed(Trigger trigger) {
                events.incrementAndGet();
            }
        });

        scheduler.pause("");
        scheduler.resume("");

        assertThat(events).hasValue(0);
        assertThat(scheduler.isPaused("")).isFalse();
    }

    @Test
    void pauseResumeAndIsPausedThrowOnNullIdentity() {
        SimpleScheduler scheduler = enabledScheduler();

        assertThatThrownBy(() -> scheduler.pause((String) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> scheduler.resume((String) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> scheduler.isPaused(null)).isInstanceOf(NullPointerException.class);
    }

    // Integration-level proof that pause(identity) doesn't just flip a flag: the paused job's
    // invoker is never actually called by ScheduledTask.execute(), while an unpaused sibling job
    // keeps firing normally in the same scheduler.
    @Test
    void pauseByIdentityActuallyPreventsThatJobFromFiringWhileOthersKeepRunning() {
        SimpleScheduler scheduler = enabledScheduler();
        AtomicInteger pausedRuns = new AtomicInteger();
        AtomicInteger activeRuns = new AtomicInteger();

        scheduleProgrammaticJob(scheduler, "paused-job", pausedRuns::incrementAndGet);
        scheduleProgrammaticJob(scheduler, "active-job", activeRuns::incrementAndGet);
        scheduler.pause("paused-job");

        Awaitility.waitAtMost(30, TimeUnit.SECONDS).until(() -> activeRuns.get() >= 1);

        assertThat(pausedRuns.get()).isZero();
    }
}
