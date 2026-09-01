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
    void twoZonesCanIndependentlyReserveTheExactSameInstant() {
        ConcurrencySlotTracker tracker = new ConcurrencySlotTracker();

        // Two jobs in different zones both want the exact same instant. Neither should see the
        // other's reservation: coordination is per zone, never global.
        tracker.reserve(ZONE_A, "job-a1", SLOT);
        tracker.reserve(ZONE_B, "job-b1", SLOT);

        // Each was the first (and only) reservation in its own zone, so each sees zero competitors
        // at that instant - i.e. neither would be bumped to a later slot.
        assertThat(tracker.countOthersAt(ZONE_A, "job-a1", SLOT)).isZero();
        assertThat(tracker.countOthersAt(ZONE_B, "job-b1", SLOT)).isZero();

        // A second job arriving in either zone only competes with reservations in that same zone.
        assertThat(tracker.countOthersAt(ZONE_A, "job-a2", SLOT)).isEqualTo(1);
        assertThat(tracker.countOthersAt(ZONE_B, "job-b2", SLOT)).isEqualTo(1);
    }

    @Test
    void countOthersAtIsZeroForAnUnreservedSlot() {
        ConcurrencySlotTracker tracker = new ConcurrencySlotTracker();

        assertThat(tracker.countOthersAt(ZONE_A, "job-1", SLOT)).isZero();
    }
}
