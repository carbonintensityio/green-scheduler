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
     * {@code null} when no {@link CarbonIntensityPeriod} genuinely overlapped this slot - a data gap, not a
     * zero reading. See {@link CarbonIntensityPeriod#overlaps} for why that distinction matters (CIIO-475).
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
     * @return the carbon-intensity value for this slot, or {@code null} for a data gap - see
     *         {@link #carbonIntensity the field javadoc}
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
     * @return the summed carbon-intensity contribution of every {@link CarbonIntensityPeriod} genuinely
     *         overlapping {@code [start, end)}, or {@link Optional#empty()} if none did - distinguishing a
     *         real data gap from an actual zero, which {@link BigDecimal#ZERO} as a reduce identity could not
     *         (CIIO-475). Periods are selected via {@link CarbonIntensityPeriod#overlaps} - see its Javadoc
     *         for why that isn't just {@code contains(start) || contains(end)}.
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
     * Precision used for the partial-period division in {@link #prorate}. Deliberately generous (16
     * significant digits) rather than the dividend's own scale: see {@link #prorate}'s Javadoc for why the
     * naive 2-arg {@code divide(divisor, RoundingMode)} silently fabricates a zero here (CIIO-475).
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

        // Unreachable: the caller only invokes this method for a ci that CarbonIntensityPeriod#overlaps
        // already confirmed genuinely overlaps [start, end) (start < ciEnd && ciStart < end), and the three
        // branches above are exhaustive for every such overlap (candidate contains ci, candidate starts
        // inside ci, or candidate ends inside ci). A silent BigDecimal.ZERO here would be exactly the kind
        // of fabricated-zero bug this class exists to prevent (CIIO-475) - fail loudly instead.
        throw new IllegalStateException(
                "Unreachable: CarbonIntensityPeriod.overlaps() reported an overlap that calculateCarbonIntensity "
                        + "could not classify - candidate=[" + start + "," + end + "), period=[" + ciStart + "," + ciEnd + ")");
    }

    /**
     * @return {@code ci}'s value scaled down to the fraction of its own resolution covered by
     *         {@code coveredSeconds}, and back up by that same fraction - i.e. its contribution to a
     *         candidate that only partially overlaps it.
     *         <p>
     *         Multiplies before dividing (rather than the mathematically equivalent divide-then-multiply) so
     *         there is only ever one rounding step, at {@link #PRORATION_PRECISION} rather than the 2-arg
     *         {@code divide(divisor, RoundingMode)}'s dividend-scale rounding that used to fabricate a zero
     *         here - see that constant's Javadoc (CIIO-475).
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
