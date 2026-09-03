package io.carbonintensity.executionplanner.runtime.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.vavr.Tuple;
import io.vavr.Tuple2;
import io.vavr.test.Arbitrary;
import io.vavr.test.Gen;
import io.vavr.test.Property;

/**
 * Property-based tests complementing the example-based tests in {@link TestCarbonIntensityCache}, covering two
 * invariants of {@link CarbonIntensityCache}:
 * <ul>
 * <li>{@link CarbonIntensityCache.Key} equality/hashCode must be consistent with hour-truncation, for any
 * {@link Instant} and any zone spelling (case/whitespace) - including around a DST fall-back overlap, where a
 * local hour repeats but the underlying {@link Instant}s remain distinct.</li>
 * <li>{@link CarbonIntensityCache#computeExpireAfterCreateNanos} must always return a non-negative nanosecond
 * TTL, even when {@code value.getEnd()} already lies in the past relative to "now": a negative value handed to
 * Caffeine's custom {@code Expiry} has undefined behaviour.</li>
 * </ul>
 *
 * <p>
 * See {@code docs/adr/0002-vavr-test-over-jqwik.md} (in carbonintensity-api) for why vavr-test rather than
 * jqwik is used here.
 */
class TestCarbonIntensityCacheProperties {

    private static final ZoneId AMSTERDAM = ZoneId.of("Europe/Amsterdam");

    // A plausible range of instants (roughly the years 2001-2033), same range as the sibling
    // TestCarbonIntensityJsonParserProperties, rather than the full Instant range.
    private static final Arbitrary<Instant> INSTANTS = size -> Gen.choose(0L, 2_000_000_000L).map(Instant::ofEpochSecond);

    private static final Arbitrary<String> BASE_ZONES = Arbitrary.of("NL", "BE", "DE", "FR", "GB");

    // Two instants guaranteed to truncate to the same hour: an arbitrary hour start, plus two independent
    // (possibly different) offsets within [0, 3599] seconds.
    private static final Arbitrary<Tuple2<Instant, Instant>> SAME_HOUR_INSTANTS = size -> INSTANTS.apply(size)
            .map(instant -> instant.truncatedTo(ChronoUnit.HOURS))
            .flatMap(hourStart -> Gen.choose(0, 3599)
                    .flatMap(offset1 -> Gen.choose(0, 3599)
                            .map(offset2 -> Tuple.of(hourStart.plusSeconds(offset1), hourStart.plusSeconds(offset2)))));

    // Two instants guaranteed to fall in *different* hours: an hour start, plus a nonzero hour offset (1-24h)
    // applied to the second one.
    private static final Arbitrary<Tuple2<Instant, Instant>> DIFFERENT_HOUR_INSTANTS = size -> INSTANTS.apply(size)
            .map(instant -> instant.truncatedTo(ChronoUnit.HOURS))
            .flatMap(hourStart -> Gen.choose(1, 24)
                    .map(hourOffset -> Tuple.of(hourStart, hourStart.plusSeconds(hourOffset * 3600L))));

    // Two spellings of the same zone that Key must treat as identical: any combination of upper/lower case and
    // surrounding whitespace (Key normalizes via toLowerCase().trim()).
    private static final Arbitrary<Tuple2<String, String>> EQUIVALENT_ZONE_SPELLINGS = size -> BASE_ZONES.apply(size)
            .flatMap(base -> Gen.choose(0, 4)
                    .flatMap(variant1 -> Gen.choose(0, 4)
                            .map(variant2 -> Tuple.of(zoneVariant(base, variant1), zoneVariant(base, variant2)))));

    private static String zoneVariant(String base, int variant) {
        return switch (variant) {
            case 0 -> base;
            case 1 -> base.toUpperCase();
            case 2 -> base.toLowerCase();
            case 3 -> " " + base + " ";
            case 4 -> "\t" + base.toLowerCase() + "\n";
            default -> throw new IllegalArgumentException("Unexpected variant: " + variant);
        };
    }

    @Test
    void keysAreEqualWhenInstantsShareAnHourAndZonesAreEquivalentSpellings() {
        Property.def("Key(t1, z1).equals(Key(t2, z2)) and same hashCode, when t1/t2 truncate to the same hour "
                + "and z1/z2 are equivalent zone spellings")
                .forAll(SAME_HOUR_INSTANTS, EQUIVALENT_ZONE_SPELLINGS)
                .suchThat((instants, zones) -> {
                    var key1 = new CarbonIntensityCache.Key(instants._1, zones._1);
                    var key2 = new CarbonIntensityCache.Key(instants._2, zones._2);

                    return key1.equals(key2) && key2.equals(key1) && key1.hashCode() == key2.hashCode();
                })
                .check()
                .assertIsSatisfied();
    }

