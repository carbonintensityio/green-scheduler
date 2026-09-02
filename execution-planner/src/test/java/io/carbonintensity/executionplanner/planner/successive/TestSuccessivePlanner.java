package io.carbonintensity.executionplanner.planner.successive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import io.carbonintensity.executionplanner.runtime.impl.CarbonIntensityDataFetcher;
import io.carbonintensity.executionplanner.runtime.impl.rest.CarbonIntensityJsonParser;
import io.carbonintensity.executionplanner.spi.CarbonIntensityPlanner;
import io.carbonintensity.executionplanner.spi.ConcurrencySlotTracker;

@ExtendWith(MockitoExtension.class)
class TestSuccessivePlanner {

    SuccessivePlanner defaultCarbonIntensityScheduler;
    CarbonIntensityDataFetcher carbonIntensityDataFetcher;

    @BeforeEach
    public void setup() {
        carbonIntensityDataFetcher = mock(CarbonIntensityDataFetcher.class);
        defaultCarbonIntensityScheduler = new SuccessivePlanner(carbonIntensityDataFetcher);
    }

    private static SuccessivePlanningConstraints constraintsFor(String identity, ZonedDateTime lastExecutionTime,
            Duration minimumGap, Duration maximumGap) {
        return DefaultSuccessivePlanningConstraints.builder()
                .withIdentity(identity)
                .withLastExecutionTime(lastExecutionTime)
                .withMinimumGap(minimumGap)
                .withMaximumGap(maximumGap)
                .withDuration(Duration.ofMinutes(30))
                .withCarbonIntensityZone("NL")
                .build();
    }

    @Test
    void shouldScheduleWithInitialDelay() {
        final var parser = new CarbonIntensityJsonParser();
        final var carbonIntensity = parser.parse(ClassLoader.getSystemResourceAsStream("day-ahead-20240824-Z.json"));

        when(carbonIntensityDataFetcher.fetchCarbonIntensity(any()))
                .thenReturn(carbonIntensity);

        Duration initialWarmup = Duration.ofSeconds(60);
        ZonedDateTime now = ZonedDateTime.now();
        final var constraints = DefaultSuccessivePlanningConstraints.builder()
                .withIdentity("foo")
                .withInitialStartTime(now)
                .withInitialMaximumDelay(initialWarmup)
                .withDuration(Duration.ofMinutes(5))
                .withMinimumGap(Duration.ofMinutes(5))
                .withMaximumGap(Duration.ofDays(10))
                .withCarbonIntensityZone("NL")
                .build();

        ZonedDateTime nextExecutionTime = defaultCarbonIntensityScheduler.getNextExecutionTime(constraints);

        assertThat(nextExecutionTime).isNotNull();
        assertThat(nextExecutionTime.isBefore(now.plus(initialWarmup))).isTrue();
    }

    @Test
    void shouldScheduleFromPreviousExecution() {
        CarbonIntensityJsonParser parser = new CarbonIntensityJsonParser();
        final var carbonIntensity = parser.parse(
                ClassLoader.getSystemResourceAsStream("day-ahead-20240824-Z.json"));

        when(carbonIntensityDataFetcher.fetchCarbonIntensity(any()))
                .thenReturn(carbonIntensity);

        ZonedDateTime lastExecutionTime = ZonedDateTime.now();
        Duration minGap = Duration.ofMinutes(90);
        Duration maxGap = Duration.ofMinutes(120);
        final var constraints = DefaultSuccessivePlanningConstraints.builder()
                .withIdentity("foo")
                .withInitialMaximumDelay(Duration.ofSeconds(0))
                .withMinimumGap(minGap)
                .withMaximumGap(maxGap)
                .withDuration(Duration.ofMinutes(5))
                .withLastExecutionTime(lastExecutionTime)
                .withCarbonIntensityZone("NL")
                .build();

        ZonedDateTime nextExecutionTime = defaultCarbonIntensityScheduler.getNextExecutionTime(constraints);
        assertThat(nextExecutionTime).isNotNull();

        // note: we allow the execution on the exact minGap, so should not be before (but equal or after)
        assertThat(nextExecutionTime.isBefore(lastExecutionTime.plus(minGap))).isFalse();
        assertThat(nextExecutionTime.isAfter(lastExecutionTime.plus(maxGap))).isFalse();
    }

