package io.carbonintensity.scheduler.runtime;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.OptionalDouble;

import io.carbonintensity.executionplanner.spi.PlannedExecution;
import io.carbonintensity.scheduler.observability.DecisionReason;

/**
 * What a trigger decided on a single {@code evaluate()} call that led to a
 * real fire: why this moment was chosen, and the carbon-intensity value
 * behind it, when known.
 * <p>
 * Deliberately not public API - the public-facing shape this feeds into is
 * {@link io.carbonintensity.scheduler.observability.DecisionTimelineEntry}.
 */
record DecisionOutcome(DecisionReason reason, OptionalDouble intensityValue) {

    /**
     * Constructed atomically on purpose: mutating {@code reason} and
     * {@code intensityValue} independently could let a fallback
     * (cron/interval) fire keep a stale value left over from a prior
     * carbon-aware fire.
     */
    DecisionOutcome {
        Objects.requireNonNull(reason, "Reason cannot be null");
        Objects.requireNonNull(intensityValue, "IntensityValue cannot be null - use OptionalDouble.empty()");
    }

    /**
     * Pulls the intensity straight from the {@link PlannedExecution} already
     * built for this fire, rather than re-deriving it separately. Fallback
     * triggers build a {@code DecisionOutcome} directly instead, always
     * pairing their reason with {@link OptionalDouble#empty()} - never an
     * inherited value.
     * <p>
     * A {@link DecisionReason#GREENEST_AVAILABLE_SLOT} reason with no backing
     * intensity value is downgraded to {@link DecisionReason#NO_DATA_AVAILABLE}
     * here: {@code GREENEST_AVAILABLE_SLOT} claims a real, measured basis for
     * the choice, which a missing intensity value contradicts (CIIO-485).
     */
    static DecisionOutcome from(PlannedExecution plannedExecution, DecisionReason reason) {
        Objects.requireNonNull(plannedExecution, "PlannedExecution cannot be null");
        BigDecimal intensity = plannedExecution.intensityValue().orElse(null);
        if (intensity == null && reason == DecisionReason.GREENEST_AVAILABLE_SLOT) {
            reason = DecisionReason.NO_DATA_AVAILABLE;
        }
        return new DecisionOutcome(reason,
                intensity == null ? OptionalDouble.empty() : OptionalDouble.of(intensity.doubleValue()));
    }
}
