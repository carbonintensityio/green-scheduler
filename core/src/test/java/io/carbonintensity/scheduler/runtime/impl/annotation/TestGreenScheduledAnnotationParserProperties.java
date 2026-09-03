package io.carbonintensity.scheduler.runtime.impl.annotation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

import com.cronutils.model.Cron;
import com.cronutils.model.time.ExecutionTime;

import io.vavr.Tuple;
import io.vavr.Tuple3;
import io.vavr.test.Arbitrary;
import io.vavr.test.Gen;
import io.vavr.test.Property;

/**
 * Property-based tests for {@link GreenScheduledAnnotationParser#parseCronExpression}, alongside the example-based
 * tests in {@link GreenScheduledAnnotationParserTest}.
 * <p>
 * The core invariant checked here is that for any {@code startTime} and any mutually-exclusive dayOfMonth/dayOfWeek
 * expression that (as validated separately by {@link GreenScheduledAnnotationValidation}) actually covers that
 * {@code startTime}'s own day, the generated Quartz cron string parses successfully and matches the
 * second-truncated {@code startTime} - across the full boundary range of second (0/59), hour (0/23) and
 * day-of-month/day-of-week expression styles (exact value, wildcard, list).
 * <p>
 * A second property checks the complementary case: a dayOfMonth/dayOfWeek value that is syntactically invalid for
 * Quartz (out of range or not a cron token at all) always fails predictably with an {@link IllegalArgumentException},
 * rather than silently producing a cron that parses but matches the wrong thing.
 *
 * <p>
 * See {@code docs/adr/0002-vavr-test-over-jqwik.md} (in carbonintensity-api) for why vavr-test rather than
 * jqwik is used here.
 */
class TestGreenScheduledAnnotationParserProperties {

    private static final ZoneId AMSTERDAM = ZoneId.of("Europe/Amsterdam");

    private static final Gen<LocalDate> DATES = Gen.choose(0, 365 * 8)
            .map(offset -> LocalDate.of(2022, 1, 1).plusDays(offset));

    // Skewed towards the second/hour boundaries (0 and the max value), mixed with values from the rest of the
    // range, so a typical run exercises both edges and the ordinary case.
    private static final Gen<Integer> SECONDS = Gen.frequency(
            Tuple.of(1, Gen.of(0)), Tuple.of(1, Gen.of(59)), Tuple.of(3, Gen.choose(1, 58)));
    private static final Gen<Integer> HOURS = Gen.frequency(
            Tuple.of(1, Gen.of(0)), Tuple.of(1, Gen.of(23)), Tuple.of(3, Gen.choose(1, 22)));
    private static final Gen<Integer> MINUTES = Gen.choose(0, 59);

    private static final Arbitrary<ZonedDateTime> START_TIMES = size -> DATES
            .flatMap(date -> HOURS
                    .flatMap(hour -> MINUTES
                            .flatMap(minute -> SECONDS
                                    .map(second -> ZonedDateTime.of(date, LocalTime.of(hour, minute, second), AMSTERDAM)))));

    // A dayOfMonth/dayOfWeek expression that is guaranteed to cover the startTime's own day - as an exact value,
    // a wildcard, or a list containing it - so the resulting cron can be expected to match. "none" (both blank)
    // is included too, since that is a valid combination handled by parseCronExpression itself.
    private static final Arbitrary<Tuple3<ZonedDateTime, String, String>> START_TIME_WITH_MATCHING_DAY_FIELDS = size -> START_TIMES
            .apply(size)
            .flatMap(startTime -> Gen.choose(0, 5).map(dayFieldStyle -> {
                int dayOfMonth = startTime.getDayOfMonth();
                String dayOfWeekName = startTime.getDayOfWeek().name().substring(0, 3); // e.g. MONDAY -> MON
                switch (dayFieldStyle) {
                    case 0:
                        return Tuple.of(startTime, "", ""); // neither set
                    case 1:
                        return Tuple.of(startTime, String.valueOf(dayOfMonth), ""); // dayOfMonth exact value
                    case 2:
                        return Tuple.of(startTime, "*", ""); // dayOfMonth wildcard
                    case 3:
                        return Tuple.of(startTime, dayOfMonth + ",1,15", ""); // dayOfMonth list containing it
                    case 4:
                        return Tuple.of(startTime, "", dayOfWeekName); // dayOfWeek exact value
                    default:
                        return Tuple.of(startTime, "", "*"); // dayOfWeek wildcard
                }
            }));

    @Test
    void generatedCronMatchesTheSecondTruncatedStartTime() {
        Property.def("parseCronExpression(...) parses and matches the second-truncated startTime")
                .forAll(START_TIME_WITH_MATCHING_DAY_FIELDS)
                .suchThat(startTimeWithDayFields -> {
                    ZonedDateTime startTime = startTimeWithDayFields._1;
                    String dayOfMonth = startTimeWithDayFields._2;
                    String dayOfWeek = startTimeWithDayFields._3;

                    Cron cron = GreenScheduledAnnotationParser.parseCronExpression(startTime, dayOfMonth, dayOfWeek);

                    return ExecutionTime.forCron(cron).isMatch(startTime.truncatedTo(ChronoUnit.SECONDS));
                })
                .check()
                .assertIsSatisfied();
    }

    // dayOfMonth values that Quartz cannot parse at all: out of its valid 1-31 range, or not a cron token.
    // Note: unlike dayOfWeek, "8" and "13" are valid dayOfMonth values, so they are deliberately excluded here.
    private static final Arbitrary<String> INVALID_DAY_OF_MONTH_VALUES = Arbitrary.of(
            "0", "32", "40", "-1", "abc", "MON-", "FOO", "*/0", "99");

    // dayOfWeek values that Quartz cannot parse at all: out of its valid 1-7 range, or not a cron token.
    private static final Arbitrary<String> INVALID_DAY_OF_WEEK_VALUES = Arbitrary.of(
            "0", "8", "13", "abc", "FOO", "MON-", "SUN-", "*/0", "99");

    @Test
    void invalidDayOfMonthAlwaysThrowsIllegalArgumentException() {
        Property.def("parseCronExpression(...) rejects an invalid dayOfMonth")
                .forAll(START_TIMES, INVALID_DAY_OF_MONTH_VALUES)
                .suchThat((startTime, invalidDayOfMonth) -> {
                    assertThatThrownBy(() -> GreenScheduledAnnotationParser.parseCronExpression(startTime, invalidDayOfMonth,
                            null))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("Invalid CRON format");
                    return true;
                })
                .check()
                .assertIsSatisfied();
    }

    @Test
    void invalidDayOfWeekAlwaysThrowsIllegalArgumentException() {
        Property.def("parseCronExpression(...) rejects an invalid dayOfWeek")
                .forAll(START_TIMES, INVALID_DAY_OF_WEEK_VALUES)
                .suchThat((startTime, invalidDayOfWeek) -> {
                    assertThatThrownBy(() -> GreenScheduledAnnotationParser.parseCronExpression(startTime, null,
                            invalidDayOfWeek))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("Invalid CRON format");
                    return true;
                })
                .check()
                .assertIsSatisfied();
    }
}
