package io.carbonintensity.scheduler.runtime.impl.annotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Example-based tests for {@link FixedWindowExpressionParser}. See {@link TestFixedWindowExpressionParserProperties}
 * for property-based tests alongside these, focused in particular on its DST-sensitive date arithmetic.
 */
class TestFixedWindowExpressionParser {

    private static final ZoneId AMSTERDAM = ZoneId.of("Europe/Amsterdam");

    @Test
    void nullExpressionYieldsEmpty() {
        assertThat(FixedWindowExpressionParser.parse(null, fixedClockAt(LocalDate.of(2025, 6, 1), LocalTime.of(4, 0)),
                AMSTERDAM, Duration.ZERO)).isEmpty();
    }

    @Test
    void emptyExpressionYieldsEmpty() {
        assertThat(FixedWindowExpressionParser.parse("", fixedClockAt(LocalDate.of(2025, 6, 1), LocalTime.of(4, 0)), AMSTERDAM,
                Duration.ZERO)).isEmpty();
    }

    @Test
    void malformedExpressionThrows() {
        Clock clock = fixedClockAt(LocalDate.of(2025, 6, 1), LocalTime.of(4, 0));
        assertThatThrownBy(() -> FixedWindowExpressionParser.parse("09:30-11:45", clock, AMSTERDAM, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid fixedWindow format");
    }

    @Test
    void unparsableTimeThrows() {
        Clock clock = fixedClockAt(LocalDate.of(2025, 6, 1), LocalTime.of(4, 0));
        assertThatThrownBy(() -> FixedWindowExpressionParser.parse("09:30 not-a-time", clock, AMSTERDAM, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid time format");
    }

    @Test
    void nonOvernightWindowStartsAndEndsOnTheSameDay() {
        Clock clock = fixedClockAt(LocalDate.of(2025, 6, 1), LocalTime.of(4, 0));

        Optional<FixedWindowConstraints> constraints = FixedWindowExpressionParser.parse("09:30 11:45", clock, AMSTERDAM,
                Duration.ZERO);

        assertThat(constraints).isPresent();
        assertThat(constraints.get().getStartTime())
                .isEqualTo(ZonedDateTime.of(LocalDate.of(2025, 6, 1), LocalTime.of(9, 30), AMSTERDAM));
        assertThat(constraints.get().getEndTime())
                .isEqualTo(ZonedDateTime.of(LocalDate.of(2025, 6, 1), LocalTime.of(11, 45), AMSTERDAM));
    }

    @Test
    void nonOvernightWindowRollsToTomorrowWhenTodaysHasAlreadyEnded() {
        // "now" is 23:09, well after today's 09:30-11:45 window (plus its zero grace period) closed, so the next
        // occurrence is tomorrow's.
        Clock clock = fixedClockAt(LocalDate.of(2025, 6, 1), LocalTime.of(23, 9));

        Optional<FixedWindowConstraints> constraints = FixedWindowExpressionParser.parse("09:30 11:45", clock, AMSTERDAM,
                Duration.ZERO);

        assertThat(constraints).isPresent();
        assertThat(constraints.get().getStartTime())
                .isEqualTo(ZonedDateTime.of(LocalDate.of(2025, 6, 2), LocalTime.of(9, 30), AMSTERDAM));
        assertThat(constraints.get().getEndTime())
                .isEqualTo(ZonedDateTime.of(LocalDate.of(2025, 6, 2), LocalTime.of(11, 45), AMSTERDAM));
    }

    @Test
    void nonOvernightWindowStaysOnTodayWhileStillWithinItsGracePeriod() {
        // "now" is 30s after today's 09:30-11:45 window closed, but a 90s grace period is still running - the
        // job hasn't missed its chance yet, so this must still resolve to today's window, not tomorrow's.
        Clock clock = fixedClockAt(LocalDate.of(2025, 6, 1), LocalTime.of(11, 45, 30));

        Optional<FixedWindowConstraints> constraints = FixedWindowExpressionParser.parse("09:30 11:45", clock, AMSTERDAM,
                Duration.ofSeconds(90));

        assertThat(constraints).isPresent();
        assertThat(constraints.get().getStartTime())
                .isEqualTo(ZonedDateTime.of(LocalDate.of(2025, 6, 1), LocalTime.of(9, 30), AMSTERDAM));
        assertThat(constraints.get().getEndTime())
                .isEqualTo(ZonedDateTime.of(LocalDate.of(2025, 6, 1), LocalTime.of(11, 45), AMSTERDAM));
    }

    @Test
    void overnightWindowEndsOnTheNextDayWhenBeforeTonightsWindow() {
        // "now" is 22:00, before tonight's 23:15 start, so the upcoming window starts today and ends tomorrow.
        Clock clock = fixedClockAt(LocalDate.of(2025, 6, 1), LocalTime.of(22, 0));

        Optional<FixedWindowConstraints> constraints = FixedWindowExpressionParser.parse("23:15 02:15", clock, AMSTERDAM,
                Duration.ZERO);

        assertThat(constraints).isPresent();
        assertThat(constraints.get().getStartTime())
                .isEqualTo(ZonedDateTime.of(LocalDate.of(2025, 6, 1), LocalTime.of(23, 15), AMSTERDAM));
        assertThat(constraints.get().getEndTime())
                .isEqualTo(ZonedDateTime.of(LocalDate.of(2025, 6, 2), LocalTime.of(2, 15), AMSTERDAM));
    }

    @Test
    void overnightWindowStartedYesterdayWhenStillWithinLastNightsWindow() {
        // "now" is 01:00, still within last night's window, so it started yesterday and ends today.
        Clock clock = fixedClockAt(LocalDate.of(2025, 6, 2), LocalTime.of(1, 0));

        Optional<FixedWindowConstraints> constraints = FixedWindowExpressionParser.parse("23:15 02:15", clock, AMSTERDAM,
                Duration.ZERO);

        assertThat(constraints).isPresent();
        assertThat(constraints.get().getStartTime())
                .isEqualTo(ZonedDateTime.of(LocalDate.of(2025, 6, 1), LocalTime.of(23, 15), AMSTERDAM));
        assertThat(constraints.get().getEndTime())
                .isEqualTo(ZonedDateTime.of(LocalDate.of(2025, 6, 2), LocalTime.of(2, 15), AMSTERDAM));
    }

    private static Clock fixedClockAt(LocalDate date, LocalTime time) {
        return Clock.fixed(ZonedDateTime.of(date, time, AMSTERDAM).toInstant(), AMSTERDAM);
    }
}
