package io.carbonintensity.scheduler.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.chrono.ChronoZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import org.junit.jupiter.api.Test;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinition;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;

import io.carbonintensity.executionplanner.planner.fixedwindow.DefaultFixedWindowPlanningConstraints;
import io.carbonintensity.executionplanner.planner.fixedwindow.FixedWindowPlanningConstraints;
import io.carbonintensity.executionplanner.planner.successive.DefaultSuccessivePlanningConstraints;
import io.carbonintensity.executionplanner.planner.successive.SuccessivePlanningConstraints;
import io.carbonintensity.executionplanner.spi.CarbonIntensityPlanner;
import io.vavr.test.Arbitrary;
import io.vavr.test.Gen;
import io.vavr.test.Property;

/**
 * {@code isOverdue()}/{@code getNextFireTime()} had zero test coverage across all four trigger
 * classes nested in {@link SimpleScheduler} (CIIO-340, NO_COVERAGE mutants): {@link SimpleScheduler.CronTrigger},
 * {@link SimpleScheduler.FixedWindowTrigger}, {@link SimpleScheduler.IntervalTrigger} and
 * {@link SimpleScheduler.SuccessiveTrigger}. Each class is exercised directly (these are
 * package-private nested classes, so this test lives in the same package), independently of the
 * full {@code SimpleScheduler}/annotation machinery, using a fixed-plus-offset {@link Clock} per
 * check rather than the shared {@code MutableClock} - there's no periodic re-evaluation to drive
 * here, just pure reads of {@code isOverdue()}/{@code getNextFireTime()}.
 * <p>
 * {@code IntervalTrigger}'s own constructor is private - in production it's only ever reachable
 * through {@link SimpleScheduler.SuccessiveTrigger}'s fallback path (when its planner reports it
 * cannot schedule). It's exercised here the same way, via a stubbed planner.
 * <p>
 * Found and fixed a real clock-injection bug in {@link SimpleScheduler.CronTrigger#isOverdue()}: see
 * {@link #cronTriggerIsOverdueMatchesItsOwnNextFireTimePlusGracePeriod()}.
 */
class TriggerOverdueAndNextFireTimeTest {

    private static final Duration GRACE_PERIOD = Duration.ofSeconds(2);
    private static final Arbitrary<Integer> FORWARD_SHIFT_SECONDS = size -> Gen.choose(0, 3600);