    @Test
    void whenTwoJobsCompeteForTheSameSlot_thenTheSecondJobIsSpreadToTheNextBestSlot() {
        CarbonIntensityDataFetcher sharedFetcher = mock(CarbonIntensityDataFetcher.class);
        CarbonIntensityJsonParser parser = new CarbonIntensityJsonParser();
        var carbonIntensity = parser.parse(ClassLoader.getSystemResourceAsStream("day-ahead-20240824-Z.json"));
        when(sharedFetcher.fetchCarbonIntensity(any())).thenReturn(carbonIntensity);

        ConcurrencySlotTracker tracker = new ConcurrencySlotTracker();
        CarbonIntensityPlanner<SuccessivePlanningConstraints> plannerA = new SuccessivePlanner(sharedFetcher, tracker, 1);
        CarbonIntensityPlanner<SuccessivePlanningConstraints> plannerB = new SuccessivePlanner(sharedFetcher, tracker, 1);

        ZonedDateTime lastExecutionTime = ZonedDateTime.parse("2024-08-27T00:00:00Z");
        Duration minGap = Duration.ZERO;
        Duration maxGap = Duration.ofHours(6);

        ZonedDateTime timeA = plannerA.getNextExecutionTime(constraintsFor("job-a", lastExecutionTime, minGap, maxGap));
        ZonedDateTime timeB = plannerB.getNextExecutionTime(constraintsFor("job-b", lastExecutionTime, minGap, maxGap));

        assertThat(timeA).isNotNull();
        assertThat(timeB).isNotNull();
        assertThat(timeB).isNotEqualTo(timeA);
    }

    /**
     * #234's design decision 5 claims that, unlike {@code FixedWindowPlanner}, "{@code SuccessivePlanner} has no
     * such conflict since it can simply look further ahead" when no slot within the gap window satisfies the
     * concurrency limit. That is not what the current implementation does: {@code SuccessivePlanner.pickTimeslot}
     * ranks candidates via {@code strategy.rankedTimeslots(ws, we, ...)}, strictly bounded by the gap window
     * {@code [ws, we]} (up to the maximum gap), exactly like {@code FixedWindowPlanner} is bounded by its fixed
     * window. It never considers slots beyond {@code we}, so when the gap window is too narrow to offer an
     * alternative slot, it falls back to the exact same "violate the limit, run at the greenest slot anyway"
     * behavior as {@code FixedWindowPlanner} - it does not "look further ahead". This test documents that the two
     * planners behave identically in this edge case, contradicting the "no such conflict" claim in #234.
     */
    @Test
    void whenNoSlotSatisfiesTheLimit_thenTheGapWindowStillWinsAndJobRunsAnyway() {
        CarbonIntensityDataFetcher sharedFetcher = mock(CarbonIntensityDataFetcher.class);
        CarbonIntensityJsonParser parser = new CarbonIntensityJsonParser();
        var carbonIntensity = parser.parse(ClassLoader.getSystemResourceAsStream("day-ahead-20240824-Z.json"));
        when(sharedFetcher.fetchCarbonIntensity(any())).thenReturn(carbonIntensity);

        ConcurrencySlotTracker tracker = new ConcurrencySlotTracker();
        // minGap == maxGap collapses the gap window to a single instant, so exactly one candidate slot
        // exists (see Timeslot.getTimeslots: candidates are generated for every resolution step in
        // [ws, we], inclusive of we) - no alternative slot can ever exist
        ZonedDateTime lastExecutionTime = ZonedDateTime.parse("2024-08-27T00:00:00Z");
        Duration minGap = Duration.ofMinutes(30);
        Duration maxGap = Duration.ofMinutes(30);
        int maxConcurrentPerSlot = 1;

        CarbonIntensityPlanner<SuccessivePlanningConstraints> plannerA = new SuccessivePlanner(sharedFetcher, tracker,
                maxConcurrentPerSlot);
        CarbonIntensityPlanner<SuccessivePlanningConstraints> plannerB = new SuccessivePlanner(sharedFetcher, tracker,
                maxConcurrentPerSlot);

        ZonedDateTime timeA = plannerA.getNextExecutionTime(constraintsFor("job-a", lastExecutionTime, minGap, maxGap));
        ZonedDateTime timeB = plannerB.getNextExecutionTime(constraintsFor("job-b", lastExecutionTime, minGap, maxGap));

        assertThat(timeA).isNotNull();
        assertThat(timeB).isNotNull();
        // no room to spread within this gap window: both jobs still run, at the same greenest slot
        assertThat(timeB).isEqualTo(timeA);
    }
}
