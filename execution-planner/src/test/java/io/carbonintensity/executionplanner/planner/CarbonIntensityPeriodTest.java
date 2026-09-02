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
    void containsIsInclusiveOfBothTheStartAndEndInstantExactly() {
        CarbonIntensityPeriod p = period(BigDecimal.ONE);
        Instant end = START.plus(RESOLUTION);

        // Kills the "changed conditional boundary"/"negated conditional" mutants on line 74:
        // a point exactly on either edge must count as contained, and one nanosecond outside
        // either edge must not.
        assertThat(p.contains(START)).as("exactly at the start").isTrue();
        assertThat(p.contains(end)).as("exactly at the end").isTrue();
        assertThat(p.contains(START.minusNanos(1))).as("one ns before the start").isFalse();
        assertThat(p.contains(end.plusNanos(1))).as("one ns after the end").isFalse();
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
