package io.carbonintensity.executionplanner.planner.successive;

import java.time.ZonedDateTime;

import io.carbonintensity.executionplanner.runtime.impl.CarbonIntensity;
import io.carbonintensity.executionplanner.runtime.impl.CarbonIntensityDataFetcher;
import io.carbonintensity.executionplanner.runtime.impl.ZonedCarbonIntensityPeriod;
import io.carbonintensity.executionplanner.spi.CarbonIntensityPlanner;
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
 * @see CarbonIntensityPlanner
 * @see SuccessivePlanningConstraints
 * @see SingleJobStrategy
 * @see CarbonIntensityDataFetcher
 * @see ZonedCarbonIntensityPeriod
 */
public class SuccessivePlanner implements CarbonIntensityPlanner<SuccessivePlanningConstraints> {

    private final CarbonIntensityDataFetcher dataFetcher;

    public SuccessivePlanner(CarbonIntensityDataFetcher dataFetcher) {
        this.dataFetcher = dataFetcher;
    }

    @Override
    public boolean canSchedule(SuccessivePlanningConstraints constraints) {
        return constraints != null;
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

        ZonedDateTime dayStart = constraints.getLastExecutionTime() != null ? constraints.getLastExecutionTime() : ws;
        var zonedPeriod = new ZonedCarbonIntensityPeriod.Builder()
                .withStartTime(dayStart)
                .withEndTime(dayStart.plusDays(1))
                .withCarbonIntensityZone(constraints.getCarbonIntensityZone())
                .build();
        CarbonIntensity carbonIntensity = dataFetcher.fetchCarbonIntensity(zonedPeriod);

        SingleJobStrategy initialStrategy = new SingleJobStrategy();
        return initialStrategy.bestTimeslot(ws, we, constraints.getDuration(), carbonIntensity).start();
    }

}
