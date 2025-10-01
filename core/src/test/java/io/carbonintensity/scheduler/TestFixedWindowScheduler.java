package io.carbonintensity.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
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

import io.carbonintensity.executionplanner.planner.fixedwindow.FixedWindowPlanner;
import io.carbonintensity.executionplanner.spi.CarbonIntensityApi;
import io.carbonintensity.scheduler.runtime.ImmutableScheduledMethod;
import io.carbonintensity.scheduler.runtime.ScheduledInvoker;
import io.carbonintensity.scheduler.runtime.SchedulerConfig;
import io.carbonintensity.scheduler.runtime.SimpleScheduler;
import io.carbonintensity.scheduler.runtime.SimpleSchedulerNotifier;
import io.carbonintensity.scheduler.test.helper.AnnotationUtil;
import io.carbonintensity.scheduler.test.helper.DisabledDummyCarbonIntensityApi;
import io.carbonintensity.scheduler.test.helper.MutableClock;

class TestFixedWindowScheduler {

    private static final Logger log = LoggerFactory.getLogger(TestFixedWindowScheduler.class);
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
    void testFixedWindowScheduler() throws InterruptedException {
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
                .fixedWindow("05:15 08:15")
                .zone("NL")
                .duration("2h")
                .identity("test")
                .overdueGracePeriod("PT90S")
                .timeZone("Europe/Amsterdam")
                .build();

        ImmutableScheduledMethod immutableScheduledMethod = new ImmutableScheduledMethod(
                scheduledCountDownInvoker,
                this.getClass().getName(),
                "testFixedWindowScheduler",
                List.of(greenScheduled));

        ZoneId zone = ZoneId.of("UTC");
        // Create a mutable clock so that we can properly simulate running through a fixedTimeFrame
        MutableClock mutableClock = new MutableClock(
                Clock.fixed(ZonedDateTime
                        .of(LocalDateTime.of(LocalDate.of(2024, 6, 1), LocalTime.of(4, 16)), ZoneId.of("Europe/Amsterdam"))
                        .toInstant(),
                        zone));

        schedulerConfig.setClock(mutableClock);
        scheduler = new SimpleScheduler(schedulerConfig);
        scheduler.scheduleMethod(immutableScheduledMethod);
        mutableClock.getNotifier().register(scheduler);

        scheduler.start();

        Thread.sleep(SCHEDULER_WAITING_PERIOD); // Sleep a few seconds, according to the schedule, it should not run.
        Assertions.assertThat(cdl.getCount()).isEqualTo(2);

        // shift the clock to 6:16 which within the window but is before the "most green time", so it still not run
        mutableClock.shift(Duration.ofHours(2));
        Thread.sleep(SCHEDULER_WAITING_PERIOD); // Sleep a few seconds, according to the schedule, it should not run.
        Assertions.assertThat(cdl.getCount()).isEqualTo(2);

        // shift the clock to 7:16 which is at the "most green time", so it should run
        mutableClock.shift(Duration.ofHours(1));

        Awaitility.waitAtMost(SCHEDULER_WAITING_PERIOD, TimeUnit.MILLISECONDS)
                .until(() -> cdl.getCount() == 1);

        mutableClock.shift(Duration.ofMinutes(4));
        Thread.sleep(SCHEDULER_WAITING_PERIOD); // Sleep a few seconds, it should not run twice
        Assertions.assertThat(cdl.getCount()).isEqualTo(1);

        mutableClock.shift(Duration.ofDays(1));
        Awaitility.waitAtMost(SCHEDULER_WAITING_PERIOD, TimeUnit.MILLISECONDS)
                .until(() -> cdl.getCount() == 0);
        Assertions.assertThat(cdl.getCount()).isZero();
    }

