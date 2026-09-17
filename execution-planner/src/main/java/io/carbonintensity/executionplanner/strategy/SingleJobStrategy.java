package io.carbonintensity.executionplanner.strategy;

import static io.carbonintensity.executionplanner.planner.Timeslot.getTimeslots;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.carbonintensity.executionplanner.planner.Timeslot;
import io.carbonintensity.executionplanner.runtime.impl.CarbonIntensity;

/**
 * Places a single job in the best spot in the given window.
 * Does not consider the next job's placement.
 */
public class SingleJobStrategy implements PlanningStrategy {

    private static final Logger log = LoggerFactory.getLogger(SingleJobStrategy.class);

    private final Duration resolution;

    /**
     * Default constructor, uses a resolution of 30 minutes to find timeslots
     */
    public SingleJobStrategy() {
        this(Duration.ofMinutes(30));
    }

    public SingleJobStrategy(Duration resolution) {
        this.resolution = resolution;
    }

    @Override
    public Timeslot bestTimeslot(ZonedDateTime ws, ZonedDateTime we, Duration duration, CarbonIntensity carbonIntensity) {
        List<Timeslot> ranked = rankedTimeslots(ws, we, duration, carbonIntensity);
        if (ranked.isEmpty()) {
            log.warn("No timeslots found!  {}", carbonIntensity.getData().size());
            return null;
        }

        Timeslot best = ranked.get(0);
        if (best.carbonIntensity() == null) {
            // Every candidate in the window was a data gap (CIIO-475): still returned, since a fixed/gap
            // window is a hard promise the caller has to honor either way, but flagged here so it's visible
            // in logs that no real carbon-intensity data actually backed this pick.
            log.warn("No real carbon-intensity data available for {} job between {} - {}; returning {} anyway "
                    + "as the best available slot", duration, ws, we, best.start());
        } else {
            log.debug("Found best timeslot of {} job between {} - {} at {} (CI: {})", duration, ws, we, best.start(),
                    best.carbonIntensity());
        }
        return best;
    }

    @Override
    public List<Timeslot> rankedTimeslots(ZonedDateTime ws, ZonedDateTime we, Duration duration,
            CarbonIntensity carbonIntensity) {
        // create timeslots and calculate carbon intensity
        List<Timeslot> timeslots = new ArrayList<>(getTimeslots(ws, we, duration, resolution, carbonIntensity));
        // Stable sort: on equal carbon intensity, the chronologically first slot stays first. A null
        // carbonIntensity (a genuine data gap, see Timeslot#carbonIntensity) sorts last - a slot with no real
        // data can never be "the greenest", and CIIO-475 was exactly this: a data gap silently outranking real,
        // non-zero readings because it was miscomputed as an actual zero.
        timeslots.sort(Comparator.comparing(Timeslot::carbonIntensity, Comparator.nullsLast(Comparator.naturalOrder())));
        return timeslots;
    }

}
