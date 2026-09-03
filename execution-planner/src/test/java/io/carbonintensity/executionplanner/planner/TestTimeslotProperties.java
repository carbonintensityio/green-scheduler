package io.carbonintensity.executionplanner.planner;

import static java.time.Duration.ofMinutes;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.carbonintensity.executionplanner.runtime.impl.CarbonIntensity;
import io.vavr.Tuple;
import io.vavr.Tuple2;
import io.vavr.test.Arbitrary;
import io.vavr.test.Gen;
import io.vavr.test.Property;

/**
 * Property-based tests for {@link Timeslot#getTimeslots}, alongside the example-based tests in
 * {@link TestTimeslot}.
 * <p>
 * The invariant checked here: for any window {@code [ws, we]}, {@code timeslotDuration} and {@code resolution},
 * {@code getTimeslots} generates exactly {@code floor((we-ws)/resolution) + 1} slots, starting at
 * {@code ws + i*resolution} for {@code i = 0..n-1}, and never starting past {@code we}. Window length and
 * resolution are generated independently, so a resolution that does NOT evenly divide the window is the common
 * case rather than something that needs to be special-cased.
 *
 * <p>
 * See {@code docs/adr/0002-vavr-test-over-jqwik.md} (in carbonintensity-api) for why vavr-test rather than
 * jqwik is used here.
 */
class TestTimeslotProperties {

    private static final ZoneId AMSTERDAM = ZoneId.of("Europe/Amsterdam");

    // getTimeslots doesn't consult any carbon intensity data to decide how many slots to generate or where they
    // start - an empty CarbonIntensity (no data points) keeps calculateCarbonIntensity a no-op (BigDecimal.ZERO),
    // so the property below can focus purely on the slot-count/start-time invariant.
    private static final CarbonIntensity EMPTY_CARBON_INTENSITY = new CarbonIntensity();

    private static final Arbitrary<ZonedDateTime> WINDOW_STARTS = size -> Gen.choose(0L, 4L * 365 * 24 * 3600)
            .map(offsetSeconds -> ZonedDateTime.ofInstant(Instant.EPOCH.plusSeconds(offsetSeconds), ZoneOffset.UTC));

    // Window length and resolution, both in seconds, generated independently of one another (and including 0 for
    // the window length, to exercise the "allow equal for 0 windows" edge case).
    private static final Arbitrary<Tuple2<Long, Long>> WINDOW_LENGTH_AND_RESOLUTION = size -> Gen.choose(0L, 2_000L)
            .flatMap(
                    windowSeconds -> Gen.choose(1L, 200L).map(resolutionSeconds -> Tuple.of(windowSeconds, resolutionSeconds)));

    @Test
    void numberOfSlotsAndTheirStartTimesMatchTheClosedFormFormula() {
        Property
                .def("getTimeslots(ws, we, ...) generates floor((we-ws)/resolution)+1 slots starting at ws+i*resolution, never past we")
                .forAll(WINDOW_STARTS, WINDOW_LENGTH_AND_RESOLUTION)
                .suchThat((ws, windowAndResolution) -> {
                    long windowSeconds = windowAndResolution._1;
                    long resolutionSeconds = windowAndResolution._2;
                    ZonedDateTime we = ws.plusSeconds(windowSeconds);
                    Duration resolution = ofSeconds(resolutionSeconds);
                    // irrelevant to the slot count/positions invariant under test; kept fixed and short
                    Duration timeslotDuration = ofSeconds(1);

                    List<Timeslot> timeslots = Timeslot.getTimeslots(ws, we, timeslotDuration, resolution,
                            EMPTY_CARBON_INTENSITY);

                    long expectedCount = windowSeconds / resolutionSeconds + 1;
                    if (timeslots.size() != expectedCount) {
                        return false;
                    }
                    for (int i = 0; i < timeslots.size(); i++) {
                        ZonedDateTime expectedStart = ws.plusSeconds(i * resolutionSeconds);
                        ZonedDateTime actualStart = timeslots.get(i).start();
                        if (!actualStart.isEqual(expectedStart) || actualStart.isAfter(we)) {
                            return false;
                        }
                    }
                    // one slot beyond the generated ones would always overshoot the window
                    ZonedDateTime afterLastSlot = ws.plusSeconds(timeslots.size() * resolutionSeconds);
                    return afterLastSlot.isAfter(we);
                })
                .check()
                .assertIsSatisfied();
    }

    // Deterministic complements to the property above.

    @Test
    void zeroLengthWindowGeneratesExactlyOneSlot() {
        // the "allow equal for 0 windows" case in Timeslot.getTimeslots's loop condition (!s.isAfter(we))
        ZonedDateTime ws = ZonedDateTime.parse("2024-08-27T00:00:00Z");
        ZonedDateTime we = ws;

        List<Timeslot> timeslots = Timeslot.getTimeslots(ws, we, ofMinutes(60), ofSeconds(1), EMPTY_CARBON_INTENSITY);

        assertThat(timeslots).hasSize(1);
        assertThat(timeslots.get(0).start()).isEqualTo(ws);
    }

    @Test
    void springForwardDayHas23HoursWorthOfTimeslots() {
        // Europe/Amsterdam DST: clocks skip forward an hour, so this calendar "day" is only 23 real hours long.
        ZonedDateTime ws = ZonedDateTime.of(2025, 3, 30, 0, 0, 0, 0, AMSTERDAM);
        ZonedDateTime we = ws.plusDays(1);
        assertThat(Duration.between(ws, we)).isEqualTo(Duration.ofHours(23));

        List<Timeslot> timeslots = Timeslot.getTimeslots(ws, we, ofMinutes(60), ofMinutes(15), EMPTY_CARBON_INTENSITY);

        assertThat(timeslots).hasSize(23 * 4 + 1);
        assertThat(timeslots.get(timeslots.size() - 1).start()).isEqualTo(we);
    }

    @Test
    void fallBackDayHas25HoursWorthOfTimeslots() {
        // Europe/Amsterdam DST: clocks fall back an hour, so this calendar "day" is 25 real hours long.
        ZonedDateTime ws = ZonedDateTime.of(2025, 10, 26, 0, 0, 0, 0, AMSTERDAM);
        ZonedDateTime we = ws.plusDays(1);
        assertThat(Duration.between(ws, we)).isEqualTo(Duration.ofHours(25));

        List<Timeslot> timeslots = Timeslot.getTimeslots(ws, we, ofMinutes(60), ofMinutes(15), EMPTY_CARBON_INTENSITY);

        assertThat(timeslots).hasSize(25 * 4 + 1);
        assertThat(timeslots.get(timeslots.size() - 1).start()).isEqualTo(we);
    }
}
