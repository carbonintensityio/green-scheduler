package io.carbonintensity.executionplanner.runtime.impl.rest;

import java.time.Duration;

/**
 * Pure exponential-backoff delay computation for {@link CarbonIntensityRestApi}'s retry loop, split out
 * from the networking code so it can be property-tested in isolation (see CIIO-470).
 */
final class RetryBackoff {

    private RetryBackoff() {
    }

    /**
     * @param attemptNumber the 1-based number of the attempt that just failed (1 for the first/original
     *        attempt, 2 for the first retry, ...)
     * @param initialBackoff delay before the first retry (i.e. the result for {@code attemptNumber == 1})
     * @param backoffMultiplier multiplier applied to the previous delay for each subsequent retry
     * @return the delay, in milliseconds, to wait before the next attempt
     */
    static long delayMillis(int attemptNumber, Duration initialBackoff, double backoffMultiplier) {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be >= 1, was " + attemptNumber);
        }
        double factor = Math.pow(backoffMultiplier, attemptNumber - 1);
        return Math.round(initialBackoff.toMillis() * factor);
    }
}
