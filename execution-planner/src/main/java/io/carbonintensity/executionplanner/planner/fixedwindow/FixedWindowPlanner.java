package io.carbonintensity.executionplanner.planner.fixedwindow;

import java.time.Duration;
import java.time.ZonedDateTime;

import io.carbonintensity.executionplanner.runtime.impl.CarbonIntensityDataFetcher;
import io.carbonintensity.executionplanner.runtime.impl.ZonedCarbonIntensityPeriod;
import io.carbonintensity.executionplanner.spi.CarbonIntensityPlanner;
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
 * @see CarbonIntensityPlanner
 * @see FixedWindowPlanningConstraints
 * @see SingleJobStrategy
 * @see CarbonIntensityDataFetcher
 * @see ZonedCarbonIntensityPeriod
 */
public class FixedWindowPlanner implements CarbonIntensityPlanner<FixedWindowPlanningConstraints> {

    private final CarbonIntensityDataFetcher dataFetcher;

    public FixedWindowPlanner(CarbonIntensityDataFetcher dataFetcher) {
        this.dataFetcher = dataFetcher;
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
        return strategy.bestTimeslot(constraints.getStart(), constraints.getEnd(), constraints.getDuration(),
                carbonIntensity).start();
    }
}
