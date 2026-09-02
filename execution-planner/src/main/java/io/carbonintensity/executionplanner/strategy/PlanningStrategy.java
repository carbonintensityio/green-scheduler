package io.carbonintensity.executionplanner.strategy;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;

import io.carbonintensity.executionplanner.planner.Timeslot;
import io.carbonintensity.executionplanner.runtime.impl.CarbonIntensity;

public interface PlanningStrategy {
    Timeslot bestTimeslot(ZonedDateTime ws, ZonedDateTime we, Duration duration, CarbonIntensity carbonIntensity);

    /**
     * Returns all viable timeslots in the given window, ranked from lowest (best) to highest carbon intensity.
     * Used to find an alternative slot when the single best slot is already claimed by another job.
     * <p>
     * The default implementation only returns the single best slot computed by
     * {@link #bestTimeslot(ZonedDateTime, ZonedDateTime, Duration, CarbonIntensity)}.
     */
    default List<Timeslot> rankedTimeslots(ZonedDateTime ws, ZonedDateTime we, Duration duration,
            CarbonIntensity carbonIntensity) {
        Timeslot best = bestTimeslot(ws, we, duration, carbonIntensity);
        return best == null ? List.of() : List.of(best);
    }
}
