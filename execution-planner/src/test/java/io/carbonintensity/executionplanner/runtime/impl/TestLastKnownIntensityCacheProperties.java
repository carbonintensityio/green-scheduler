package io.carbonintensity.executionplanner.runtime.impl;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.vavr.Tuple;
import io.vavr.Tuple2;
import io.vavr.test.Arbitrary;
import io.vavr.test.Gen;
import io.vavr.test.Property;

/**
 * Property-based tests for {@link LastKnownIntensityCache}'s staleness-cutoff behaviour (CIIO-470): a
 * recorded value must be returned for as long as its age is within the caller-supplied staleness threshold,
 * and refused once it isn't - regardless of the concrete zone, value, or threshold involved.
 * <p>
 * See {@code docs/adr/0002-vavr-test-over-jqwik.md} (in carbonintensity-api) for why vavr-test rather than
 * jqwik is used here.
 */
class TestLastKnownIntensityCacheProperties {

    private static final Arbitrary<Instant> FETCHED_AT_INSTANTS = size -> Gen.choose(0L, 2_000_000_000L)
            .map(Instant::ofEpochSecond);

    private static final Arbitrary<String> ZONES = Arbitrary.of("NL", "BE", "DE", "FR", "GB", "pl", " nl ");

    // Plausible gCO2eq/kWh values.
    private static final Arbitrary<BigDecimal> VALUES = size -> Gen.choose(0L, 2000L).map(BigDecimal::valueOf);

    // Plausible staleness thresholds: 1 minute to 24 hours.
    private static final Arbitrary<Duration> THRESHOLDS = size -> Gen.choose(60L, 86_400L).map(Duration::ofSeconds);

    // An age (in seconds) strictly within [0, threshold] and one strictly beyond it, paired with the
    // threshold itself.
    private static final Arbitrary<Tuple2<Duration, Long>> THRESHOLD_AND_FRESH_AGE = size -> THRESHOLDS.apply(size)
            .flatMap(threshold -> Gen.choose(0L, threshold.getSeconds()).map(age -> Tuple.of(threshold, age)));

    private static final Arbitrary<Tuple2<Duration, Long>> THRESHOLD_AND_STALE_AGE = size -> THRESHOLDS.apply(size)
            .flatMap(threshold -> Gen.choose(1L, 1_000_000L)
                    .map(overshoot -> Tuple.of(threshold, threshold.getSeconds() + overshoot)));

    @Test
    void getReturnsTheValueWhenItsAgeIsWithinTheStalenessThreshold() {
        Property.def("get(zone, now, threshold) is present and equal to the recorded value, whenever "
                + "now - fetchedAt <= threshold")
                .forAll(ZONES, VALUES, FETCHED_AT_INSTANTS, THRESHOLD_AND_FRESH_AGE)
                .suchThat((zone, value, fetchedAt, thresholdAndAge) -> {
                    var cache = new LastKnownIntensityCache();
                    cache.record(zone, value, fetchedAt);
                    Instant now = fetchedAt.plusSeconds(thresholdAndAge._2);

                    return cache.get(zone, now, thresholdAndAge._1)
                            .map(value::equals)
                            .orElse(false);
                })
                .check()
                .assertIsSatisfied();
    }

    @Test
    void getIsAbsentOnceTheValueIsOlderThanTheStalenessThreshold() {
        Property.def("get(zone, now, threshold) is absent whenever now - fetchedAt > threshold")
                .forAll(ZONES, VALUES, FETCHED_AT_INSTANTS, THRESHOLD_AND_STALE_AGE)
                .suchThat((zone, value, fetchedAt, thresholdAndAge) -> {
                    var cache = new LastKnownIntensityCache();
                    cache.record(zone, value, fetchedAt);
                    Instant now = fetchedAt.plusSeconds(thresholdAndAge._2);

                    return cache.get(zone, now, thresholdAndAge._1).isEmpty();
                })
                .check()
                .assertIsSatisfied();
    }

    @Test
    void getIsAbsentWhenNothingWasEverRecordedForTheZone() {
        Property.def("get(zone, ...) is absent when record(zone, ...) was never called")
                .forAll(ZONES, FETCHED_AT_INSTANTS, THRESHOLDS)
                .suchThat((zone, now, threshold) -> new LastKnownIntensityCache().get(zone, now, threshold).isEmpty())
                .check()
                .assertIsSatisfied();
    }

    @Test
    void zoneLookupIsCaseAndWhitespaceInsensitive() {
        Property.def("a value recorded for one spelling of a zone is retrievable under any other equivalent spelling")
                .forAll(FETCHED_AT_INSTANTS, VALUES)
                .suchThat((fetchedAt, value) -> {
                    var cache = new LastKnownIntensityCache();
                    cache.record(" Nl\t", value, fetchedAt);

                    return cache.get("nl", fetchedAt, Duration.ofHours(1)).map(value::equals).orElse(false);
                })
                .check()
                .assertIsSatisfied();
    }
}
