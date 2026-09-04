package io.carbonintensity.executionplanner.planner;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.carbonintensity.executionplanner.runtime.impl.CarbonIntensity;

/**
 * Direct tests of {@link Timeslot#calculateCarbonIntensity(List, ZonedDateTime, ZonedDateTime)},
 * placing a job's start/end exactly on a carbon-intensity period's boundary. {@link TestTimeslot}
 * only ever asserts on timeslot *counts* through {@link Timeslot#getTimeslots}, never on this
 * method's actual result, and never with a boundary-exact value - PIT (CIIO-336) found every
 * comparison in the three overlap branches survives as a result.
 */
class TimeslotCalculateCarbonIntensityTest {

    // One carbon-intensity period: [2026-01-01T00:00Z, 2026-01-01T01:00Z], value 100.
    private static final ZonedDateTime CI_START = ZonedDateTime.parse("2026-01-01T00:00:00Z");
    private static final Duration RESOLUTION = Duration.ofHours(1);
    private static final BigDecimal CI_VALUE = BigDecimal.valueOf(100);

    private static List<CarbonIntensityPeriod> onePeriod() {
        CarbonIntensity ci = new CarbonIntensity();
        ci.setStart(CI_START.toInstant());
        ci.setResolution(RESOLUTION);
        ci.setData(List.of(CI_VALUE));
        return CarbonIntensityPeriod.of(ci);
    }

    @Test
    void jobExactlyCoveringThePeriodGetsItsFullValue() {
        // start == ciStart, end == ciEnd - kills the "changed conditional boundary"/"negated
        // conditional" mutants on line 78-79 that decide "full overlap".
        BigDecimal result = Timeslot.calculateCarbonIntensity(onePeriod(), CI_START, CI_START.plus(RESOLUTION));

        assertThat(result).isEqualByComparingTo(CI_VALUE);
    }

    @Test
    void jobStartingExactlyAtThePeriodEndAndEndingAfterGetsNoWeightFromIt() {
        // start == ciEnd (one instant past the boundary this test is guarding), well after the
        // period - kills a "changed conditional boundary" mutant on line 82 by proving a job that
        // starts right where the period ends does not get charged for it.
        ZonedDateTime jobStart = CI_START.plus(RESOLUTION);
        ZonedDateTime jobEnd = jobStart.plus(RESOLUTION);

        BigDecimal result = Timeslot.calculateCarbonIntensity(onePeriod(), jobStart, jobEnd);

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void jobStartingInsideThePeriodAndEndingAfterGetsAProportionalShare() {
        // start is 15 minutes (a quarter) into the hour-long period, ends well after it - exercises
        // the "job starts in or on ci window, ends after" branch (line 82-89) with a start value
        // that is neither the period's exact start nor exact end.
        ZonedDateTime jobStart = CI_START.plus(Duration.ofMinutes(45));
        ZonedDateTime jobEnd = jobStart.plus(RESOLUTION);

        BigDecimal result = Timeslot.calculateCarbonIntensity(onePeriod(), jobStart, jobEnd);

        // 15 of the period's 60 minutes overlap -> a quarter of its value.
        assertThat(result).isEqualByComparingTo(BigDecimal.valueOf(25));
    }

    @Test
    void jobEndingExactlyAtThePeriodStartGetsNoWeightFromIt() {
        // end == ciStart - kills a "negated conditional"/"changed conditional boundary" mutant on
        // line 95 by proving a job that ends exactly where the period begins isn't charged for it.
        ZonedDateTime jobStart = CI_START.minus(RESOLUTION);
        ZonedDateTime jobEnd = CI_START;

        BigDecimal result = Timeslot.calculateCarbonIntensity(onePeriod(), jobStart, jobEnd);

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void jobEndingInsideThePeriodAndStartingBeforeGetsAProportionalShare() {
        // starts well before the period, ends 15 minutes into it - exercises the "job ends in or on
        // ci window, starts before" branch (line 95-98).
        ZonedDateTime jobStart = CI_START.minus(RESOLUTION);
        ZonedDateTime jobEnd = CI_START.plus(Duration.ofMinutes(15));

        BigDecimal result = Timeslot.calculateCarbonIntensity(onePeriod(), jobStart, jobEnd);

        assertThat(result).isEqualByComparingTo(BigDecimal.valueOf(25));
    }

    @Test
    void jobEntirelyBeforeThePeriodGetsNoWeightFromIt() {
        // Kills the "replaced return value with null" mutant on line 101 by exercising the "no
        // overlap at all" fallback, which no existing test ever reaches.
        ZonedDateTime jobStart = CI_START.minus(RESOLUTION).minus(RESOLUTION);
        ZonedDateTime jobEnd = CI_START.minus(RESOLUTION);

        BigDecimal result = Timeslot.calculateCarbonIntensity(onePeriod(), jobStart, jobEnd);

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