    @Test
    void testFixedWindowSchedulerMonday() throws InterruptedException {
        CountDownLatch cdl = new CountDownLatch(2);

        //always start the test on first sunday from now
        LocalDate sunday = LocalDate.of(2024, 6, 2);

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
                .fixedWindow("05:15 08:15")
                .zone("NL")
                .duration("2h")
                .identity("test")
                .overdueGracePeriod("PT90S")
                .timeZone("Europe/Amsterdam")
                .dayOfWeek("MON")
                .build();

        ImmutableScheduledMethod immutableScheduledMethod = new ImmutableScheduledMethod(
                scheduledCountDownInvoker,
                this.getClass().getName(),
                "testFixedWindowScheduler",
                List.of(greenScheduled));

        ZoneId zone = ZoneId.of("UTC");
        // Create a mutable clock so that we can properly simulate running through a fixedTimeFrame
        MutableClock mutableClock = new MutableClock(
                Clock.fixed(ZonedDateTime
                        .of(LocalDateTime.of(sunday, LocalTime.of(4, 16)),
                                ZoneId.of("Europe/Amsterdam"))
                        .toInstant(),
                        zone));

        schedulerConfig.setClock(mutableClock);
        scheduler = new SimpleScheduler(schedulerConfig);
        scheduler.scheduleMethod(immutableScheduledMethod);
        mutableClock.getNotifier().register(scheduler);

        scheduler.start();

        Thread.sleep(SCHEDULER_WAITING_PERIOD); // Sleep a few seconds, according to the schedule, it should not run.
        Assertions.assertThat(cdl.getCount()).isEqualTo(2);

        // shift the clock to 7:16 which is at the "most green time", but not on monday so it should not run yet
        mutableClock.shift(Duration.ofHours(3));

        Thread.sleep(SCHEDULER_WAITING_PERIOD);
        Assertions.assertThat(cdl.getCount()).isEqualTo(2);

        // shift the clock to next day 7:16 which is at the "most green time", so it should run
        mutableClock.shift(Duration.ofDays(1));
        Awaitility.waitAtMost(SCHEDULER_WAITING_PERIOD, TimeUnit.MILLISECONDS)
                .until(() -> cdl.getCount() == 1);

        mutableClock.shift(Duration.ofMinutes(4));
        Thread.sleep(SCHEDULER_WAITING_PERIOD); // Sleep a few seconds, it should not run twice
        Assertions.assertThat(cdl.getCount()).isEqualTo(1);

        mutableClock.shift(Duration.ofDays(6));
        Thread.sleep(SCHEDULER_WAITING_PERIOD);
        Assertions.assertThat(cdl.getCount()).isEqualTo(1);

        // shift the clock to next week 7:16 which is at the "most green time", so it should run
        mutableClock.shift(Duration.ofDays(1));
        Awaitility.waitAtMost(SCHEDULER_WAITING_PERIOD, TimeUnit.MILLISECONDS)
                .until(() -> cdl.getCount() == 0);
        Assertions.assertThat(cdl.getCount()).isZero();
    }