    @Test
    void keysAreNotEqualWhenInstantsFallInDifferentHours() {
        Property.def("Key(t1, z).equals(Key(t2, z)) is false when t1/t2 truncate to different hours")
                .forAll(DIFFERENT_HOUR_INSTANTS, BASE_ZONES)
                .suchThat((instants, zone) -> {
                    var key1 = new CarbonIntensityCache.Key(instants._1, zone);
                    var key2 = new CarbonIntensityCache.Key(instants._2, zone);

                    return !key1.equals(key2);
                })
                .check()
                .assertIsSatisfied();
    }

    // Deterministic complement to the properties above: Key truncates the raw (UTC) Instant, so it is
    // unaffected by DST - but this pins down that a repeated local hour during the Europe/Amsterdam fall-back
    // overlap (2025-10-26, clocks go back from 03:00 CEST to 02:00 CET) still produces two distinct Keys,
    // since the two occurrences of local 02:30 are, in fact, an hour apart in absolute (UTC) time.
    @Test
    void keysStayDistinctAcrossTheFallBackOverlapRepeatedLocalHour() {
        LocalDateTime repeatedLocalTime = LocalDateTime.of(2025, 10, 26, 2, 30);
        Instant firstOccurrence = ZonedDateTime.of(repeatedLocalTime, AMSTERDAM).withEarlierOffsetAtOverlap().toInstant();
        Instant secondOccurrence = ZonedDateTime.of(repeatedLocalTime, AMSTERDAM).withLaterOffsetAtOverlap().toInstant();

        // Sanity check on the scenario itself: same local wall-clock time, one absolute hour apart.
        assertThat(Duration.between(firstOccurrence, secondOccurrence)).isEqualTo(Duration.ofHours(1));

        var key1 = new CarbonIntensityCache.Key(firstOccurrence, "NL");
        var key2 = new CarbonIntensityCache.Key(secondOccurrence, "NL");

        assertThat(key1).isNotEqualTo(key2);
        assertThat(key1.hashCode()).isNotEqualTo(key2.hashCode());
    }

    // Offset (possibly negative) from "now" to the carbon-intensity block's end. Negative means the block
    // already ended before "now" - e.g. a stale upstream response, or a slow fetch that only completes after
    // the block it describes has expired - the edge case the existing example-based tests don't cover.
    private static final Arbitrary<Integer> END_OFFSET_SECONDS = size -> Gen.choose(-200_000, 200_000);

    @Test
    void expireAfterCreateNanosIsNeverNegativeEvenWhenEndIsAlreadyInThePast() {
        Property.def("computeExpireAfterCreateNanos(...) >= 0 for any 'now'/end combination")
                .forAll(INSTANTS, END_OFFSET_SECONDS)
                .suchThat((now, endOffsetSeconds) -> {
                    var value = new CarbonIntensity();
                    value.setData(List.of(BigDecimal.ONE));
                    value.setEnd(now.plusSeconds(endOffsetSeconds));

                    long nanos = CarbonIntensityCache.computeExpireAfterCreateNanos(value, now, Duration.ofHours(1));

                    return nanos >= 0;
                })
                .check()
                .assertIsSatisfied();
    }

    @Test
    void expireAfterCreateNanosIsAlwaysTheEmptyValueTtlWhenDataIsEmpty() {
        Property.def("computeExpireAfterCreateNanos(...) == emptyValueTTL.toNanos() whenever value.getData() is empty, "
                + "regardless of 'now' or end")
                .forAll(INSTANTS, END_OFFSET_SECONDS)
                .suchThat((now, endOffsetSeconds) -> {
                    var value = new CarbonIntensity();
                    value.setData(List.of());
                    value.setEnd(now.plusSeconds(endOffsetSeconds));
                    var emptyValueTTL = Duration.ofHours(1);

                    long nanos = CarbonIntensityCache.computeExpireAfterCreateNanos(value, now, emptyValueTTL);

                    return nanos == emptyValueTTL.toNanos();
                })
                .check()
                .assertIsSatisfied();
    }
}
