package io.carbonintensity.scheduler.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.carbonintensity.executionplanner.planner.fixedwindow.FixedWindowPlanningConstraints;
import io.carbonintensity.executionplanner.spi.CarbonIntensityPlanner;
import io.carbonintensity.executionplanner.spi.PlanningConstraints;
import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.Trigger;
import io.carbonintensity.scheduler.observability.GreenObserved;
import io.carbonintensity.scheduler.runtime.impl.annotation.GreenScheduledAnnotationParser;
import io.carbonintensity.scheduler.test.helper.AnnotationUtil;
import io.carbonintensity.scheduler.test.helper.DisabledDummyCarbonIntensityApi;

/**
 * Verifies the {@link Trigger} extensions added to carry Micrometer tag/state data out to an extension (see CIIO-348):
 * {@link Trigger#getStrategy()}, {@link Trigger#getCarbonIntensityZone()}, {@link Trigger#getGreenObserved()} and,
 * for {@code fixedWindow} jobs only, {@link Trigger#getLastWindowFireMode()}.
 */
class TriggerObservabilityExtensionsTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");

    private SimpleScheduler scheduler;

    @AfterEach
    void afterEach() {
        if (scheduler != null) {
            scheduler.close();
        }
    }

    @Test
    void fixedWindowTriggerReportsFixedWindowStrategy() {
        SimpleScheduler.SimpleTrigger trigger = (SimpleScheduler.SimpleTrigger) newTrigger(fixedWindowSchedule(),
                Clock.system(ZONE));

        assertThat(trigger.getStrategy()).isEqualTo(Trigger.Strategy.FIXED_WINDOW);
    }

    @Test
    void successiveTriggerReportsSuccessiveStrategy() {
        GreenScheduled successive = AnnotationUtil.newGreenScheduled()
                .identity("test")
                .successive("0h 1h 4h")
                .duration("15m")
                .carbonIntensityZone("NL")
                .build();

        SimpleScheduler.SimpleTrigger trigger = (SimpleScheduler.SimpleTrigger) newTrigger(successive, Clock.system(ZONE));

        assertThat(trigger.getStrategy()).isEqualTo(Trigger.Strategy.SUCCESSIVE);
    }

    @Test
    void triggerReportsItsConfiguredZone() {
        SimpleScheduler.SimpleTrigger trigger = (SimpleScheduler.SimpleTrigger) newTrigger(fixedWindowSchedule(),
                Clock.system(ZONE));

        assertThat(trigger.getCarbonIntensityZone()).isEqualTo("NL");
    }

    @Test
    void triggerReportsItsGreenObservedConfigurationOnceSet() {
        SimpleScheduler.SimpleTrigger trigger = (SimpleScheduler.SimpleTrigger) newTrigger(fixedWindowSchedule(),
                Clock.system(ZONE));

        assertThat(trigger.getGreenObserved()).isEmpty();

        GreenObserved observed = AnnotationUtil.newGreenObserved().carbonImpact(true).build();
        trigger.setGreenObserved(observed);

        assertThat(trigger.getGreenObserved()).contains(observed);
    }

    @Test
    void fixedWindowFiringThroughThePlannerReportsOptimizedMode() {
        ZonedDateTime windowStart = at(LocalDate.of(2026, 9, 4), LocalTime.of(9, 30));
        ZonedDateTime plannedFireTime = at(LocalDate.of(2026, 9, 4), LocalTime.of(9, 45));
        // the window is computed relative to "now" at parse time, so pin the clock to the same day being evaluated
        Clock parseTimeClock = Clock.fixed(at(LocalDate.of(2026, 9, 4), LocalTime.of(0, 0)).toInstant(), ZONE);
        FixedWindowPlanningConstraints constraints = fixedWindowConstraints(parseTimeClock);

        CarbonIntensityPlanner<FixedWindowPlanningConstraints> alwaysSchedulableAt = new CarbonIntensityPlanner<>() {
            @Override
            public boolean canSchedule(FixedWindowPlanningConstraints c) {
                return true;
            }

            @Override
            public ZonedDateTime getNextExecutionTime(FixedWindowPlanningConstraints c) {
                return plannedFireTime;
            }
        };

        SimpleScheduler.FixedWindowTrigger trigger = new SimpleScheduler.FixedWindowTrigger("test", "Test#test",
                Duration.ofSeconds(30), alwaysSchedulableAt, constraints, parseTimeClock);

        assertThat(trigger.getLastWindowFireMode()).isEmpty();

        ZonedDateTime now = windowStart.plusMinutes(20); // after the planned fire time, still inside the window
        assertThat(trigger.evaluate(now)).isNotNull();
        assertThat(trigger.getLastWindowFireMode()).contains(Trigger.WindowFireMode.OPTIMIZED);
    }

    @Test
    void fixedWindowFallingBackToItsCronReportsFallbackMode() {
        Clock parseTimeClock = Clock.fixed(at(LocalDate.of(2026, 9, 4), LocalTime.of(0, 0)).toInstant(), ZONE);
        FixedWindowPlanningConstraints constraints = fixedWindowConstraints(parseTimeClock);

        CarbonIntensityPlanner<FixedWindowPlanningConstraints> neverSchedulable = new CarbonIntensityPlanner<>() {
            @Override
            public boolean canSchedule(FixedWindowPlanningConstraints c) {
                return false;
            }

            @Override
            public ZonedDateTime getNextExecutionTime(FixedWindowPlanningConstraints c) {
                throw new AssertionError("Should not be called when canSchedule() is false");
            }
        };

        SimpleScheduler.FixedWindowTrigger trigger = new SimpleScheduler.FixedWindowTrigger("test", "Test#test",
                Duration.ofSeconds(30), neverSchedulable, constraints, parseTimeClock);

        assertThat(trigger.getLastWindowFireMode()).isEmpty();

        // the fallback cron ("0 0 10 * * ?") occurrence for that day
        ZonedDateTime fallbackFireTime = at(LocalDate.of(2026, 9, 4), LocalTime.of(10, 0, 5));
        assertThat(trigger.evaluate(fallbackFireTime)).isNotNull();
        assertThat(trigger.getLastWindowFireMode()).contains(Trigger.WindowFireMode.FALLBACK);
    }

    private GreenScheduled fixedWindowSchedule() {
        return AnnotationUtil.newGreenScheduled()
                .identity("test")
                .fixedWindow("9:30 11:45")
                .duration("15m")
                .cron("0 0 10 * * ?")
                .carbonIntensityZone("NL")
                .timeZone("Europe/Amsterdam")
                .build();
    }

    private FixedWindowPlanningConstraints fixedWindowConstraints(Clock clock) {
        PlanningConstraints constraints = GreenScheduledAnnotationParser.createConstraints("test", fixedWindowSchedule(),
                clock);
        return (FixedWindowPlanningConstraints) constraints;
    }

    private Trigger newTrigger(GreenScheduled greenScheduled, Clock clock) {
        SchedulerConfig config = new SchedulerConfig();
        config.setCarbonIntensityApi(new DisabledDummyCarbonIntensityApi());
        config.setClock(clock);
        scheduler = new SimpleScheduler(config);

        PlanningConstraints constraints = GreenScheduledAnnotationParser.createConstraints("test", greenScheduled, clock);
        SimpleScheduler.SimpleTrigger trigger = scheduler.createTrigger("test", "Test#test",
                GreenScheduledAnnotationParser.parseOverdueGracePeriod(greenScheduled, Duration.ofSeconds(30)),
                constraints);
        trigger.setCarbonIntensityZone(greenScheduled.carbonIntensityZone());
        return trigger;
    }

    private ZonedDateTime at(LocalDate date, LocalTime time) {
        return ZonedDateTime.of(LocalDateTime.of(date, time), ZONE);
    }
}
