package io.carbonintensity.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.assertj.core.api.Assertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.carbonintensity.executionplanner.planner.successive.SuccessivePlanner;
import io.carbonintensity.executionplanner.spi.CarbonIntensityApi;
import io.carbonintensity.scheduler.runtime.ImmutableScheduledMethod;
import io.carbonintensity.scheduler.runtime.ScheduledInvoker;
import io.carbonintensity.scheduler.runtime.SchedulerConfig;
import io.carbonintensity.scheduler.runtime.SimpleScheduler;
import io.carbonintensity.scheduler.test.helper.AnnotationUtil;
import io.carbonintensity.scheduler.test.helper.DisabledDummyCarbonIntensityApi;
import io.carbonintensity.scheduler.test.helper.MutableClock;

class TestSuccessiveWindowScheduler {

    private static final Logger log = LoggerFactory.getLogger(TestSuccessiveWindowScheduler.class);
    public static final long SCHEDULER_WAITING_PERIOD = 101L; // minimum accepted by Awaitility
    private SimpleScheduler scheduler;
    private final CarbonIntensityApi disabledApi = new DisabledDummyCarbonIntensityApi();
    private SchedulerConfig schedulerConfig;

    @BeforeEach
    public void beforeEach() {
        schedulerConfig = new SchedulerConfig();
        schedulerConfig.setCarbonIntensityApi(disabledApi);
    }

    @AfterEach
    public void afterEach() {
        if (scheduler != null) {
            scheduler.stop();
        }
    }

    @Test
    void testSuccessiveWindowScheduler() throws InterruptedException {
        CountDownLatch cdl = new CountDownLatch(2);

        ScheduledInvoker scheduledCountDownInvoker = execution -> {
            try {
                log.info("Invoked scheduledCountDownInvoker executing countdown...");
                cdl.countDown();
                return CompletableFuture.completedStage(null);
            } catch (Exception e) {
                return CompletableFuture.failedStage(e);
            }
        };

        GreenScheduled greenScheduled = AnnotationUtil.newGreenScheduled()
                .successive("12h 4h 12h")
                .zone("NL")
                .duration("1h")
                .identity("test")
                .overdueGracePeriod("PT90S")
                .timeZone("Europe/Amsterdam")
                .build();

        ImmutableScheduledMethod immutableScheduledMethod = new ImmutableScheduledMethod(
                scheduledCountDownInvoker,
                this.getClass().getName(),
                "testSuccessiveWindowScheduler",
                List.of(greenScheduled));

        ZoneId zone = ZoneId.of("UTC");
        // Create a mutable clock so that we can properly simulate running through a fixedTimeFrame
        MutableClock mutableClock = new MutableClock(
                Clock.fixed(ZonedDateTime
                        .of(LocalDateTime.of(LocalDate.of(2024, 6, 1),
                                LocalTime.of(7, 16)), ZoneId.of("Europe/Amsterdam"))
                        .toInstant(),
                        zone));

        schedulerConfig.setClock(mutableClock);
        scheduler = new SimpleScheduler(schedulerConfig);
        scheduler.scheduleMethod(immutableScheduledMethod);
        mutableClock.getNotifier().register(scheduler);
        scheduler.start();

        Thread.sleep(SCHEDULER_WAITING_PERIOD); // Sleep a few seconds, the greenest window is at 18:16 UTC, so it should not run yet.
        Assertions.assertThat(cdl.getCount()).isEqualTo(2);

        // shift the clock to 17:16 which is before the "most green time", so it still not run
        mutableClock.shift(Duration.ofHours(11));
        Thread.sleep(SCHEDULER_WAITING_PERIOD); // Sleep a few seconds, according to the schedule, it should not run.
        Assertions.assertThat(cdl.getCount()).isEqualTo(2);

        // shift the clock to 18:16:01 which is at the "most green time", so it should run
        mutableClock.shift(Duration.ofHours(1));
        mutableClock.shift(Duration.ofSeconds(1));

        Awaitility.waitAtMost(SCHEDULER_WAITING_PERIOD, TimeUnit.MILLISECONDS)
                .until(() -> cdl.getCount() == 1);

        mutableClock.shift(Duration.ofHours(4));
        Thread.sleep(SCHEDULER_WAITING_PERIOD); // Sleep a few seconds, it should not run twice within 4 hours
        Assertions.assertThat(cdl.getCount()).isEqualTo(1);

        mutableClock.shift(Duration.ofHours(8)); // Shift clock to 6:16:01
        Thread.sleep(SCHEDULER_WAITING_PERIOD); // Sleep a few seconds, Second run should be at 6:16:02 according to the planner
        Assertions.assertThat(cdl.getCount()).isEqualTo(1);

        mutableClock.shift(Duration.ofSeconds(1)); // Shift to 6:16:02

        Awaitility.waitAtMost(SCHEDULER_WAITING_PERIOD, TimeUnit.MILLISECONDS)
                .until(() -> cdl.getCount() == 0);
    }

