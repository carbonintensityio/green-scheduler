package io.carbonintensity.executionplanner.planner;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.carbonintensity.executionplanner.runtime.impl.CarbonIntensity;

/**
 * Direct tests of {@link Timeslot#calculateCarbonIntensity(List, ZonedDateTime, ZonedDateTime)},
 * placing a job's start/end exactly on a carbon-intensity period's boundary. {@link TestTimeslot}
 * covers precision underflow for partial overlaps that end inside a period; these cover each
 * overlap branch (job contains the period, job starts inside it, job ends inside it) and the
 * boundaries where a job touches a period without overlapping it - a data gap, not a zero
 * reading - which PIT (CIIO-336) found unprotected.
 */
class TimeslotCalculateCarbonIntensityTest {

    // One carbon-intensity period: [2026-01-01T00:00Z, 2026-01-01T01:00Z), value 100.
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
        // start == ciStart, end == ciEnd - pins the boundaries of the "job contains the period"
        // branch, which returns the period's value unprorated.
        Optional<BigDecimal> result = Timeslot.calculateCarbonIntensity(onePeriod(), CI_START,
                CI_START.plus(RESOLUTION));

        assertThat(result).hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo(CI_VALUE));
    }

    @Test
    void jobStartingExactlyAtThePeriodEndIsADataGap() {
        // start == ciEnd, so the job only touches the period's exclusive end - pins the
        // "start < ciEnd" boundary of the overlap check: no overlap means no data, not zero.
        ZonedDateTime jobStart = CI_START.plus(RESOLUTION);
        ZonedDateTime jobEnd = jobStart.plus(RESOLUTION);

        Optional<BigDecimal> result = Timeslot.calculateCarbonIntensity(onePeriod(), jobStart, jobEnd);

        assertThat(result).isEmpty();
    }

    @Test
    void jobStartingInsideThePeriodAndEndingAfterGetsAProportionalShare() {
        // start is 45 minutes into the hour-long period and ends well after it - exercises the
        // "job starts inside the period" branch with a start that is neither of its boundaries.
        ZonedDateTime jobStart = CI_START.plus(Duration.ofMinutes(45));
        ZonedDateTime jobEnd = jobStart.plus(RESOLUTION);

        Optional<BigDecimal> result = Timeslot.calculateCarbonIntensity(onePeriod(), jobStart, jobEnd);

        // 15 of the period's 60 minutes overlap -> a quarter of its value.
        assertThat(result).hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo(BigDecimal.valueOf(25)));
    }

    @Test
    void jobEndingExactlyAtThePeriodStartIsADataGap() {
        // end == ciStart, so the job only touches the period's inclusive start from outside - pins
        // the "ciStart < end" boundary of the overlap check: no overlap means no data, not zero.
        ZonedDateTime jobStart = CI_START.minus(RESOLUTION);
        ZonedDateTime jobEnd = CI_START;

        Optional<BigDecimal> result = Timeslot.calculateCarbonIntensity(onePeriod(), jobStart, jobEnd);

        assertThat(result).isEmpty();
    }

    @Test
    void jobEndingInsideThePeriodAndStartingBeforeGetsAProportionalShare() {
        // starts well before the period, ends 15 minutes into it - exercises the "job ends inside
        // the period" branch.
        ZonedDateTime jobStart = CI_START.minus(RESOLUTION);
        ZonedDateTime jobEnd = CI_START.plus(Duration.ofMinutes(15));

        Optional<BigDecimal> result = Timeslot.calculateCarbonIntensity(onePeriod(), jobStart, jobEnd);

        assertThat(result).hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo(BigDecimal.valueOf(25)));
    }

    @Test
    void jobEntirelyBeforeThePeriodIsADataGap() {
        // No overlap at all: the job ends an hour before the period starts.
        ZonedDateTime jobStart = CI_START.minus(RESOLUTION).minus(RESOLUTION);
        ZonedDateTime jobEnd = CI_START.minus(RESOLUTION);

        Optional<BigDecimal> result = Timeslot.calculateCarbonIntensity(onePeriod(), jobStart, jobEnd);

        assertThat(result).isEmpty();
    }
}
