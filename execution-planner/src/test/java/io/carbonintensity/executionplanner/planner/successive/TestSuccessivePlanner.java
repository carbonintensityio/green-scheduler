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

@ExtendWith(MockitoExtension.class)
class TestSuccessivePlanner {

    SuccessivePlanner defaultCarbonIntensityScheduler;
    CarbonIntensityDataFetcher carbonIntensityDataFetcher;

    @BeforeEach
    public void setup() {
        carbonIntensityDataFetcher = mock(CarbonIntensityDataFetcher.class);
        defaultCarbonIntensityScheduler = new SuccessivePlanner(carbonIntensityDataFetcher);
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
}