    @Test
    void testSuccessiveWindowScheduler_useCalculatedFallbackInterval() throws InterruptedException {
        CountDownLatch cdl = new CountDownLatch(2);

        ScheduledInvoker scheduledCountDownInvoker = execution -> {
            try {
                log.info("Invoked scheduledCountDownInvoker executing countdown...");
                cdl.countDown();
                return CompletableFuture.completedStage(null);
            } catch (Exception e) {
                return CompletableFuture.failedStage(e);
            }
        };

        GreenScheduled greenScheduled = AnnotationUtil.newGreenScheduled()
                .successive("12h 4h 12h")
                .zone("NL")
                .duration("1h")
                .identity("test")
                .overdueGracePeriod("PT90S")
                .timeZone("Europe/Amsterdam")
                .build();

        ImmutableScheduledMethod immutableScheduledMethod = new ImmutableScheduledMethod(
                scheduledCountDownInvoker,
                this.getClass().getName(),
                "testSuccessiveWindowScheduler",
                List.of(greenScheduled));

        ZoneId zone = ZoneId.of("UTC");
        // Create a mutable clock so that we can properly simulate running through a fixedTimeFrame
        MutableClock mutableClock = new MutableClock(
                Clock.fixed(ZonedDateTime
                        .of(LocalDateTime.of(LocalDate.of(2024, 6, 1),
                                LocalTime.of(4, 16)), ZoneId.of("Europe/Amsterdam"))
                        .toInstant(),
                        zone));
        schedulerConfig.setClock(mutableClock);

        try (MockedConstruction<SuccessivePlanner> successivePlannerMockedConstruction = mockConstruction(
                SuccessivePlanner.class, (mock, context) -> {
                    when(mock.canSchedule(any())).thenReturn(false);
                });) {
            scheduler = new SimpleScheduler(schedulerConfig);
            scheduler.scheduleMethod(immutableScheduledMethod);
            mutableClock.getNotifier().register(scheduler);
            scheduler.start();
            mutableClock.getNotifier().check();

            // Wait a few seconds, when using the fallback, it should immediately run
            Awaitility.waitAtMost(SCHEDULER_WAITING_PERIOD, TimeUnit.MILLISECONDS)
                    .until(() -> cdl.getCount() == 1);

            // After that it should have an interval of 8 hours ((4 + 12) / 2)
            // shift the clock to 11:16, since we did not pass the 8 hours, it should not run
            mutableClock.shift(Duration.ofHours(7));
            Thread.sleep(SCHEDULER_WAITING_PERIOD); // Sleep a few seconds, according to the schedule, it should not run.
            Assertions.assertThat(cdl.getCount()).isEqualTo(1);

            // shift the clock to 12:17, which means we passed the 8 hours, it should run again
            mutableClock.shift(Duration.ofHours(1));
            mutableClock.shift(Duration.ofMinutes(1));

            Awaitility.waitAtMost(SCHEDULER_WAITING_PERIOD, TimeUnit.MILLISECONDS)
                    .until(() -> cdl.getCount() == 0);
            Assertions.assertThat(cdl.getCount()).isZero();
        }
    }
}
