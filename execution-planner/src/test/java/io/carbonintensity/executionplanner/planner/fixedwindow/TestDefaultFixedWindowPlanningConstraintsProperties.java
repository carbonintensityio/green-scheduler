package io.carbonintensity.executionplanner.planner.fixedwindow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;

import io.vavr.Tuple;
import io.vavr.Tuple2;
import io.vavr.control.Option;
import io.vavr.test.Arbitrary;
import io.vavr.test.Gen;
import io.vavr.test.Property;

/**
 * Property-based tests for the day-shifting logic in {@link DefaultFixedWindowPlanningConstraints}
 * ({@code checkStartTime}/{@code calculateDelayedDays}, exercised here through the public constructor/builder
 * since both methods are private), alongside the hand-picked examples in {@link TestFixedWindowPlanner}.
 * <p>
 * Those existing examples all derive their cron and start time from {@code ZonedDateTime.now()}, so they never
 * pin a specific month/year boundary or leap day, and none of them drives the search past its 35-day cap into
 * the fallback path. This class targets exactly that: a {@code startTime} paired with a day-of-month-only or
 * day-of-week-only cron, skewed towards month ends (including 29/30/31, which don't exist in every month) and
 * year boundaries, plus the pinned 2024-02-29 leap day.
 * <p>
 * The invariant: the constructed constraints' start is {@code startTime} shifted by the smallest non-negative
 * number of days such that the cron matches - found independently in this test via a plain brute-force search
 * over the same 0..34 day horizon the production code uses (day 0 via {@code checkStartTime}, days 1..34 via
 * {@code calculateDelayedDays}'s loop) - or, if no day in that horizon matches, the shift is 0 (the documented
 * fallback, logged rather than applied silently).
 *
 * <p>
 * See {@code docs/adr/0002-vavr-test-over-jqwik.md} (in carbonintensity-api) for why vavr-test rather than
 * jqwik is used here.
 */
class TestDefaultFixedWindowPlanningConstraintsProperties {

    private static final ZoneId UTC = ZoneId.of("UTC");

    // Same cap as DefaultFixedWindowPlanningConstraints: checkStartTime covers day 0, and
    // calculateDelayedDays' loop covers days 1..34, so 35 candidate days are examined in total.
    private static final int HORIZON_DAYS = 35;

