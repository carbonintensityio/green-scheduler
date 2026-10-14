package io.carbonintensity.executionplanner.strategy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import io.carbonintensity.executionplanner.planner.Timeslot;
import io.carbonintensity.executionplanner.runtime.impl.CarbonIntensity;
import io.carbonintensity.executionplanner.testsupport.CarbonIntensityGens;
import io.vavr.Tuple;
import io.vavr.Tuple3;
import io.vavr.test.Arbitrary;
import io.vavr.test.Gen;
import io.vavr.test.Property;

/**
 * Property-based cross-check between the two {@link SingleJobStrategy} configurations actually used in
 * production - {@code new SingleJobStrategy(Duration.ofHours(1))}, used by {@code FixedWindowPlanner}, and
 * {@code new SingleJobStrategy()} (30-minute resolution), used by {@code SuccessivePlanner} - against the
 * exact same synthetic {@link CarbonIntensity} data. This is the permanent guard for the whole CIIO-475 class
 * of bug, not just the one scenario pinned in
 * {@code TestFixedWindowPlanner#aWindowStartingBeforeAnyRealDataMustNotWinWithAFabricatedZero}.
 * <p>
 * Shared invariant: a candidate slot with no genuine carbon-intensity data (a real data gap, see
 * {@link Timeslot#carbonIntensity()}) must never outrank a candidate with real data. Concretely, if
 * {@link SingleJobStrategy#rankedTimeslots} returns any slot with real (non-null) data, the winning slot -
 * {@code rankedTimeslots().get(0)}, equivalently {@code bestTimeslot(...)} - must also have real data. Before
 * the CIIO-475 fix this could fail: a data gap could be miscomputed as an actual, fabricated zero and win
 * outright, since zero beats any real (always positive, here) reading.
 * <p>
 * Data windows are generated so they routinely start or end outside the fetched data's actual coverage -
 * exactly the shape a fixed window's own start (or a successive gap window) has whenever it doesn't happen to
 * line up with the carbon-intensity data's first or last data point - rather than only ever exercising the
 * comfortable "window fully inside the data" case.
 * <p>
 * See {@code docs/adr/0002-vavr-test-over-jqwik.md} (in carbonintensity-api) for why vavr-test rather than
 * jqwik is used here.
 */
class TestSingleJobStrategyDataGapProperties {

    private static final SingleJobStrategy FIXED_WINDOW_STEP = new SingleJobStrategy(Duration.ofHours(1));
    private static final SingleJobStrategy SUCCESSIVE_STEP = new SingleJobStrategy();

    private static final Arbitrary<Instant> DATA_STARTS = CarbonIntensityGens.STARTS;
    private static final Arbitrary<Duration> RESOLUTIONS = CarbonIntensityGens.RESOLUTIONS;

    // dataSize: how many data points the fetched CarbonIntensity has (2..10).
    // windowStartOffsetInResolutions: the job window's start, relative to the data's own start, in units of
    // resolution - negative values start the window before any data exists (the CIIO-475 shape), positive
    // values start it partway through, so both "window starts before the data" and "window fully inside the
    // data" are exercised.
    // windowLengthInResolutions: the job window's length, in units of resolution.
    private static final Arbitrary<Tuple3<Integer, Integer, Integer>> SHAPE = size -> Gen.choose(2, 10)
            .flatMap(dataSize -> Gen.choose(-3, 3)
                    .flatMap(windowStartOffset -> Gen.choose(1, dataSize + 3)
                            .map(windowLength -> Tuple.of(dataSize, windowStartOffset, windowLength))));

    @Test
    void aDataGapNeverOutranksRealDataForEitherProductionStrategy() {
        Property.def("neither FixedWindowPlanner's 1h-step SingleJobStrategy nor SuccessivePlanner's default "
                + "30-min-step one ever lets a data gap win over a slot with real data")
                .forAll(DATA_STARTS, RESOLUTIONS, SHAPE)
                .suchThat((dataStart, resolution, shape) -> {
                    int dataSize = shape._1;
                    int windowStartOffset = shape._2;
                    int windowLength = shape._3;

                    CarbonIntensity carbonIntensity = new CarbonIntensity();
                    carbonIntensity.setZone("NL");
                    carbonIntensity.setResolution(resolution);
                    carbonIntensity.setStart(dataStart);
                    // unique, always-positive values: never confusable with a fabricated zero
                    carbonIntensity.setData(IntStream.rangeClosed(1, dataSize)
                            .mapToObj(BigDecimal::valueOf)
                            .collect(Collectors.toList()));
                    carbonIntensity.setEnd(dataStart.plus(resolution.multipliedBy(dataSize)));

                    ZonedDateTime ws = ZonedDateTime.ofInstant(dataStart, ZoneOffset.UTC)
                            .plus(resolution.multipliedBy(windowStartOffset));
                    ZonedDateTime we = ws.plus(resolution.multipliedBy(windowLength));
                    Duration jobDuration = resolution;

                    return neverLetsADataGapOutrankRealData(
                            FIXED_WINDOW_STEP.rankedTimeslots(ws, we, jobDuration, carbonIntensity))
                            && neverLetsADataGapOutrankRealData(
                                    SUCCESSIVE_STEP.rankedTimeslots(ws, we, jobDuration, carbonIntensity));
                })
                .check()
                .assertIsSatisfied();
    }

    private static boolean neverLetsADataGapOutrankRealData(List<Timeslot> ranked) {
        if (ranked.isEmpty()) {
            return true;
        }
        boolean anyRealData = ranked.stream().anyMatch(t -> t.carbonIntensity() != null);
        // if there's no real data anywhere in the window, there's nothing for a gap to unfairly outrank
        return !anyRealData || ranked.get(0).carbonIntensity() != null;
    }
}