    @Test
    void testFixedWindowSchedulerSecondOfMonth() throws InterruptedException {
        CountDownLatch cdl = new CountDownLatch(2);

        // always start the test on 1st day of month (simulated)
        ZonedDateTime initialDate = ZonedDateTime.of(
                LocalDate.of(2024, 6, 1),
                LocalTime.of(4, 16),
                ZoneId.of("Europe/Amsterdam"));
        ZoneId zone = ZoneId.of("UTC");

        MutableClock mutableClock = new MutableClock(
                Clock.fixed(initialDate.toInstant(), zone));

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
                .fixedWindow("05:15 08:15")
                .zone("NL")
                .duration("2h")
                .identity("test")
                .overdueGracePeriod("PT90S")
                .timeZone("Europe/Amsterdam")
                .dayOfMonth("2")
                .build();

        ImmutableScheduledMethod immutableScheduledMethod = new ImmutableScheduledMethod(
                scheduledCountDownInvoker,
                this.getClass().getName(),
                "testFixedWindowScheduler",
                List.of(greenScheduled));

        schedulerConfig.setClock(mutableClock);
        scheduler = new SimpleScheduler(schedulerConfig);
        scheduler.scheduleMethod(immutableScheduledMethod);
        mutableClock.getNotifier().register(scheduler);
        scheduler.start();

        Thread.sleep(SCHEDULER_WAITING_PERIOD);
        Assertions.assertThat(cdl.getCount()).isEqualTo(2);

        // shift to 7:16 on the 1st — still not allowed (day is wrong)
        mutableClock.shift(Duration.ofHours(3));
        Thread.sleep(SCHEDULER_WAITING_PERIOD);
        Assertions.assertThat(cdl.getCount()).isEqualTo(2);

        // shift to 7:16 on the 2nd — should run once
        mutableClock.shift(Duration.ofDays(1));
        Awaitility.await()
                .atMost(SCHEDULER_WAITING_PERIOD, TimeUnit.MILLISECONDS)
                .until(() -> cdl.getCount() == 1);

        // advance 4 more minutes — still within window, but should not re-run
        mutableClock.shift(Duration.ofMinutes(4));
        Thread.sleep(SCHEDULER_WAITING_PERIOD);
        Assertions.assertThat(cdl.getCount()).isEqualTo(1);

        // shift to 3rd — should not run
        mutableClock.shift(Duration.ofDays(1));
        Thread.sleep(SCHEDULER_WAITING_PERIOD);
        Assertions.assertThat(cdl.getCount()).isEqualTo(1);

        // === SHIFT TO NEXT MONTH 2nd ===
        ZonedDateTime currentTime = ZonedDateTime.now(mutableClock);
        YearMonth currentMonth = YearMonth.from(currentTime);
        int daysInMonth = currentMonth.lengthOfMonth();

        int remainingDaysThisMonth = daysInMonth - currentTime.getDayOfMonth();
        int daysToNextMonth2nd = remainingDaysThisMonth + 2;

        mutableClock.shift(Duration.ofDays(daysToNextMonth2nd));

        Awaitility.await()
                .atMost(SCHEDULER_WAITING_PERIOD, TimeUnit.MILLISECONDS)
                .until(() -> cdl.getCount() == 0);
        Assertions.assertThat(cdl.getCount()).isZero();
    }

