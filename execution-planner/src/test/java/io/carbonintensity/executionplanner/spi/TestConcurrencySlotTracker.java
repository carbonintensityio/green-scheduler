package io.carbonintensity.executionplanner.spi;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class TestConcurrencySlotTracker {

    private static final String ZONE_A = "NL";
    private static final String ZONE_B = "DE";
    private static final Instant SLOT = Instant.parse("2024-08-27T12:00:00Z");
    private static final Instant OTHER_SLOT = Instant.parse("2024-08-27T13:00:00Z");

    @Test
    void countOthersAtExcludesTheJobsOwnReservation() {
        ConcurrencySlotTracker tracker = new ConcurrencySlotTracker();

        tracker.reserve(ZONE_A, "job-1", SLOT);

        assertThat(tracker.countOthersAt(ZONE_A, "job-1", SLOT)).isZero();
        assertThat(tracker.countOthersAt(ZONE_A, "job-2", SLOT)).isEqualTo(1);
    }

    @Test
    void reservingANewSlotReleasesThePreviousOne() {
        ConcurrencySlotTracker tracker = new ConcurrencySlotTracker();

        tracker.reserve(ZONE_A, "job-1", SLOT);
        tracker.reserve(ZONE_A, "job-1", OTHER_SLOT);

        assertThat(tracker.countOthersAt(ZONE_A, "job-2", SLOT)).isZero();
        assertThat(tracker.countOthersAt(ZONE_A, "job-2", OTHER_SLOT)).isEqualTo(1);
    }

    @Test
    void reservationsAreScopedPerZone() {
        ConcurrencySlotTracker tracker = new ConcurrencySlotTracker();

        tracker.reserve(ZONE_A, "job-1", SLOT);

        assertThat(tracker.countOthersAt(ZONE_B, "job-2", SLOT)).isZero();
    }

    @Test
    void countOthersAtIsZeroForAnUnreservedSlot() {
        ConcurrencySlotTracker tracker = new ConcurrencySlotTracker();

        assertThat(tracker.countOthersAt(ZONE_A, "job-1", SLOT)).isZero();
    }
}
