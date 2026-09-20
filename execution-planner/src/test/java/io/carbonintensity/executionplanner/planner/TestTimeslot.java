package io.carbonintensity.executionplanner.planner;

import static java.time.Duration.ofHours;
import static java.time.Duration.ofMinutes;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.carbonintensity.executionplanner.runtime.impl.CarbonIntensity;
import io.carbonintensity.executionplanner.runtime.impl.rest.CarbonIntensityJsonParser;

class TestTimeslot {
    static CarbonIntensity carbonIntensity;

    @BeforeAll
    public static void beforeAll() {
        CarbonIntensityJsonParser parser = new CarbonIntensityJsonParser();
        carbonIntensity = parser.parse(
                ClassLoader.getSystemResourceAsStream("day-ahead-20240824-Z.json"));
    }

    @Test
    void testFullDayInSeconds() {
        ZonedDateTime ws = ZonedDateTime.parse("2024-08-27T00:00:00Z");
        ZonedDateTime we = ZonedDateTime.parse("2024-08-28T00:00:00Z");

        List<Timeslot> timeslots = Timeslot.getTimeslots(ws, we, ofMinutes(60), ofSeconds(1), carbonIntensity);
        // should generate a timeslot for each second in the day
        assertThat(timeslots).hasSize(24 * 60 * 60 + 1);
        // check that the 3rd timeslot is actually the third second of the day
        assertThat(timeslots.get(2).start()).hasToString("2024-08-27T00:00:02Z");

    }

    @Test
    void testFullDayInQuarters() {
        ZonedDateTime ws = ZonedDateTime.parse("2024-08-27T00:00:00Z");
        ZonedDateTime we = ZonedDateTime.parse("2024-08-28T00:00:00Z");

        List<Timeslot> timeslots = Timeslot.getTimeslots(ws, we, ofMinutes(60), ofMinutes(15), carbonIntensity);
        // should generate a timeslot for each quarter-hour in the day
        assertThat(timeslots).hasSize(24 * 4 + 1);
        // check that the 12th timeslot is actually the 3rd hour of the day
        assertThat(timeslots.get(12).start()).hasToString("2024-08-27T03:00Z");
    }

    @Test
    void testDurationShorterThanWindowShouldWork() {
        // allow a 1-minute window
        ZonedDateTime ws = ZonedDateTime.parse("2024-08-27T00:00:00Z");
        ZonedDateTime we = ZonedDateTime.parse("2024-08-27T00:01:00Z");

        // but generate timeslots with a granularity of 1 hour
        List<Timeslot> timeslots = Timeslot.getTimeslots(ws, we, ofMinutes(60), ofHours(1), carbonIntensity);
        // should give exactly one slot
        assertThat(timeslots).hasSize(1);
    }

    @Test
    void testZeroWindow() {
        // allow a 0 window
        ZonedDateTime ws = ZonedDateTime.parse("2024-08-27T00:00:00Z");
        ZonedDateTime we = ZonedDateTime.parse("2024-08-27T00:00:00Z");

        // but generate timeslots with a granularity of 1 hour
        List<Timeslot> timeslots = Timeslot.getTimeslots(ws, we, ofMinutes(60), ofHours(1), carbonIntensity);
        // should give exactly one slot
        assertThat(timeslots).hasSize(1);
    }

    /**
     * CIIO-475 follow-up: after the original overlap-filter fix, {@code fixedWindow()} jobs in the pilot
     * still fired with a fabricated {@code intensityValue: 0.0} whenever the fetched data was a single
     * coarse-resolution reading (e.g. a synthesized last-known-value reused after a total fetch failure -
     * see {@code CarbonIntensityDataFetcherImpl}) rather than fine-grained real data.
     * <p>
     * Root cause: the partial-overlap arithmetic used {@code BigDecimal.divide(divisor, RoundingMode)},
     * which rounds the quotient to the <em>dividend's own scale</em>. A realistic, low-precision reading
     * (e.g. {@code "45.2"}, scale 1) divided by a multi-hour resolution (in seconds) rounds to exactly
     * {@code 0.0} - which the following multiply then keeps at exactly zero, indistinguishable from a
     * genuine zero-emission reading. This reproduces that exact shape directly against
     * {@code calculateCarbonIntensity}, without going through the fetcher/planner at all, across two
     * different underflow-prone combinations of value scale, resolution and covered fraction.
     */
    @ParameterizedTest(name = "{index}: value={1} scale={2}h resolution over a {3}-covered candidate must not prorate to 0.0")
    @MethodSource("underflowProneProrations")
    void aLowPrecisionValueOverACoarseResolutionMustNotProrateToAFabricatedZero(Duration resolution, BigDecimal value,
            Duration candidateOffset, Duration candidateDuration, BigDecimal expected) {
        Instant windowStart = Instant.parse("2026-09-18T02:00:00Z");
        CarbonIntensityPeriod wholeWindowAsOnePeriod = new CarbonIntensityPeriod(windowStart, resolution, value);

        ZonedDateTime candidateStart = windowStart.atZone(ZoneOffset.UTC).plus(candidateOffset);
        ZonedDateTime candidateEnd = candidateStart.plus(candidateDuration);

        var result = Timeslot.calculateCarbonIntensity(List.of(wholeWindowAsOnePeriod), candidateStart, candidateEnd);

        assertThat(result).isPresent();
        assertThat(result.get()).isNotEqualByComparingTo(BigDecimal.ZERO);
        if (expected != null) {
            assertThat(result.get()).isEqualByComparingTo(expected);
        }
    }

    static Stream<Arguments> underflowProneProrations() {
        return Stream.of(
                // a quarter of the 4-hour window's only reading (1 hour out of 4), not the fabricated 0.0
                Arguments.of(ofHours(4), new BigDecimal("45.2"), ofHours(1), ofHours(1), new BigDecimal("11.3")),
                // shortest covered fraction relative to resolution (15 min out of 6h) - the case most prone
                // to underflow, since the divided fraction is smaller still; no exact value pinned here.
                Arguments.of(ofHours(6), new BigDecimal("49"), Duration.ZERO, ofMinutes(15), null));
    }
}
