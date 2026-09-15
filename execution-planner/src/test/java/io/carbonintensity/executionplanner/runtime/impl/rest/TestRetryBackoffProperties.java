package io.carbonintensity.executionplanner.runtime.impl.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.vavr.test.Arbitrary;
import io.vavr.test.Gen;
import io.vavr.test.Property;

/**
 * Property-based tests for {@link RetryBackoff}'s exponential-backoff computation (CIIO-470).
 * <p>
 * See {@code docs/adr/0002-vavr-test-over-jqwik.md} (in carbonintensity-api) for why vavr-test rather than
 * jqwik is used here.
 */
class TestRetryBackoffProperties {

    // Plausible configured initial backoffs: 1ms to 10 minutes.
    private static final Arbitrary<Duration> INITIAL_BACKOFFS = size -> Gen.choose(1L, 600_000L).map(Duration::ofMillis);

    // Plausible configured multipliers: 1.0 (no growth) to 10.0.
    private static final Arbitrary<Double> MULTIPLIERS = size -> Gen.choose(0, 900).map(hundredths -> 1.0 + hundredths / 100.0);

    // 1-based attempt numbers a real retry loop would ever pass in (bounded well above any realistic
    // retryMaxAttempts configuration).
    private static final Arbitrary<Integer> ATTEMPT_NUMBERS = size -> Gen.choose(1, 20);

    @Test
    void delayMillisMatchesTheDefaultsFromCIIO470() {
        // The ticket pins these two concrete numbers down explicitly: 300ms, then 900ms.
        assertThat(RetryBackoff.delayMillis(1, Duration.ofMillis(300), 3.0)).isEqualTo(300L);
        assertThat(RetryBackoff.delayMillis(2, Duration.ofMillis(300), 3.0)).isEqualTo(900L);
    }

    @Test
    void delayMillisForTheFirstAttemptIsAlwaysExactlyTheInitialBackoff() {
        Property.def("delayMillis(1, initialBackoff, multiplier) == initialBackoff.toMillis(), for any "
                + "initialBackoff/multiplier")
                .forAll(INITIAL_BACKOFFS, MULTIPLIERS)
                .suchThat((initialBackoff, multiplier) -> RetryBackoff.delayMillis(1, initialBackoff,
                        multiplier) == initialBackoff.toMillis())
                .check()
                .assertIsSatisfied();
    }

    @Test
    void delayMillisIsMonotonicallyNonDecreasingAsAttemptNumberGrows_whenMultiplierIsAtLeastOne() {
        Property.def("delayMillis(n+1, ...) >= delayMillis(n, ...) whenever multiplier >= 1.0")
                .forAll(ATTEMPT_NUMBERS, INITIAL_BACKOFFS, MULTIPLIERS)
                .suchThat((attemptNumber, initialBackoff, multiplier) -> {
                    long current = RetryBackoff.delayMillis(attemptNumber, initialBackoff, multiplier);
                    long next = RetryBackoff.delayMillis(attemptNumber + 1, initialBackoff, multiplier);
                    return next >= current;
                })
                .check()
                .assertIsSatisfied();
    }

    @Test
    void delayMillisIsNeverNegative() {
        Property.def("delayMillis(...) >= 0 for any attemptNumber/initialBackoff/multiplier combination")
                .forAll(ATTEMPT_NUMBERS, INITIAL_BACKOFFS, MULTIPLIERS)
                .suchThat((attemptNumber, initialBackoff, multiplier) -> RetryBackoff.delayMillis(attemptNumber, initialBackoff,
                        multiplier) >= 0)
                .check()
                .assertIsSatisfied();
    }

    @Test
    void delayMillisRejectsAnAttemptNumberBelowOne() {
        assertThatThrownBy(() -> RetryBackoff.delayMillis(0, Duration.ofMillis(300), 3.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