    private static final CronParser QUARTZ_PARSER = new CronParser(
            CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));
    private static final Cron FALLBACK_CRON = QUARTZ_PARSER.parse("0 0 12 * * ?");

    private static final String[] DAY_OF_WEEK_NAMES = { "MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN" };

    // Skewed towards the last day of a month (28/29/30/31 - not every month has a 31st, or even a 29th, which
    // is exactly what makes a day-of-month cron interesting here), mixed with the pinned 2024 leap day and
    // plain dates spread across a 5-year span that includes a leap year.
    private static final Gen<LocalDate> DATES = Gen.frequency(
            Tuple.of(4,
                    Gen.choose(2023, 2027).flatMap(
                            year -> Gen.choose(1, 12).map(month -> YearMonth.of(year, month).atEndOfMonth()))),
            Tuple.of(1, Gen.of(LocalDate.of(2024, 2, 29))),
            Tuple.of(5, Gen.choose(0, 365 * 5).map(offset -> LocalDate.of(2023, 1, 1).plusDays(offset))));

    // A start time together with a cron whose seconds/minutes/hours match that start time exactly, and whose
    // day-of-month (kind 0) or day-of-week (kind 1) field is randomized - isolating the day-stepping search
    // from time-of-day matching, which is not what's under test here.
    private static final Arbitrary<Tuple2<ZonedDateTime, Cron>> START_AND_CRON = size -> DATES
            .flatMap(date -> Gen.choose(0, 23)
                    .flatMap(hour -> Gen.choose(0, 59)
                            .flatMap(minute -> Gen.choose(0, 59)
                                    .flatMap(second -> Gen.choose(0, 1).flatMap(kind -> {
                                        ZonedDateTime startTime = ZonedDateTime.of(date,
                                                LocalTime.of(hour, minute, second), UTC);
                                        Gen<Cron> cronGen = kind == 0
                                                ? Gen.choose(1, 31)
                                                        .map(dayOfMonth -> dayOfMonthCron(second, minute, hour,
                                                                dayOfMonth))
                                                : Gen.choose(DAY_OF_WEEK_NAMES)
                                                        .map(dayOfWeek -> dayOfWeekCron(second, minute, hour,
                                                                dayOfWeek));
                                        return cronGen.map(cron -> Tuple.of(startTime, cron));
                                    })))));

    @Test
    void startIsShiftedToTheMinimalMatchingDayOrFallsBackToZeroWithinTheHorizon() {
        Property.def("DefaultFixedWindowPlanningConstraints.getStart() == startTime + minimal matching day,"
                + " or startTime unchanged when nothing in the horizon matches")
                .forAll(START_AND_CRON)
                .suchThat(startAndCron -> {
                    ZonedDateTime startTime = startAndCron._1;
                    Cron cron = startAndCron._2;

                    DefaultFixedWindowPlanningConstraints constraints = buildConstraints(startTime, cron);
                    long actualDelayDays = ChronoUnit.DAYS.between(startTime, constraints.getStart());

                    Option<Integer> expectedDelayDays = findMinimalMatchingDelay(startTime, cron);
                    if (expectedDelayDays.isDefined()) {
                        return actualDelayDays == expectedDelayDays.get()
                                && ExecutionTime.forCron(cron).isMatch(constraints.getStart());
                    } else {
                        // Nothing in the 35-day horizon matches: the documented fallback applies, and the
                        // resulting start does NOT satisfy the cron - a known limitation of the hardcoded cap,
                        // not something this test treats as a silent pass-through.
                        return actualDelayDays == 0;
                    }
                })
                .check()
                .assertIsSatisfied();
    }

    // Deterministic complement to the property above: a day-of-month cron that can never match within the
    // 35-day horizon from this particular start. The 31st only occurs in Jan/Mar/May/Jul/Aug/Oct/Dec, and
    // starting right after Jan 31 the next occurrence is Mar 31 - 58 days out (28 days left in Feb, plus 30
    // to get from Mar 1 to Mar 31) - pinning that the fallback is delay 0, not a silently wrong window.
    @Test
    void whenNoMatchExistsWithinTheHorizon_thenFallsBackToUnshiftedStartTime() {
        ZonedDateTime startTime = ZonedDateTime.of(2023, 2, 1, 10, 0, 0, 0, UTC);
        Cron cron = dayOfMonthCron(0, 0, 10, 31);

        assertThat(findMinimalMatchingDelay(startTime, cron)).isEmpty();

        DefaultFixedWindowPlanningConstraints constraints = buildConstraints(startTime, cron);

        assertThat(constraints.getStart()).isEqualTo(startTime);
        assertThat(ExecutionTime.forCron(cron).isMatch(constraints.getStart())).isFalse();
    }

    private static DefaultFixedWindowPlanningConstraints buildConstraints(ZonedDateTime startTime, Cron cron) {
        return DefaultFixedWindowPlanningConstraints.builder()
                .withIdentity("job")
                .withDuration(Duration.ofMinutes(30))
                .withCarbonIntensityZone("NL")
                .withCronExpression(cron)
                .withStartAndEnd(startTime, startTime.plusHours(1))
                .withFallbackCronExpression(FALLBACK_CRON)
                .withTimeZoneId(UTC)
                .build();
    }

    // Independent oracle: brute-force the same 0..34 day horizon the production code covers (day 0 via
    // checkStartTime, 1..34 via calculateDelayedDays), returning the smallest matching offset, if any.
    private static Option<Integer> findMinimalMatchingDelay(ZonedDateTime startTime, Cron cron) {
        ExecutionTime executionTime = ExecutionTime.forCron(cron);
        for (int day = 0; day < HORIZON_DAYS; day++) {
            if (executionTime.isMatch(startTime.plusDays(day))) {
                return Option.some(day);
            }
        }
        return Option.none();
    }

    private static Cron dayOfMonthCron(int second, int minute, int hour, int dayOfMonth) {
        return QUARTZ_PARSER.parse(String.format("%d %d %d %d * ?", second, minute, hour, dayOfMonth));
    }

    private static Cron dayOfWeekCron(int second, int minute, int hour, String dayOfWeek) {
        return QUARTZ_PARSER.parse(String.format("%d %d %d ? * %s", second, minute, hour, dayOfWeek));
    }
}
