package io.carbonintensity.executionplanner.planner;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import io.carbonintensity.executionplanner.runtime.impl.CarbonIntensity;

/**
 * A possible timeslot for a job to run, its duration should be equal to the job's duration.
 * Will probably overlap multiple CarbonIntensityPeriod instances.
 */
public class Timeslot {
    ZonedDateTime start;
    ZonedDateTime end;
    BigDecimal carbonIntensity;

    public Timeslot(ZonedDateTime start, ZonedDateTime end, BigDecimal carbonIntensity) {
        this.start = start;
        this.end = end;
        this.carbonIntensity = carbonIntensity;
    }

    public ZonedDateTime start() {
        return start;
    }

    public ZonedDateTime end() {
        return end;
    }

    public BigDecimal carbonIntensity() {
        return carbonIntensity;
    }

    /**
     * Generate a list of timeslots for a given period of time.
     * Currently for each starting second, a timeslot is generated.
     *
     * @param ws the start of the window to start the job in
     * @param we the end of the window to start the job in
     * @param timeslotDuration the duration of each timeslot
     * @param resolution the resolution of generating timeslots (e.g. a timeslot every minute)
     * @param carbonIntensity the carbon intensity data
     * @return a list of timeslots
     */
    public static List<Timeslot> getTimeslots(ZonedDateTime ws, ZonedDateTime we, Duration timeslotDuration,
            Duration resolution, CarbonIntensity carbonIntensity) {
        List<CarbonIntensityPeriod> periods = CarbonIntensityPeriod.of(carbonIntensity);

        List<Timeslot> timeslots = new ArrayList<>();
        ZonedDateTime s = ws;

        while (!s.isAfter(we)) { // allow equal for 0 windows
            ZonedDateTime e = s.plus(timeslotDuration);
            timeslots.add(new Timeslot(s, e, calculateCarbonIntensity(periods, s, e)));
            s = s.plus(resolution);
        }
        return timeslots;
    }

    public static BigDecimal calculateCarbonIntensity(List<CarbonIntensityPeriod> carbonIntensityInstants, ZonedDateTime start,
            ZonedDateTime end) {
        // find carbon intensities.
        return carbonIntensityInstants.stream()
                .filter(m -> m.contains(start.toInstant()) || m.contains(end.toInstant()))
                .map(ci -> calculateCarbonIntensity(start, end, ci))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal calculateCarbonIntensity(ZonedDateTime start, ZonedDateTime end, CarbonIntensityPeriod ci) {
        Instant ciStart = ci.moment();
        Instant ciEnd = ciStart.plus(ci.resolution());
        if (start.toInstant().compareTo(ciStart) <= 0
                && end.toInstant().compareTo(ciEnd) >= 0) {
            return ci.value();
        }
        if (start.toInstant().compareTo(ciStart) >= 0 && start.toInstant().compareTo(ciEnd) <= 0) {
            long secsInCiPeriod;
            // job start in or on ci window
            if (end.toInstant().compareTo(ciEnd) <= 0) {
                // job ends in ci window
                secsInCiPeriod = Duration.between(start, end).getSeconds();
            } else {
                secsInCiPeriod = Duration.between(start.toInstant(), ciEnd).getSeconds();
            }
            // Multiply before dividing, and with an explicit scale (CIIO-341): dividing first with
            // no explicit scale rounds to the *dividend's* scale (0 for a typical integer-valued
            // reading), silently truncating any per-second rate below 1 to zero before the
            // multiply ever runs - which a partial overlap almost always is.
            return ci.value().multiply(BigDecimal.valueOf(secsInCiPeriod))
                    .divide(BigDecimal.valueOf(ci.resolution().getSeconds()), 10, RoundingMode.HALF_EVEN);
        }
        //job ends in or on ci window, but does not start in it
        if (end.toInstant().compareTo(ciStart) >= 0 && end.toInstant().compareTo(ciEnd) <= 0) {
            long secsInCiPeriod = Duration.between(ciStart, end.toInstant()).getSeconds();
            // Multiply before dividing, and with an explicit scale (CIIO-341): dividing first with
            // no explicit scale rounds to the *dividend's* scale (0 for a typical integer-valued
            // reading), silently truncating any per-second rate below 1 to zero before the
            // multiply ever runs - which a partial overlap almost always is.
            return ci.value().multiply(BigDecimal.valueOf(secsInCiPeriod))
                    .divide(BigDecimal.valueOf(ci.resolution().getSeconds()), 10, RoundingMode.HALF_EVEN);
        }

        return BigDecimal.ZERO;
    }

    @Override
    public String toString() {
        return "Timeslot{" +
                "start=" + start +
                ", end=" + end +
                ", carbonIntensity=" + carbonIntensity +
                '}';
    }
}
