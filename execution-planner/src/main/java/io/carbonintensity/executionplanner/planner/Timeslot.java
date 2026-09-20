package io.carbonintensity.executionplanner.planner;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.carbonintensity.executionplanner.runtime.impl.CarbonIntensity;

/**
 * A possible timeslot for a job to run, its duration should be equal to the job's duration.
 * Will probably overlap multiple CarbonIntensityPeriod instances.
 */
public class Timeslot {
    ZonedDateTime start;
    ZonedDateTime end;
    /**
     * {@code null} when no {@link CarbonIntensityPeriod} genuinely
     * overlapped this slot - a data gap, not a zero reading. See
     * {@link CarbonIntensityPeriod#overlaps} for why that distinction
     * matters.
     */
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

    /**
     * @return the carbon-intensity value for this slot, or {@code null}
     *         for a data gap - see {@link #carbonIntensity the field
     *         javadoc}
     */
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
            timeslots.add(new Timeslot(s, e, calculateCarbonIntensity(periods, s, e).orElse(null)));
            s = s.plus(resolution);
        }
        return timeslots;
    }

    /**
     * @param carbonIntensityInstants candidate periods to search
     * @param start start of the candidate slot, inclusive
     * @param end end of the candidate slot, exclusive
     * @return summed contribution of every genuinely overlapping period,
     *         or {@link Optional#empty()} for a real data gap
     */
    public static Optional<BigDecimal> calculateCarbonIntensity(List<CarbonIntensityPeriod> carbonIntensityInstants,
            ZonedDateTime start, ZonedDateTime end) {
        Instant startInstant = start.toInstant();
        Instant endInstant = end.toInstant();
        return carbonIntensityInstants.stream()
                .filter(ci -> ci.overlaps(startInstant, endInstant))
                .map(ci -> calculateCarbonIntensity(start, end, ci))
                .reduce(BigDecimal::add);
    }

    /**
     * Precision used for the partial-period division in {@link #prorate}.
     * Deliberately generous (16 significant digits) rather than the
     * dividend's own scale: see {@link #prorate}'s Javadoc for why the
     * naive 2-arg {@code divide(divisor, RoundingMode)} silently
     * fabricates a zero here.
     */
    private static final MathContext PRORATION_PRECISION = MathContext.DECIMAL64;

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
            return prorate(ci, secsInCiPeriod);
        }
        //job ends in or on ci window, but does not start in it
        if (end.toInstant().compareTo(ciStart) >= 0 && end.toInstant().compareTo(ciEnd) <= 0) {
            long secsInCiPeriod = Duration.between(ciStart, end.toInstant()).getSeconds();
            return prorate(ci, secsInCiPeriod);
        }

        // Unreachable: the caller only invokes this method for a ci that
        // CarbonIntensityPeriod#overlaps already confirmed genuinely
        // overlaps [start, end) (start < ciEnd && ciStart < end), and the
        // three branches above are exhaustive for every such overlap
        // (candidate contains ci, candidate starts inside ci, or candidate
        // ends inside ci). A silent BigDecimal.ZERO here would be exactly
        // the kind of fabricated-zero bug this class exists to prevent -
        // fail loudly instead.
        throw new IllegalStateException(
                "Unreachable: CarbonIntensityPeriod.overlaps() reported an overlap that calculateCarbonIntensity "
                        + "could not classify - candidate=[" + start + "," + end + "), period=[" + ciStart + "," + ciEnd + ")");
    }

    /**
     * @param ci the period being prorated
     * @param coveredSeconds seconds of {@code ci}'s own resolution that
     *        the candidate slot actually covers
     * @return {@code ci}'s value scaled to that covered fraction. Multiplies
     *         before dividing - see {@link #PRORATION_PRECISION}'s Javadoc.
     */
    private static BigDecimal prorate(CarbonIntensityPeriod ci, long coveredSeconds) {
        return ci.value().multiply(BigDecimal.valueOf(coveredSeconds))
                .divide(BigDecimal.valueOf(ci.resolution().getSeconds()), PRORATION_PRECISION);
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