    private static Cron everySecondCron() {
        CronDefinition definition = CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ);
        return new CronParser(definition).parse("* * * * * ?");
    }

    private static ZonedDateTime nowTruncatedToSeconds() {
        return ZonedDateTime.now(Clock.systemUTC()).truncatedTo(ChronoUnit.SECONDS);
    }

    private static Clock clockAt(ZonedDateTime base, int shiftSeconds) {
        return Clock.offset(Clock.fixed(base.toInstant(), ZoneOffset.UTC), Duration.ofSeconds(shiftSeconds));
    }

    // --- CronTrigger ---

    // Property-of-record for CIIO-340: this failed before the fix below, because isOverdue() used
    // ZonedDateTime.now() (the real system clock) instead of ZonedDateTime.now(this.clock), so it
    // silently ignored whatever Clock was injected into the trigger. Fixed in
    // SimpleScheduler.CronTrigger#isOverdue() by using ZonedDateTime.now(this.clock).
    @Test
    void cronTriggerIsOverdueMatchesItsOwnNextFireTimePlusGracePeriod() {
        ZonedDateTime start = nowTruncatedToSeconds();
        Cron everySecond = everySecondCron();

        Property.def("CronTrigger.isOverdue() reflects getNextFireTime() + gracePeriod, using the trigger's injected clock")
                .forAll(FORWARD_SHIFT_SECONDS)
                .suchThat(shiftSeconds -> {
                    Clock clock = clockAt(start, shiftSeconds);
                    SimpleScheduler.CronTrigger trigger = new SimpleScheduler.CronTrigger("id", start, everySecond,
                            GRACE_PERIOD,
                            "desc", clock);

                    Instant expectedNextFireTime = ExecutionTime.forCron(everySecond).nextExecution(start)
                            .map(ChronoZonedDateTime::toInstant).orElse(null);
                    ZonedDateTime now = ZonedDateTime.now(clock);
                    boolean expectedOverdue = !now.isBefore(start)
                            && (expectedNextFireTime == null
                                    || expectedNextFireTime.plus(GRACE_PERIOD).isBefore(now.toInstant()));

                    return Objects.equals(expectedNextFireTime, trigger.getNextFireTime())
                            && expectedOverdue == trigger.isOverdue();
                })
                .check()
                .assertIsSatisfied();
    }

    @Test
    void cronTriggerIsNotOverdueBeforeItsStartTime() {
        ZonedDateTime start = nowTruncatedToSeconds().plusHours(1);
        SimpleScheduler.CronTrigger trigger = new SimpleScheduler.CronTrigger("id", start, everySecondCron(), GRACE_PERIOD,
                "desc",
                Clock.systemUTC());

        assertThat(trigger.isOverdue()).isFalse();
    }

    @Test
    void cronTriggerIsOverdueOnlyStrictlyAfterNextFireTimePlusGracePeriod() {
        ZonedDateTime start = nowTruncatedToSeconds();
        Cron everySecond = everySecondCron();
        Instant nextFireTime = ExecutionTime.forCron(everySecond).nextExecution(start).map(ChronoZonedDateTime::toInstant)
                .orElseThrow();
        Instant threshold = nextFireTime.plus(GRACE_PERIOD);

        SimpleScheduler.CronTrigger notYetOverdue = new SimpleScheduler.CronTrigger("id", start, everySecond, GRACE_PERIOD,
                "desc",
                Clock.fixed(threshold, ZoneOffset.UTC));
        assertThat(notYetOverdue.isOverdue()).isFalse();

        SimpleScheduler.CronTrigger overdue = new SimpleScheduler.CronTrigger("id", start, everySecond, GRACE_PERIOD, "desc",
                Clock.fixed(threshold.plusMillis(1), ZoneOffset.UTC));
        assertThat(overdue.isOverdue()).isTrue();
    }

    // --- FixedWindowTrigger ---

    private static FixedWindowPlanningConstraints fixedWindowConstraints(ZonedDateTime start, ZonedDateTime end) {
        Cron cron = everySecondCron();
        return DefaultFixedWindowPlanningConstraints.builder()
                .withIdentity("id")
                .withDuration(Duration.ofMinutes(1))
                .withCarbonIntensityZone("NL")
                .withCronExpression(cron)
                .withStartAndEnd(start, end)
                .withFallbackCronExpression(cron)
                .withTimeZoneId(ZoneOffset.UTC)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static CarbonIntensityPlanner<FixedWindowPlanningConstraints> mockFixedWindowPlanner() {
        return mock(CarbonIntensityPlanner.class);
    }

    // FixedWindowTrigger#isOverdue() unconditionally returns false, regardless of the clock or the
    // planner - unlike the other three trigger classes, it never falls back to CronTrigger's
    // isOverdue() logic. This property pins that down across a wide range of clock offsets so a
    // future accidental super.isOverdue() delegation is caught.
    @Test
    void fixedWindowTriggerIsNeverOverdue() {
        ZonedDateTime start = nowTruncatedToSeconds();
        FixedWindowPlanningConstraints constraints = fixedWindowConstraints(start, start.plusHours(2));
        Arbitrary<Integer> wideShiftSeconds = size -> Gen.choose(-100_000, 100_000);

        Property.def("FixedWindowTrigger.isOverdue() is always false, by design, regardless of the clock")
                .forAll(wideShiftSeconds)
                .suchThat(shiftSeconds -> {
                    Clock clock = clockAt(start, shiftSeconds);
                    SimpleScheduler.FixedWindowTrigger trigger = new SimpleScheduler.FixedWindowTrigger("id", "desc",
                            GRACE_PERIOD,
                            mockFixedWindowPlanner(), constraints, clock);
                    return !trigger.isOverdue();
                })
                .check()
                .assertIsSatisfied();
    }

    @Test
    void fixedWindowTriggerGetNextFireTimeDelegatesToThePlanner() {
        ZonedDateTime start = nowTruncatedToSeconds();
        FixedWindowPlanningConstraints constraints = fixedWindowConstraints(start, start.plusHours(2));
        CarbonIntensityPlanner<FixedWindowPlanningConstraints> planner = mockFixedWindowPlanner();
        ZonedDateTime plannedTime = start.plusMinutes(42);
        when(planner.getNextExecutionTime(constraints)).thenReturn(plannedTime);
        SimpleScheduler.FixedWindowTrigger trigger = new SimpleScheduler.FixedWindowTrigger("id", "desc", GRACE_PERIOD, planner,
                constraints, Clock.systemUTC());

        assertThat(trigger.getNextFireTime()).isEqualTo(plannedTime.toInstant());
    }

    // --- IntervalTrigger (reached only via SuccessiveTrigger's fallback - its own constructor is
    // private, used solely from within SuccessiveTrigger's super() call) ---

    private static SuccessivePlanningConstraints successiveConstraints(ZonedDateTime start, Duration gap) {
        return DefaultSuccessivePlanningConstraints.builder()
                .withIdentity("id")
                .withCarbonIntensityZone("NL")
                .withInitialStartTime(start)
                .withInitialMaximumDelay(Duration.ZERO)
                .withMinimumGap(gap)
                .withMaximumGap(gap)
                .withDuration(Duration.ofSeconds(1))
                .build();
    }

    @SuppressWarnings("unchecked")
    private static CarbonIntensityPlanner<SuccessivePlanningConstraints> plannerThatCannotSchedule() {
        CarbonIntensityPlanner<SuccessivePlanningConstraints> planner = mock(CarbonIntensityPlanner.class);
        when(planner.canSchedule(any())).thenReturn(false);
        return planner;
    }

    @Test
    void intervalTriggerFallbackIsOverdueMatchesItsOwnNextFireTimePlusGracePeriod() {
        ZonedDateTime start = nowTruncatedToSeconds();
        Duration gap = Duration.ofSeconds(10); // minGap == maxGap, so the fallback interval is exactly `gap`

        Property.def(
                "IntervalTrigger (reached via SuccessiveTrigger's fallback) isOverdue() reflects its own getNextFireTime() + gracePeriod")
                .forAll(FORWARD_SHIFT_SECONDS)
                .suchThat(shiftSeconds -> {
                    Clock clock = clockAt(start, shiftSeconds);
                    SimpleScheduler.SuccessiveTrigger trigger = new SimpleScheduler.SuccessiveTrigger("id", clock, start,
                            "desc",
                            GRACE_PERIOD, plannerThatCannotSchedule(), successiveConstraints(start, gap));
                    trigger.lastFireTime = start; // baseline: already fired exactly once, at "start"

                    Instant expectedNextFireTime = start.plus(gap).toInstant();
                    ZonedDateTime now = ZonedDateTime.now(clock);
                    boolean expectedOverdue = !now.isBefore(start)
                            && expectedNextFireTime.plus(GRACE_PERIOD).isBefore(now.toInstant());

                    return expectedNextFireTime.equals(trigger.getNextFireTime()) && expectedOverdue == trigger.isOverdue();
                })
                .check()
                .assertIsSatisfied();
    }

    @Test
    void intervalTriggerFallbackIsNotOverdueBeforeItsStartTime() {
        ZonedDateTime start = nowTruncatedToSeconds().plusHours(1);
        SimpleScheduler.SuccessiveTrigger trigger = new SimpleScheduler.SuccessiveTrigger("id", Clock.systemUTC(), start,
                "desc",
                GRACE_PERIOD, plannerThatCannotSchedule(), successiveConstraints(start, Duration.ofSeconds(10)));
        // lastFireTime intentionally left null: a trigger that hasn't started yet has never fired.

        assertThat(trigger.isOverdue()).isFalse();
    }

    // --- SuccessiveTrigger's own logic (planner reports it CAN schedule) ---

    @SuppressWarnings("unchecked")
    private static CarbonIntensityPlanner<SuccessivePlanningConstraints> plannerReturning(ZonedDateTime nextExecutionTime) {
        CarbonIntensityPlanner<SuccessivePlanningConstraints> planner = mock(CarbonIntensityPlanner.class);
        when(planner.canSchedule(any())).thenReturn(true);
        when(planner.getNextExecutionTime(any())).thenReturn(nextExecutionTime);
        return planner;
    }

    @Test
    void successiveTriggerIsOverdueMatchesThePlannedNextFireTimePlusGracePeriod() {
        ZonedDateTime start = nowTruncatedToSeconds();
        ZonedDateTime plannedNextFireTime = start.plusSeconds(30);
        SuccessivePlanningConstraints constraints = successiveConstraints(start, Duration.ofSeconds(10));

        Property.def(
                "SuccessiveTrigger.isOverdue() reflects the planner's getNextExecutionTime() + gracePeriod when it can schedule")
                .forAll(FORWARD_SHIFT_SECONDS)
                .suchThat(shiftSeconds -> {
                    Clock clock = clockAt(start, shiftSeconds);
                    SimpleScheduler.SuccessiveTrigger trigger = new SimpleScheduler.SuccessiveTrigger("id", clock, start,
                            "desc",
                            GRACE_PERIOD, plannerReturning(plannedNextFireTime), constraints);

                    ZonedDateTime now = ZonedDateTime.now(clock);
                    boolean expectedOverdue = !now.isBefore(start)
                            && plannedNextFireTime.toInstant().plus(GRACE_PERIOD).isBefore(now.toInstant());

                    return plannedNextFireTime.toInstant().equals(trigger.getNextFireTime())
                            && expectedOverdue == trigger.isOverdue();
                })
                .check()
                .assertIsSatisfied();
    }

    @Test
    void successiveTriggerIsNotOverdueBeforeItsStartTimeEvenWhenThePlannerCanSchedule() {
        ZonedDateTime start = nowTruncatedToSeconds().plusHours(1);
        SimpleScheduler.SuccessiveTrigger trigger = new SimpleScheduler.SuccessiveTrigger("id", Clock.systemUTC(), start,
                "desc",
                GRACE_PERIOD, plannerReturning(start.plusMinutes(5)), successiveConstraints(start, Duration.ofSeconds(10)));

        assertThat(trigger.isOverdue()).isFalse();
    }
}
