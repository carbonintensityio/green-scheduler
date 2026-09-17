package io.carbonintensity.executionplanner.runtime.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * See CIIO-470's addendum: the background poller's configurable, wall-clock {@code recoveryBudget} is
 * converted into an attempt count against its fixed, internal poll interval via
 * {@link CarbonIntensityDataFetcherImpl#deriveMaxAttempts(Duration, Duration)} - a pure function, tested here
 * in isolation from {@link TestCarbonIntensityDataFetcher}'s Mockito-based fixture, which this derivation
 * doesn't need.
 */
class TestCarbonIntensityDataFetcherMaxAttempts {

    @ParameterizedTest
    @CsvSource({
            // recoveryBudget, pollInterval, expectedMaxAttempts
            "PT10M, PT30S, 20", // the previous hardcoded default, unchanged: 10min / 30s = 20 attempts
            "PT1M, PT30S, 2",
            "PT15S, PT30S, 1", // budget shorter than a single poll interval: still at least 1 attempt
            "PT30S, PT30S, 1", // budget exactly equal to one interval: exactly 1 attempt, not 0
            "PT45S, PT30S, 1", // rounds down rather than scheduling a partial extra attempt
    })
    void whenDerivingMaxAttempts_thenAtLeastOneAttemptIsAlwaysScheduled(String budgetText, String pollIntervalText,
            int expectedMaxAttempts) {
        Duration recoveryBudget = Duration.parse(budgetText);
        Duration pollInterval = Duration.parse(pollIntervalText);

        assertThat(CarbonIntensityDataFetcherImpl.deriveMaxAttempts(recoveryBudget, pollInterval))
                .isEqualTo(expectedMaxAttempts);
    }

    /**
     * An excessively large (almost certainly misconfigured) budget must not silently overflow the
     * {@code long}-to-{@code int} narrowing into a near-zero or negative attempt count - that would defeat
     * the whole point of this derivation, which exists specifically to guarantee a positive attempt count.
     */
    @Test
    void whenBudgetIsExcessivelyLarge_thenAttemptCountIsClampedRatherThanOverflowing() {
        Duration recoveryBudget = Duration.ofSeconds(Long.MAX_VALUE);
        Duration pollInterval = Duration.ofSeconds(30);

        int maxAttempts = CarbonIntensityDataFetcherImpl.deriveMaxAttempts(recoveryBudget, pollInterval);

        assertThat(maxAttempts).isEqualTo(Integer.MAX_VALUE);
    }
}
