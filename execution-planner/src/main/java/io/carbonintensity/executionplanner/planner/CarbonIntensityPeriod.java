package io.carbonintensity.executionplanner.planner;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import io.carbonintensity.executionplanner.runtime.impl.CarbonIntensity;

/**
 * Represents a period in time with a carbon intensity value.
 */
public class CarbonIntensityPeriod implements Comparable<CarbonIntensityPeriod> {

    Instant instant;
    Duration resolution;
    BigDecimal value;

    CarbonIntensityPeriod(Instant moment, Duration resolution, BigDecimal value) {
        this.instant = moment;
        this.resolution = resolution;
        this.value = value;
    }

    /**
     * Converts the carbon intensity data (which is a simple array) to a list of moments.
     *
     * @param carbonIntensity the API output
     * @return a list of Instants.
     */
    public static List<CarbonIntensityPeriod> of(CarbonIntensity carbonIntensity) {
        return IntStream.range(0, carbonIntensity.getData().size())
                .mapToObj(i -> {
                    var moment = carbonIntensity.getStart().plusMillis(i * carbonIntensity.getResolution().toMillis());
                    var value = carbonIntensity.getData().get(i);
                    return new CarbonIntensityPeriod(moment, carbonIntensity.getResolution(), value);
                })
                .collect(Collectors.toList());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof CarbonIntensityPeriod))
            return false;

        CarbonIntensityPeriod that = (CarbonIntensityPeriod) o;
        return Objects.equals(moment(), that.moment()) && Objects.equals(value(), that.value())
                && Objects.equals(resolution(), that.resolution());
    }

    Duration resolution() {
        return resolution;
    }

    @Override
    public int hashCode() {
        return Objects.hash(moment(), value(), resolution());
    }

    @Override
    public int compareTo(CarbonIntensityPeriod o) {
        return Comparator.comparing(CarbonIntensityPeriod::moment)
                .thenComparing(CarbonIntensityPeriod::resolution)
                .compare(this, o);
    }

    /**
     * Whether {@code point} falls within this period, using a half-open interval
     * {@code [moment, moment + resolution)}.
     * <p>
     * The upper bound is deliberately exclusive: {@link #of(CarbonIntensity)} produces contiguous periods where
     * {@code period[i].moment() + resolution == period[i + 1].moment()}. If both bounds were inclusive, that shared
     * boundary instant would be reported as contained by two consecutive periods at once, which would double-count
     * it wherever {@code contains} is used to select overlapping periods (e.g. {@link Timeslot#calculateCarbonIntensity}).
     */
    public boolean contains(Instant point) {
        return point.compareTo(instant) >= 0 && point.isBefore(instant.plus(resolution));
    }

    @Override
    public String toString() {
        return "CarbonIntensityMoment{" +
                "moment=" + instant +
                ", value=" + value +
                ", resolution=" + resolution +
                '}';
    }

    public Instant moment() {
        return instant;
    }

    public BigDecimal value() {
        return value;
    }

}
