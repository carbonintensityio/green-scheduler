package io.carbonintensity.executionplanner.planner;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import io.carbonintensity.executionplanner.runtime.impl.CarbonIntensity;
import io.vavr.test.Arbitrary;
import io.vavr.test.Gen;
import io.vavr.test.Property;

/**
 * Property-based tests for {@link CarbonIntensityPeriod#of(CarbonIntensity)} and
 * {@link CarbonIntensityPeriod#contains(Instant)},
 * alongside the example-based coverage of {@link Timeslot} in {@code TestTimeslot}.
 * <p>
 * See {@code docs/adr/0002-vavr-test-over-jqwik.md} (in carbonintensity-api) for why vavr-test rather than
 * jqwik is used here.
 */
class TestCarbonIntensityPeriodProperties {

    // Arbitrary start instants spread across a multi-year range, so generated periods land on a variety of
    // moments rather than always near the epoch.
    private static final Gen<Instant> STARTS_GEN = Gen.choose(0L, 4L * 365 * 24 * 60 * 60)
            .map(Instant::ofEpochSecond);
    private static final Arbitrary<Instant> STARTS = size -> STARTS_GEN;

    // Resolutions actually used in practice (quarter-hourly, half-hourly, hourly).
    private static final Gen<Duration> RESOLUTIONS_GEN = Gen.choose(Duration.ofMinutes(15), Duration.ofMinutes(30),
            Duration.ofHours(1));
    private static final Arbitrary<Duration> RESOLUTIONS = size -> RESOLUTIONS_GEN;

    // At least 2 data points, so there is always at least one adjacent pair of periods to check.
    private static final Gen<Integer> DATA_SIZES_GEN = Gen.choose(2, 20);
    private static final Arbitrary<Integer> DATA_SIZES = size -> DATA_SIZES_GEN;

    // CIIO-366: contains(...) used to be doubly-inclusive ([moment, moment + resolution], both bounds closed),
    // so the instant shared by two consecutive periods (A.moment() + resolution == B.moment()) was reported as
    // contained by both A and B at once. Fixed in CarbonIntensityPeriod#contains by making the interval half-open
    // ([moment, moment + resolution)), matching the contiguous, non-overlapping periods that of(...) produces.
    // Before the fix: a.contains(boundary) && b.contains(boundary) could both be true.
    // After the fix: exactly b.contains(boundary) is true, a.contains(boundary) is false.
    @Test
    void adjacentPeriodsDoNotBothContainTheSharedBoundaryInstant() {
        Property.def("for adjacent periods A, B where B starts exactly where A ends, "
                + "the shared boundary instant is not contained by both at once")
                .forAll(STARTS, RESOLUTIONS, DATA_SIZES)
                .suchThat((start, resolution, size) -> {
                    List<CarbonIntensityPeriod> periods = buildPeriods(start, resolution, size);
                    for (int i = 0; i < periods.size() - 1; i++) {
                        CarbonIntensityPeriod a = periods.get(i);
                        CarbonIntensityPeriod b = periods.get(i + 1);
                        Instant boundary = a.moment().plus(resolution);
                        if (a.contains(boundary) && b.contains(boundary)) {
                            return false;
                        }
                    }
                    return true;
                })
                .check()
                .assertIsSatisfied();
    }

    // Deterministic complement to the property above: a concrete pair of adjacent periods, so a regression is
    // caught on every run rather than only when the property generator happens to land on this shape.
    @Test
    void boundaryInstantBelongsOnlyToTheLaterPeriod() {
        Instant start = Instant.parse("2024-08-27T00:00:00Z");
        Duration resolution = Duration.ofMinutes(15);
        List<CarbonIntensityPeriod> periods = buildPeriods(start, resolution, 2);

        CarbonIntensityPeriod a = periods.get(0);
        CarbonIntensityPeriod b = periods.get(1);
        Instant boundary = a.moment().plus(resolution);

        assertThat(boundary).isEqualTo(b.moment());
        assertThat(a.contains(boundary)).isFalse();
        assertThat(b.contains(boundary)).isTrue();
    }

    // Invariant 1: of(...) always produces contiguous periods, regardless of start instant, resolution or data size.
    @Test
    void generatedPeriodsAreContiguous() {
        Property.def("CarbonIntensityPeriod.of(...) produces contiguous periods: "
                + "period[i].moment() + resolution == period[i + 1].moment()")
                .forAll(STARTS, RESOLUTIONS, DATA_SIZES)
                .suchThat((start, resolution, size) -> {
                    List<CarbonIntensityPeriod> periods = buildPeriods(start, resolution, size);
                    for (int i = 0; i < periods.size() - 1; i++) {
                        if (!periods.get(i).moment().plus(resolution).equals(periods.get(i + 1).moment())) {
                            return false;
                        }
                    }
                    return true;
                })
                .check()
                .assertIsSatisfied();
    }

    private static List<CarbonIntensityPeriod> buildPeriods(Instant start, Duration resolution, int size) {
        CarbonIntensity carbonIntensity = new CarbonIntensity();
        carbonIntensity.setStart(start);
        carbonIntensity.setResolution(resolution);
        carbonIntensity.setData(IntStream.range(0, size)
                .mapToObj(BigDecimal::valueOf)
                .collect(Collectors.toList()));
        return CarbonIntensityPeriod.of(carbonIntensity);
    }
}
