package io.carbonintensity.executionplanner.planner;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.carbonintensity.executionplanner.runtime.impl.CarbonIntensity;

/**
 * {@link CarbonIntensityPeriod} had no direct test at all - it was only exercised indirectly
 * through {@link TestTimeslot}, which never inspects its own behavior. PIT (CIIO-339) found
 * {@code equals}/{@code compareTo} entirely unreached, and every boundary comparison in
 * {@link CarbonIntensityPeriod#contains} survives - existing indirect coverage never places a
 * point exactly on the period's start or end.
 */
class CarbonIntensityPeriodTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration RESOLUTION = Duration.ofMinutes(15);

    private CarbonIntensityPeriod period(BigDecimal value) {
        return CarbonIntensityPeriod.of(carbonIntensity(value)).get(0);
    }

    private CarbonIntensity carbonIntensity(BigDecimal value) {
        CarbonIntensity ci = new CarbonIntensity();
        ci.setStart(START);
        ci.setResolution(RESOLUTION);
        ci.setData(List.of(value));
        return ci;
    }

    @Test
    void ofBuildsOnePeriodPerDataPointStartingAtTheGivenInstant() {
        List<CarbonIntensityPeriod> periods = CarbonIntensityPeriod.of(carbonIntensity(BigDecimal.TEN));

        assertThat(periods).hasSize(1);
        assertThat(periods.get(0).moment()).isEqualTo(START);
        assertThat(periods.get(0).value()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(periods.get(0).resolution()).isEqualTo(RESOLUTION);
    }

    @Test
    void containsIsInclusiveOfTheStartAndExclusiveOfTheEndInstant() {
        CarbonIntensityPeriod p = period(BigDecimal.ONE);
        Instant end = START.plus(RESOLUTION);

        // Kills the "changed conditional boundary"/"negated conditional" mutants on the contains()
        // comparisons: a point exactly on the start edge must count as contained, one nanosecond
        // before the start must not, and neither must the end instant itself.
        //
        // The upper bound is deliberately EXCLUSIVE, not inclusive: CarbonIntensityPeriod.of(...)
        // produces contiguous periods where period[i].moment() + resolution == period[i + 1].moment().
        // If both bounds were inclusive (as an earlier version of this test asserted), that shared
        // boundary instant would be reported as contained by two consecutive periods at once - see
        // CIIO-366, which found this exact double-inclusive bug via a property test.
        assertThat(p.contains(START)).as("exactly at the start").isTrue();
        assertThat(p.contains(end)).as("exactly at the end").isFalse();
        assertThat(p.contains(START.minusNanos(1))).as("one ns before the start").isFalse();
        assertThat(p.contains(end.minusNanos(1))).as("one ns before the end").isTrue();
        assertThat(p.contains(START.plusSeconds(1))).as("well inside").isTrue();
    }

    @Test
    void equalsAndHashCodeAreBasedOnMomentValueAndResolution() {
        CarbonIntensityPeriod a = period(BigDecimal.valueOf(42));
        CarbonIntensityPeriod b = period(BigDecimal.valueOf(42));
        CarbonIntensityPeriod differentValue = period(BigDecimal.valueOf(43));

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(differentValue);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("not a period");
    }

    @Test
    void compareToOrdersByMomentThenResolution() {
        CarbonIntensityPeriod earlier = CarbonIntensityPeriod.of(carbonIntensity(BigDecimal.ONE)).get(0);
        CarbonIntensity laterCi = carbonIntensity(BigDecimal.ONE);
        laterCi.setStart(START.plus(RESOLUTION));
        CarbonIntensityPeriod later = CarbonIntensityPeriod.of(laterCi).get(0);

        assertThat(earlier.compareTo(later)).isNegative();
        assertThat(later.compareTo(earlier)).isPositive();
        assertThat(earlier.compareTo(earlier)).isZero();
    }
}