    @Test
    void testFixedWindowSchedulerSecondAndFifteenthOfMonth() throws InterruptedException {
        CountDownLatch cdl = new CountDownLatch(3);

        // start test on 1st of current month at 04:16 Amsterdam time
        ZonedDateTime initialDate = ZonedDateTime.of(
                LocalDate.of(2024, 6, 1),
                LocalTime.of(4, 16),
                ZoneId.of("Europe/Amsterdam"));
        ZoneId utcZone = ZoneId.of("UTC");

        MutableClock mutableClock = new MutableClock(
                Clock.fixed(initialDate.toInstant(), utcZone));

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
                .fixedWindow("05:15 08:15")
                .zone("NL")
                .duration("2h")
                .identity("test")
                .overdueGracePeriod("PT90S")
                .timeZone("Europe/Amsterdam")
                .dayOfMonth("2,15")
                .build();

        ImmutableScheduledMethod immutableScheduledMethod = new ImmutableScheduledMethod(
                scheduledCountDownInvoker,
                this.getClass().getName(),
                "testFixedWindowScheduler",
                List.of(greenScheduled));

        schedulerConfig.setClock(mutableClock);
        scheduler = new SimpleScheduler(schedulerConfig);
        scheduler.scheduleMethod(immutableScheduledMethod);
        mutableClock.getNotifier().register(scheduler);
        scheduler.start();

        // 1st at 04:16 -> should NOT run yet
        Thread.sleep(SCHEDULER_WAITING_PERIOD);
        Assertions.assertThat(cdl.getCount()).isEqualTo(3);

        // Shift to 07:16 on the 1st (inside green window, but wrong day)
        mutableClock.shift(Duration.ofHours(3));
        Thread.sleep(SCHEDULER_WAITING_PERIOD);
        Assertions.assertThat(cdl.getCount()).isEqualTo(3);

        // Shift to 07:16 on the 2nd (valid green execution day)
        mutableClock.shift(Duration.ofDays(1));
        Awaitility.await().atMost(SCHEDULER_WAITING_PERIOD, TimeUnit.MILLISECONDS)
                .until(() -> cdl.getCount() == 2);

        // Still 2nd, within window but should not re-run
        mutableClock.shift(Duration.ofMinutes(4));
        Thread.sleep(SCHEDULER_WAITING_PERIOD);
        Assertions.assertThat(cdl.getCount()).isEqualTo(2);

        // Shift to 14th — not a trigger day
        mutableClock.shift(Duration.ofDays(12));
        Thread.sleep(SCHEDULER_WAITING_PERIOD);
        Assertions.assertThat(cdl.getCount()).isEqualTo(2);

        // Shift to 15th — valid day
        mutableClock.shift(Duration.ofDays(1));
        Awaitility.await().atMost(SCHEDULER_WAITING_PERIOD, TimeUnit.MILLISECONDS)
                .until(() -> cdl.getCount() == 1);
        Assertions.assertThat(cdl.getCount()).isEqualTo(1);

        // === SHIFT TO NEXT MONTH 2nd ===
        ZonedDateTime currentTime = ZonedDateTime.now(mutableClock);
        YearMonth currentMonth = YearMonth.from(currentTime);
        int daysInMonth = currentMonth.lengthOfMonth();

        int remainingDaysThisMonth = daysInMonth - currentTime.getDayOfMonth();
        int daysToNextMonth2nd = remainingDaysThisMonth + 2;

        mutableClock.shift(Duration.ofDays(daysToNextMonth2nd));

        Awaitility.await().atMost(SCHEDULER_WAITING_PERIOD, TimeUnit.MILLISECONDS)
                .until(() -> cdl.getCount() == 0);
        Assertions.assertThat(cdl.getCount()).isZero();
    }

    @Test
    void testFixedWindowSchedulerWorkingDays() throws InterruptedException {
        CountDownLatch cdl = new CountDownLatch(6);

        //always start the test on first sunday from now
        LocalDate sunday = LocalDate.of(2024, 6, 2);

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
                .fixedWindow("05:15 08:15")
                .zone("NL")
                .duration("2h")
                .identity("test")
                .overdueGracePeriod("PT90S")
                .timeZone("Europe/Amsterdam")
                .dayOfWeek("MON-FRI")
                .build();

        ImmutableScheduledMethod immutableScheduledMethod = new ImmutableScheduledMethod(
                scheduledCountDownInvoker,
                this.getClass().getName(),
                "testFixedWindowScheduler",
                List.of(greenScheduled));

        ZoneId zone = ZoneId.of("UTC");
        // Create a mutable clock so that we can properly simulate running through a fixedTimeFrame
        MutableClock mutableClock = new MutableClock(
                Clock.fixed(ZonedDateTime
                        .of(LocalDateTime.of(sunday, LocalTime.of(4, 16)),
                                ZoneId.of("Europe/Amsterdam"))
                        .toInstant(),
                        zone));

        schedulerConfig.setClock(mutableClock);
        scheduler = new SimpleScheduler(schedulerConfig);
        scheduler.scheduleMethod(immutableScheduledMethod);
        mutableClock.getNotifier().register(scheduler);

        scheduler.start();

        Thread.sleep(SCHEDULER_WAITING_PERIOD); // Sleep a few seconds, according to the schedule, it should not run.
        Assertions.assertThat(cdl.getCount()).isEqualTo(6);

        // shift the clock to 7:16 which is at the "most green time", but not on monday so it should not run yet
        mutableClock.shift(Duration.ofHours(3));

        Thread.sleep(SCHEDULER_WAITING_PERIOD);
        Assertions.assertThat(cdl.getCount()).isEqualTo(6);

        int counter = 5;
        while (counter > 0) {
            mutableClock.shift(Duration.ofDays(1));
            int countEffectiveFinal = counter;
            Awaitility.waitAtMost(SCHEDULER_WAITING_PERIOD, TimeUnit.MILLISECONDS)
                    .until(() -> cdl.getCount() == countEffectiveFinal);
            mutableClock.shift(Duration.ofMinutes(4));
            Thread.sleep(SCHEDULER_WAITING_PERIOD); // Sleep a few seconds, it should not run twice
            Assertions.assertThat(cdl.getCount()).isEqualTo(counter);
            counter--;
        }

        // shift the clock to next sunday 7:16 which is a weekend day so it should not have run anymore
        mutableClock.shift(Duration.ofDays(2));
        Thread.sleep(SCHEDULER_WAITING_PERIOD); // Sleep a few seconds, according to the schedule, it should not run.
        Assertions.assertThat(cdl.getCount()).isEqualTo(1);

        //shift the clock to next monday, for one last run
        mutableClock.shift(Duration.ofDays(1));
        Awaitility.waitAtMost(SCHEDULER_WAITING_PERIOD, TimeUnit.MILLISECONDS)
                .until(() -> cdl.getCount() == 0);
        mutableClock.shift(Duration.ofMinutes(4));
        Thread.sleep(SCHEDULER_WAITING_PERIOD); // Sleep a few seconds, it should not run twice
        Assertions.assertThat(cdl.getCount()).isZero();
    }

