package io.carbonintensity.executionplanner.runtime.impl.rest;

import java.time.Duration;

/**
 * Configuration for {@link CarbonIntensityRestApi}, including the retry/backoff behaviour used on the
 * scheduler's critical path and the staleness threshold for reusing a last-known-good value.
 * <p>
 * See CIIO-470: these knobs replace a static, per-zone fallback dataset that used to return fabricated
 * intensity values whenever the real API was briefly unreachable. Every extension exposes the same knobs
 * (e.g. {@code green-scheduler.carbon-intensity-retry-max-attempts} for Quarkus) so a deployment can tune
 * them without a code change, without anyone actually needing to touch them for the defaults to be sane.
 */
public class CarbonIntensityApiConfig {

    /** Total attempts (1 original + retries) for a single foreground fetch. */
    public static final int DEFAULT_RETRY_MAX_ATTEMPTS = 3;
    /** Delay before the first retry; each subsequent retry multiplies this by {@link #DEFAULT_RETRY_BACKOFF_MULTIPLIER}. */
    public static final Duration DEFAULT_RETRY_INITIAL_BACKOFF = Duration.ofMillis(300);
    /** With the defaults above: 300ms, then 900ms. */
    public static final double DEFAULT_RETRY_BACKOFF_MULTIPLIER = 3.0;
    /** Hard ceiling on the total time a single foreground fetch (including all retries) may take. */
    public static final Duration DEFAULT_RETRY_BUDGET = Duration.ofSeconds(2);
    /** How old a last-known-good value may be and still count as a genuine carbon-aware decision. */
    public static final Duration DEFAULT_STALENESS_THRESHOLD = Duration.ofHours(4);
    /**
     * Total wall-clock time {@link io.carbonintensity.executionplanner.runtime.impl.BackgroundCarbonIntensityRefresher}
     * may keep polling a failed zone before giving up, until a subsequent foreground failure restarts it.
     * Matches the previous hardcoded behaviour exactly (30s poll interval * 20 attempts = 10 minutes); the
     * poll interval itself stays an internal constant, see that class.
     */
    public static final Duration DEFAULT_RECOVERY_BUDGET = Duration.ofMinutes(10);

    private final String apiKey;
    private final String apiUrl;
    private final boolean enabled;
    private final int retryMaxAttempts;
    private final Duration retryInitialBackoff;
    private final double retryBackoffMultiplier;
    private final Duration retryBudget;
    private final Duration stalenessThreshold;
    private final Duration recoveryBudget;

    protected CarbonIntensityApiConfig(Builder builder) {
        this.apiKey = builder.apiKey;
        this.apiUrl = builder.apiUrl;
        this.enabled = this.apiKey != null && !this.apiKey.isBlank() && this.apiUrl != null
                && !this.apiUrl.isBlank();
        this.retryMaxAttempts = builder.retryMaxAttempts != null ? builder.retryMaxAttempts : DEFAULT_RETRY_MAX_ATTEMPTS;
        this.retryInitialBackoff = builder.retryInitialBackoff != null ? builder.retryInitialBackoff
                : DEFAULT_RETRY_INITIAL_BACKOFF;
        this.retryBackoffMultiplier = builder.retryBackoffMultiplier != null ? builder.retryBackoffMultiplier
                : DEFAULT_RETRY_BACKOFF_MULTIPLIER;
        this.retryBudget = builder.retryBudget != null ? builder.retryBudget : DEFAULT_RETRY_BUDGET;
        this.stalenessThreshold = builder.stalenessThreshold != null ? builder.stalenessThreshold
                : DEFAULT_STALENESS_THRESHOLD;
        this.recoveryBudget = builder.recoveryBudget != null ? builder.recoveryBudget : DEFAULT_RECOVERY_BUDGET;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    /**
     * Total attempts (1 original + retries) allowed for a single foreground fetch. Only retried on
     * transient connectivity errors, never on an HTTP error response.
     */
    public int getRetryMaxAttempts() {
        return retryMaxAttempts;
    }

    /** Delay before the first retry. */
    public Duration getRetryInitialBackoff() {
        return retryInitialBackoff;
    }

    /** Multiplier applied to the previous backoff for each subsequent retry. */
    public double getRetryBackoffMultiplier() {
        return retryBackoffMultiplier;
    }

    /** Hard ceiling on the total time (across all attempts) a single foreground fetch may take. */
    public Duration getRetryBudget() {
        return retryBudget;
    }

    /** How old a last-known-good value per zone may be and still be reused as a genuine decision. */
    public Duration getStalenessThreshold() {
        return stalenessThreshold;
    }

    /**
     * Total wall-clock time the background recovery poller may keep retrying a failed zone before giving
     * up. The poller's own poll interval (30s) stays an internal, non-configurable constant - only this
     * total budget is configurable.
     */
    public Duration getRecoveryBudget() {
        return recoveryBudget;
    }

    public static class Builder {
        private String apiKey;
        private String apiUrl;
        private Integer retryMaxAttempts;
        private Duration retryInitialBackoff;
        private Double retryBackoffMultiplier;
        private Duration retryBudget;
        private Duration stalenessThreshold;
        private Duration recoveryBudget;

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder apiUrl(String apiUrl) {
            this.apiUrl = apiUrl;
            return this;
        }

        public Builder retryMaxAttempts(Integer maxAttempts) {
            if (maxAttempts != null && maxAttempts < 1) {
                throw new IllegalArgumentException("retryMaxAttempts must be at least 1, was " + maxAttempts);
            }
            this.retryMaxAttempts = maxAttempts;
            return this;
        }

        public Builder retryInitialBackoff(Duration initialBackoff) {
            this.retryInitialBackoff = initialBackoff;
            return this;
        }

        public Builder retryBackoffMultiplier(Double backoffMultiplier) {
            if (backoffMultiplier != null && backoffMultiplier < 1.0) {
                throw new IllegalArgumentException(
                        "retryBackoffMultiplier must be at least 1.0, was " + backoffMultiplier);
            }
            this.retryBackoffMultiplier = backoffMultiplier;
            return this;
        }

        public Builder retryBudget(Duration budget) {
            this.retryBudget = budget;
            return this;
        }

        public Builder stalenessThreshold(Duration threshold) {
            this.stalenessThreshold = threshold;
            return this;
        }

        /**
         * Sets the total wall-clock recovery budget. Must be positive: a zero or negative budget would leave
         * the background poller with no time to ever attempt a recovery.
         */
        public Builder recoveryBudget(Duration budget) {
            if (budget != null && (budget.isZero() || budget.isNegative())) {
                throw new IllegalArgumentException("recoveryBudget must be positive, was " + budget);
            }
            this.recoveryBudget = budget;
            return this;
        }

        public CarbonIntensityApiConfig build() {
            return new CarbonIntensityApiConfig(this);
        }
    }
}
