package io.carbonintensity.scheduler.runtime.impl.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;

import io.vavr.Tuple;
import io.vavr.Tuple2;
import io.vavr.test.Arbitrary;
import io.vavr.test.Gen;
import io.vavr.test.Property;

/**
 * Tests for {@link GreenScheduledAnnotationParser}'s private {@code calculateFallBackCronExpression}, alongside the
 * example-based tests in {@link GreenScheduledAnnotationParserTest}.
 * <p>
 * The method computes a single daily "average" time-of-day between a fixed window's start and end, to use as a
 * fallback cron trigger when no explicit {@code cron} is configured. It derives that average from
 * {@code endTime.toSecondOfDay() - startTime.toSecondOfDay()}, which goes negative for an overnight window (e.g.
 * 23:15 -> 02:15), producing a fallback time exactly 12 hours away from the actual midpoint of the window instead
 * of inside it.
 * <p>
 * The method is private, so it is exercised here via reflection rather than through the full
 * {@link GreenScheduledAnnotationParser#createConstraints} path - that path's next-occurrence/DST handling in
 * {@link FixedWindowExpressionParser} is unrelated to this calculation and would only obscure it.
 * <p>
 * See {@code docs/adr/0002-vavr-test-over-jqwik.md} (in carbonintensity-api) for why vavr-test rather than
 * jqwik is used here.
 */
class TestCalculateFallBackCronExpressionProperties {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final LocalDate ANY_DATE = LocalDate.of(2024, 1, 1);

    // A start/end pair of distinct times of day, at minute precision (seconds always :00) - built as an offset from
    // the start rather than two independently generated times, so they can never accidentally collide (a
    // zero-length window isn't a meaningful case here). Mirrors TestFixedWindowExpressionParserProperties's
    // DISTINCT_WINDOW: the offset range of [1, 1439] means roughly half the generated windows are overnight (end
    // minute-of-day < start minute-of-day). Keeping the seconds at :00 also avoids the fallback cron's own
    // second-truncation (it only encodes hour:minute) from ever pulling the truncated average outside the window -
    // a rounding artifact independent of this bug.
    private static final Arbitrary<Tuple2<LocalTime, LocalTime>> DISTINCT_WINDOW = size -> Gen.choose(0, 1439)
            .flatMap(startMinute -> Gen.choose(1, 1439)
                    .map(offset -> Tuple.of(toLocalTime(startMinute), toLocalTime((startMinute + offset) % 1440))));

    // CIIO-318: previously found that an overnight window (endTime < startTime) produced a fallback average time
    // exactly 12 hours away from the true midpoint, because dailyWindowInSeconds went negative and integer division
    // by 2 preserved the sign. Fixed by wrapping the negative difference back into [0, 86400) before halving.
    @Test
    void overnightWindowFallbackCronFallsInsideTheWindow() {
        FixedWindowConstraints overnightWindow = toFixedWindowConstraints(LocalTime.of(23, 15), LocalTime.of(2, 15));

        String fallbackCron = calculateFallBackCronExpression(overnightWindow);

        // Midpoint of 23:15 -> 02:15 (a 3-hour window) is 00:45, not 12:45 (the pre-fix, 12-hours-off result).
        assertThat(fallbackCron).isEqualTo("0 45 0 * * ?");
    }

    @Test
    void nonOvernightWindowFallbackCronIsUnaffected() {
        FixedWindowConstraints daytimeWindow = toFixedWindowConstraints(LocalTime.of(9, 30), LocalTime.of(11, 45));

        String fallbackCron = calculateFallBackCronExpression(daytimeWindow);

        assertThat(fallbackCron).isEqualTo("0 37 10 * * ?");
    }

    @Test
    void fallbackCronIsAlwaysParseableAsAQuartzCronExpression() {
        Property.def("calculateFallBackCronExpression(...) always yields a parseable Quartz cron expression")
                .forAll(DISTINCT_WINDOW)
                .suchThat(window -> {
                    FixedWindowConstraints fixedWindow = toFixedWindowConstraints(window._1, window._2);
                    String fallbackCron = calculateFallBackCronExpression(fixedWindow);
                    Cron parsed = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ))
                            .parse(fallbackCron);
                    return parsed != null;
                })
                .check()
                .assertIsSatisfied();
    }

    @Test
    void fallbackCronAlwaysFallsInsideTheWindowItAverages() {
        Property.def("calculateFallBackCronExpression(...) time-of-day lies within [startTime, endTime], wrapping "
                + "across midnight for an overnight window")
                .forAll(DISTINCT_WINDOW)
                .suchThat(window -> {
                    LocalTime startTime = window._1;
                    LocalTime endTime = window._2;
                    FixedWindowConstraints fixedWindow = toFixedWindowConstraints(startTime, endTime);

                    String fallbackCron = calculateFallBackCronExpression(fixedWindow);
                    LocalTime fallbackTime = parseHourMinute(fallbackCron);

                    return isWithinWindow(fallbackTime, startTime, endTime);
                })
                .check()
                .assertIsSatisfied();
    }

    private static boolean isWithinWindow(LocalTime candidate, LocalTime startTime, LocalTime endTime) {
        int candidateMinute = candidate.getHour() * 60 + candidate.getMinute();
        int startMinute = startTime.getHour() * 60 + startTime.getMinute();
        int endMinute = endTime.getHour() * 60 + endTime.getMinute();
        if (startMinute <= endMinute) {
            return candidateMinute >= startMinute && candidateMinute <= endMinute;
        }
        // Overnight window: the valid range wraps across midnight.
        return candidateMinute >= startMinute || candidateMinute <= endMinute;
    }

    private static LocalTime parseHourMinute(String quartzCron) {
        // Cron format produced by calculateFallBackCronExpression: "0 <minute> <hour> * * ?"
        String[] fields = quartzCron.split(" ");
        int minute = Integer.parseInt(fields[1]);
        int hour = Integer.parseInt(fields[2]);
        return LocalTime.of(hour, minute);
    }

    private static FixedWindowConstraints toFixedWindowConstraints(LocalTime startTime, LocalTime endTime) {
        return new FixedWindowConstraints(
                ZonedDateTime.of(ANY_DATE, startTime, UTC),
                ZonedDateTime.of(ANY_DATE, endTime, UTC));
    }

    private static String calculateFallBackCronExpression(FixedWindowConstraints fixedWindow) {
        try {
            Method method = GreenScheduledAnnotationParser.class.getDeclaredMethod("calculateFallBackCronExpression",
                    FixedWindowConstraints.class);
            method.setAccessible(true);
            return (String) method.invoke(null, fixedWindow);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static LocalTime toLocalTime(int minuteOfDay) {
        return LocalTime.of(minuteOfDay / 60, minuteOfDay % 60);
    }
}
