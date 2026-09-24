package io.carbonintensity.executionplanner.planner.successive;

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
 * A {@link CarbonIntensityPlanner} implementation that schedules tasks based on successive
 * planning constraints and carbon intensity data.
 *
 * <p>
 * The {@code SuccessivePlanner} calculates the best execution time for tasks that need to be scheduled
 * successively, considering a gap between executions and ensuring that tasks are scheduled at the optimal
 * carbon intensity levels. It retrieves carbon intensity data from the {@link CarbonIntensityDataFetcher}
 * and uses the {@link SingleJobStrategy} to find the best time slot within the given constraints.
 * </p>
 *
 * <p>
 * When a {@link ConcurrencySlotTracker} and a positive {@code maxConcurrentPerSlot} are supplied, jobs
 * that would otherwise all land on the same slot within the same zone are spread across the next-best
 * slots instead, up to that limit per slot. The gap window is always honored: if no slot within it
 * satisfies the limit, the job still runs at the greenest slot in the window regardless of the limit.
 * </p>
 *
 * @see CarbonIntensityPlanner
 * @see SuccessivePlanningConstraints
 * @see SingleJobStrategy
 * @see CarbonIntensityDataFetcher
 * @see ZonedCarbonIntensityPeriod
 */
public class SuccessivePlanner implements CarbonIntensityPlanner<SuccessivePlanningConstraints> {

    private static final Logger log = LoggerFactory.getLogger(SuccessivePlanner.class);

    private final CarbonIntensityDataFetcher dataFetcher;
    private final ConcurrencySlotTracker slotTracker;
    private final int maxConcurrentPerSlot;

    public SuccessivePlanner(CarbonIntensityDataFetcher dataFetcher) {
        this(dataFetcher, null, 0);
    }

    /**
     * @param slotTracker shared tracker used to spread jobs across multiple green moments when several
     *        compete for the same slot within the same zone, or {@code null} to disable spreading
     * @param maxConcurrentPerSlot maximum number of jobs allowed to start at the exact same slot within a
     *        zone; ignored when {@code slotTracker} is {@code null}. A value {@code <= 0} disables spreading.
     */
    public SuccessivePlanner(CarbonIntensityDataFetcher dataFetcher, ConcurrencySlotTracker slotTracker,
            int maxConcurrentPerSlot) {
        this.dataFetcher = dataFetcher;
        this.slotTracker = slotTracker;
        this.maxConcurrentPerSlot = maxConcurrentPerSlot;
    }

    /**
     * @return {@code false} when {@code constraints} is {@code null}, or when no
     *         genuine carbon-intensity data (live or a still-fresh last-known
     *         value) is available for the next candidate day - in which case the
     *         caller falls back to its always-available fallback route (plain
     *         interval between the configured min/max gap), instead of this
     *         planner returning a fabricated "greenest" slot.
     */
    @Override
    public boolean canSchedule(SuccessivePlanningConstraints constraints) {
        return constraints != null
                && dataFetcher.fetchCarbonIntensity(buildDayPeriod(constraints)).hasData();
    }

    /**
     * The carbon-intensity period covering the day the next candidate slot falls
     * in: starting at the last execution (or the initial start time, before the
     * first run) and spanning 24 hours.
     *
     * @param constraints the planning constraints to derive the day period from
     * @return the 24-hour carbon-intensity period covering the next candidate day
     */
    private static ZonedCarbonIntensityPeriod buildDayPeriod(SuccessivePlanningConstraints constraints) {
        ZonedDateTime dayStart = constraints.getLastExecutionTime() != null ? constraints.getLastExecutionTime()
                : constraints.getInitialStartTime();
        return new ZonedCarbonIntensityPeriod.Builder()
                .withStartTime(dayStart)
                .withEndTime(dayStart.plusDays(1))
                .withCarbonIntensityZone(constraints.getCarbonIntensityZone())
                .build();
    }

    @Override
    public ZonedDateTime getNextExecutionTime(SuccessivePlanningConstraints constraints) {
        ZonedDateTime ws;
        ZonedDateTime we;

        // first time execution
        if (constraints.getLastExecutionTime() == null) {
            ws = constraints.getInitialStartTime();
            we = ws.plus(constraints.getInitialMaximumDelay());
        } else {
            ws = constraints.getLastExecutionTime().plus(constraints.getMinimumGap());
            we = constraints.getLastExecutionTime().plus(constraints.getMaximumGap());
        }

        CarbonIntensity carbonIntensity = dataFetcher.fetchCarbonIntensity(buildDayPeriod(constraints));

        SingleJobStrategy strategy = new SingleJobStrategy();

        if (slotTracker == null || maxConcurrentPerSlot <= 0) {
            Timeslot best = strategy.bestTimeslot(ws, we, constraints.getDuration(), carbonIntensity);
            return best == null ? null : best.start();
        }

        return pickTimeslot(strategy, constraints, ws, we, carbonIntensity);
    }

    private ZonedDateTime pickTimeslot(SingleJobStrategy strategy, SuccessivePlanningConstraints constraints,
            ZonedDateTime ws, ZonedDateTime we, CarbonIntensity carbonIntensity) {
        List<Timeslot> ranked = strategy.rankedTimeslots(ws, we, constraints.getDuration(), carbonIntensity);
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

        // The gap window is a hard promise to the user and always wins: if no slot inside it still
        // satisfies the concurrency limit, run anyway at the greenest slot instead of not running at all.
        Timeslot best = ranked.get(0);
        log.warn(
                "Concurrency limit of {} per slot exceeded for zone {} at {} - scheduling '{}' anyway to honor its gap window",
                maxConcurrentPerSlot, zone, best.start(), identity);
        slotTracker.reserve(zone, identity, best.start().toInstant());
        return best.start();
    }

}
