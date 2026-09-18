package io.carbonintensity.scheduler.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.carbonintensity.executionplanner.spi.PlannedExecution;
import io.carbonintensity.scheduler.observability.DecisionReason;

/**
 * A {@link DecisionReason#GREENEST_AVAILABLE_SLOT} entry with no backing intensity value is a contradiction - it
 * claims a carbon-aware, measured choice while carrying no measurement at all. This locks down that
 * {@link DecisionOutcome#from} never lets that combination through.
 */
class DecisionOutcomeTest {

    private static final ZonedDateTime FIRE_TIME = ZonedDateTime.now(ZoneId.of("UTC"));

    @Test
    void aRealIntensityValueKeepsTheGreenestAvailableSlotReason() {
        PlannedExecution plannedExecution = new PlannedExecution(FIRE_TIME, Optional.of(new BigDecimal("41.2")));

        DecisionOutcome outcome = DecisionOutcome.from(plannedExecution, DecisionReason.GREENEST_AVAILABLE_SLOT);

        assertThat(outcome.reason()).isEqualTo(DecisionReason.GREENEST_AVAILABLE_SLOT);
        assertThat(outcome.intensityValue()).hasValue(41.2);
    }

    @Test
    void aMissingIntensityValueDowngradesGreenestAvailableSlotToNoDataAvailable() {
        PlannedExecution plannedExecution = new PlannedExecution(FIRE_TIME, Optional.empty());

        DecisionOutcome outcome = DecisionOutcome.from(plannedExecution, DecisionReason.GREENEST_AVAILABLE_SLOT);

        assertThat(outcome.reason()).isEqualTo(DecisionReason.NO_DATA_AVAILABLE);
        assertThat(outcome.intensityValue()).isEmpty();
    }

    @Test
    void aMissingIntensityValueDoesNotAlterAnUnrelatedReason() {
        // from() is only ever invoked with GREENEST_AVAILABLE_SLOT in practice - this just confirms the
        // downgrade is specific to that reason, not a blanket "no intensity -> rewrite the reason" rule.
        PlannedExecution plannedExecution = new PlannedExecution(FIRE_TIME, Optional.empty());

        DecisionOutcome outcome = DecisionOutcome.from(plannedExecution, DecisionReason.FALLBACK_TO_PLAIN_INTERVAL);

        assertThat(outcome.reason()).isEqualTo(DecisionReason.FALLBACK_TO_PLAIN_INTERVAL);
        assertThat(outcome.intensityValue()).isEmpty();
    }
}