    @Test
    void testFixedWindowScheduler_useFallbackCron() throws InterruptedException {
        CountDownLatch cdl = new CountDownLatch(1);

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
                .fixedWindow("05:15 08:15")
                .zone("NL")
                .duration("2h")
                .identity("test")
                .cron("0 15 10 * * ?")
                .overdueGracePeriod("PT90S")
                .timeZone("Europe/Amsterdam")
                .build();

        ImmutableScheduledMethod immutableScheduledMethod = new ImmutableScheduledMethod(
                scheduledCountDownInvoker,
                this.getClass().getName(),
                "testFixedWindowScheduler",
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

        try (MockedConstruction<FixedWindowPlanner> fixedWindowPlannerMockedConstruction = mockConstruction(
                FixedWindowPlanner.class, (mock, context) -> when(mock.canSchedule(any())).thenReturn(false))) {
            scheduler = new SimpleScheduler(schedulerConfig);
            scheduler.scheduleMethod(immutableScheduledMethod);
            mutableClock.getNotifier().register(scheduler);
            scheduler.start();

            Thread.sleep(SCHEDULER_WAITING_PERIOD); // Sleep a few seconds, according to the schedule, it should not run.
            Assertions.assertThat(cdl.getCount()).isEqualTo(1);

            // shift the clock to 8:16, since we cannot schedule we still won't run.
            mutableClock.shift(Duration.ofHours(4));
            Thread.sleep(SCHEDULER_WAITING_PERIOD); // Sleep a few seconds, according to the schedule, it should not run.
            Assertions.assertThat(cdl.getCount()).isEqualTo(1);

            // shift the clock to 10:16, according to our cron it should run at 10:15
            mutableClock.shift(Duration.ofHours(2));

            Awaitility.waitAtMost(SCHEDULER_WAITING_PERIOD, TimeUnit.MILLISECONDS)
                    .until(() -> cdl.getCount() == 0);

            mutableClock.shift(Duration.ofMinutes(4));
            Thread.sleep(SCHEDULER_WAITING_PERIOD); // Sleep a few seconds, it should not run twice
            Assertions.assertThat(cdl.getCount()).isZero();
        }
    }

    @Test
    void testFixedWindowScheduler_useCalculatedFallbackCron() throws InterruptedException {
        CountDownLatch cdl = new CountDownLatch(1);

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
                .fixedWindow("05:15 08:15")
                .zone("NL")
                .duration("2h")
                .identity("test")
                .overdueGracePeriod("PT90S")
                .timeZone("Europe/Amsterdam")
                .build();

        ImmutableScheduledMethod immutableScheduledMethod = new ImmutableScheduledMethod(
                scheduledCountDownInvoker,
                this.getClass().getName(),
                "testFixedWindowScheduler",
                List.of(greenScheduled));

        ZoneId zone = ZoneId.of("UTC");
        // Create a mutable clock so that we can properly simulate running through a fixedTimeFrame
        MutableClock mutableClock = new MutableClock(
                Clock.fixed(ZonedDateTime
                        .of(LocalDateTime.of(LocalDate.of(2025, 6, 1),
                                LocalTime.of(4, 44)), ZoneId.of("Europe/Amsterdam"))
                        .toInstant(),
                        zone));
        schedulerConfig.setClock(mutableClock);

        try (MockedConstruction<FixedWindowPlanner> fixedWindowPlannerMockedConstruction = mockConstruction(
                FixedWindowPlanner.class, (mock, context) -> when(mock.canSchedule(any())).thenReturn(false))) {
            scheduler = new SimpleScheduler(schedulerConfig);
            scheduler.scheduleMethod(immutableScheduledMethod);
            mutableClock.getNotifier().register(scheduler);
            scheduler.start();

            Thread.sleep(SCHEDULER_WAITING_PERIOD); // Sleep a few seconds, according to the schedule, it should not run.
            Assertions.assertThat(cdl.getCount()).isEqualTo(1);

            // shift the clock to 6:44, since we cannot schedule we still won't run.
            mutableClock.shift(Duration.ofHours(2));
            Thread.sleep(SCHEDULER_WAITING_PERIOD); // Sleep a few seconds, according to the schedule, it should not run.
            Assertions.assertThat(cdl.getCount()).isEqualTo(1);

            // shift the clock to 6:46, according to the calculated cron it should run at 6:45
            mutableClock.shift(Duration.ofMinutes(2));

            Awaitility.waitAtMost(SCHEDULER_WAITING_PERIOD, TimeUnit.MILLISECONDS)
                    .until(() -> cdl.getCount() == 0);

            mutableClock.shift(Duration.ofMinutes(4));
            Thread.sleep(SCHEDULER_WAITING_PERIOD); // Sleep a few seconds, it should not run twice
            Assertions.assertThat(cdl.getCount()).isZero();
        }
    }

    @Test
    void testFixedWindowScheduler_executedDueToGracePeriod() {
        CountDownLatch cdl = new CountDownLatch(1);

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
                .fixedWindow("05:15 08:15")
                .zone("NL")
                .duration("2h")
                .identity("test")
                .overdueGracePeriod("PT90S")
                .timeZone("Europe/Amsterdam")
                .build();

        ImmutableScheduledMethod immutableScheduledMethod = new ImmutableScheduledMethod(
                scheduledCountDownInvoker,
                this.getClass().getName(),
                "testFixedWindowScheduler_executedDueToGracePeriod",
                List.of(greenScheduled));

        Clock fixedClock = Clock
                .fixed(ZonedDateTime
                        .of(LocalDateTime.of(LocalDate.of(2025, 6, 1),
                                LocalTime.of(8, 15, 30)), ZoneId.of("Europe/Amsterdam"))
                        .toInstant(), ZoneId.systemDefault());

        schedulerConfig.setClock(fixedClock);
        scheduler = new SimpleScheduler(schedulerConfig);
        scheduler.scheduleMethod(immutableScheduledMethod);

        Assertions.assertThat(cdl.getCount()).isEqualTo(1);
        scheduler.start();
        SimpleSchedulerNotifier notifier = new SimpleSchedulerNotifier();
        notifier.register(scheduler);
        notifier.check();

        Awaitility.waitAtMost(SCHEDULER_WAITING_PERIOD, TimeUnit.MILLISECONDS)
                .until(() -> cdl.getCount() == 0);
        Assertions.assertThat(cdl.getCount()).isZero();
    }

    @Test
    void testFixedWindowScheduler_neverExecutedDueToSkipOfTimeFrame() throws InterruptedException {
        CountDownLatch cdl = new CountDownLatch(1);

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
                .fixedWindow("05:15 08:15")
                .zone("NL")
                .duration("2h")
                .identity("test")
                .overdueGracePeriod("PT90S")
                .timeZone("Europe/Amsterdam")
                .build();

        ImmutableScheduledMethod immutableScheduledMethod = new ImmutableScheduledMethod(
                scheduledCountDownInvoker,
                this.getClass().getName(),
                "testFixedWindowScheduler_neverExecutedDueToSkipOfTimeFrame",
                List.of(greenScheduled));

        //  Set the time just after endTime (8:15) + overDueGracePeriod (90s)
        Clock fixedClock = Clock
                .fixed(ZonedDateTime.of(LocalDateTime.of(LocalDate.of(2025, 6, 1),
                        LocalTime.of(8, 17)), ZoneId.of("Europe/Amsterdam"))
                        .toInstant(),
                        ZoneId.systemDefault());

        schedulerConfig.setClock(fixedClock);
        scheduler = new SimpleScheduler(schedulerConfig);
        scheduler.scheduleMethod(immutableScheduledMethod);

        Assertions.assertThat(cdl.getCount()).isEqualTo(1);
        scheduler.start();

        Thread.sleep(SCHEDULER_WAITING_PERIOD); // Wait a few seconds, to give the scheduler time assess if it should run.
        Assertions.assertThat(cdl.getCount()).isEqualTo(1);
    }

    @Test
    void testFixedWindowOvernightScheduler() throws InterruptedException {
        CountDownLatch cdl = new CountDownLatch(1);

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
                .fixedWindow("23:15 02:15")
                .zone("NL")
                .duration("2h")
                .identity("test")
                .overdueGracePeriod("PT90S")
                .timeZone("Europe/Amsterdam")
                .build();

        ImmutableScheduledMethod immutableScheduledMethod = new ImmutableScheduledMethod(
                scheduledCountDownInvoker,
                this.getClass().getName(),
                "testFixedWindowOvernightScheduler",
                List.of(greenScheduled));

        ZoneId zone = ZoneId.of("UTC");
        // Create a mutable clock so that we can properly simulate running through a fixedTimeFrame
        MutableClock mutableClock = new MutableClock(
                Clock.fixed(ZonedDateTime
                        .of(LocalDateTime.of(LocalDate.of(2025, 6, 1),
                                LocalTime.of(22, 16)), ZoneId.of("Europe/Amsterdam"))
                        .toInstant(),
                        zone));

        schedulerConfig.setClock(mutableClock);
        scheduler = new SimpleScheduler(schedulerConfig);
        scheduler.scheduleMethod(immutableScheduledMethod);
        mutableClock.getNotifier().register(scheduler);
        scheduler.start();

        Thread.sleep(SCHEDULER_WAITING_PERIOD); // Sleep a few seconds, according to the schedule, it should not run.
        Assertions.assertThat(cdl.getCount()).isEqualTo(1);

        // shift the clock to 00:16 which within the window but is before the "most green time", so it still not run
        mutableClock.shift(Duration.ofHours(2));
        Thread.sleep(SCHEDULER_WAITING_PERIOD); // Sleep a few seconds, according to the schedule, it should not run.
        Assertions.assertThat(cdl.getCount()).isEqualTo(1);

        // shift the clock to 1:16 which is at the "most green time", so it should run
        mutableClock.shift(Duration.ofHours(1));

        Awaitility.waitAtMost(SCHEDULER_WAITING_PERIOD, TimeUnit.MILLISECONDS)
                .until(() -> cdl.getCount() == 0);
        Assertions.assertThat(cdl.getCount()).isZero();
    }
}
