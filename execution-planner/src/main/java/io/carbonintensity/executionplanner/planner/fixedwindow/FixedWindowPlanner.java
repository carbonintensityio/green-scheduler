package io.carbonintensity.executionplanner.planner.fixedwindow;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.carbonintensity.executionplanner.planner.Timeslot;
import io.carbonintensity.executionplanner.runtime.impl.CarbonIntensity;
import io.carbonintensity.executionplanner.runtime.impl.CarbonIntensityDataFetcher;
import io.carbonintensity.executionplanner.runtime.impl.ZonedCarbonIntensityPeriod;
import io.carbonintensity.executionplanner.spi.CarbonIntensityPlanner;
import io.carbonintensity.executionplanner.spi.ConcurrencySlotTracker;
import io.carbonintensity.executionplanner.strategy.SingleJobStrategy;

/**
 * A {@link CarbonIntensityPlanner} implementation that determines the best execution time
 * for a task within a fixed time window, based on the carbon intensity data fetched from
 * the {@link CarbonIntensityDataFetcher}.
 *
 * <p>
 * The {@code FixedWindowPlanner} uses a {@link SingleJobStrategy} to identify the best
 * time slot within a specified window that minimizes the carbon intensity impact. It fetches
 * the relevant carbon intensity data for the given window and determines the optimal time
 * for execution based on the constraints provided.
 * </p>
 *
 * <p>
 * When a {@link ConcurrencySlotTracker} and a positive {@code maxConcurrentPerSlot} are supplied, jobs
 * that would otherwise all land on the same slot within the same zone are spread across the next-best
 * slots instead, up to that limit per slot. The fixed window is always honored: if no slot within it
 * satisfies the limit, the job still runs at the greenest slot in the window regardless of the limit.
 * </p>
 *
 * @see CarbonIntensityPlanner
 * @see FixedWindowPlanningConstraints
 * @see SingleJobStrategy
 * @see CarbonIntensityDataFetcher
 * @see ZonedCarbonIntensityPeriod
 */
public class FixedWindowPlanner implements CarbonIntensityPlanner<FixedWindowPlanningConstraints> {

    private static final Logger log = LoggerFactory.getLogger(FixedWindowPlanner.class);

    private final CarbonIntensityDataFetcher dataFetcher;
    private final ConcurrencySlotTracker slotTracker;
    private final int maxConcurrentPerSlot;

    public FixedWindowPlanner(CarbonIntensityDataFetcher dataFetcher) {
        this(dataFetcher, null, 0);
    }

    /**
     * @param slotTracker shared tracker used to spread jobs across multiple green moments when several
     *        compete for the same slot within the same zone, or {@code null} to disable spreading
     * @param maxConcurrentPerSlot maximum number of jobs allowed to start at the exact same slot within a
     *        zone; ignored when {@code slotTracker} is {@code null}. A value {@code <= 0} disables spreading.
     */
    public FixedWindowPlanner(CarbonIntensityDataFetcher dataFetcher, ConcurrencySlotTracker slotTracker,
            int maxConcurrentPerSlot) {
        this.dataFetcher = dataFetcher;
        this.slotTracker = slotTracker;
        this.maxConcurrentPerSlot = maxConcurrentPerSlot;
    }

    @Override
    public boolean canSchedule(FixedWindowPlanningConstraints constraints) {
        return constraints != null;
    }

    @Override
    public ZonedDateTime getNextExecutionTime(FixedWindowPlanningConstraints constraints) {

        final var period = new ZonedCarbonIntensityPeriod.Builder()
                .withStartTime(constraints.getStart())
                .withEndTime(constraints.getEnd())
                .withCarbonIntensityZone(constraints.getCarbonIntensityZone())
                .build();
        final var carbonIntensity = dataFetcher.fetchCarbonIntensity(period);

        final var strategy = new SingleJobStrategy(Duration.ofHours(1));

        if (slotTracker == null || maxConcurrentPerSlot <= 0) {
            Timeslot best = strategy.bestTimeslot(constraints.getStart(), constraints.getEnd(), constraints.getDuration(),
                    carbonIntensity);
            return best == null ? null : best.start();
        }

        return pickTimeslot(strategy, constraints, carbonIntensity);
    }

    private ZonedDateTime pickTimeslot(SingleJobStrategy strategy, FixedWindowPlanningConstraints constraints,
            CarbonIntensity carbonIntensity) {
        List<Timeslot> ranked = strategy.rankedTimeslots(constraints.getStart(), constraints.getEnd(),
                constraints.getDuration(), carbonIntensity);
        if (ranked.isEmpty()) {
            return null;
        }

        String zone = constraints.getCarbonIntensityZone();
        String identity = constraints.getIdentity();
        for (Timeslot candidate : ranked) {
            if (slotTracker.tryReserve(zone, identity, candidate.start().toInstant(), maxConcurrentPerSlot)) {
                return candidate.start();
            }
        }

        // The fixed window is a hard promise to the user and always wins: if no slot inside it still
        // satisfies the concurrency limit, run anyway at the greenest slot instead of not running at all.
        Timeslot best = ranked.get(0);
        log.warn(
                "Concurrency limit of {} per slot exceeded for zone {} at {} - scheduling '{}' anyway to honor its fixed window",
                maxConcurrentPerSlot, zone, best.start(), identity);
        slotTracker.reserve(zone, identity, best.start().toInstant());
        return best.start();
    }
}
