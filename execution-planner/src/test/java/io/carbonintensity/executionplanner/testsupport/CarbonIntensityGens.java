package io.carbonintensity.executionplanner.testsupport;

import java.time.Duration;
import java.time.Instant;

import io.vavr.test.Arbitrary;
import io.vavr.test.Gen;

/**
 * Shared vavr-test generators for carbon-intensity property tests, so the modeled time range and the set of
 * resolutions actually seen in practice are defined once rather than copy-pasted into every
 * {@code *Properties} test class.
 * <p>
 * See {@code docs/adr/0002-vavr-test-over-jqwik.md} (in carbonintensity-api) for why vavr-test rather than
 * jqwik is used here.
 */
public final class CarbonIntensityGens {

    private CarbonIntensityGens() {
    }

    /** Arbitrary instants spread across a multi-year range, so generated data doesn't always land near the epoch. */
    public static final Arbitrary<Instant> STARTS = size -> Gen.choose(0L, 4L * 365 * 24 * 60 * 60)
            .map(Instant::ofEpochSecond);

    /** Resolutions actually used in practice: quarter-hourly, half-hourly, hourly. */
    public static final Arbitrary<Duration> RESOLUTIONS = size -> Gen.choose(Duration.ofMinutes(15), Duration.ofMinutes(30),
            Duration.ofHours(1));
}
