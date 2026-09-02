package io.carbonintensity.executionplanner.spi;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Tracks, per carbon-intensity zone, which job identities are currently reserved to start at a given
 * instant. Used by {@link CarbonIntensityPlanner} implementations to spread jobs across multiple green
 * moments when several of them would otherwise all land on the exact same moment within the same zone.
 * <p>
 * A single tracker instance is meant to be shared by all jobs scheduled within one application instance.
 * It only coordinates jobs known to this JVM - it is not intended to coordinate scheduling across a
 * cluster of application instances.
 * <p>
 * All three public methods are serialized per zone (via an internal per-zone lock), so concurrent callers
 * targeting the same zone never observe a torn intermediate state and {@link #tryReserve} is a genuine
 * check-and-reserve compound operation, not a check-then-act race. Two different zones never contend on
 * the same lock, preserving the existing zone-isolation guarantee.
 */
public class ConcurrencySlotTracker {

    private final ConcurrentMap<String, ConcurrentMap<Instant, Set<String>>> slotsByZone = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ConcurrentMap<String, Instant>> reservedSlotByZoneAndIdentity = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> zoneLocks = new ConcurrentHashMap<>();

    /**
     * Reserves the given slot for the given job identity within the given zone, replacing any previous
     * reservation this identity held in that zone, regardless of how many other identities already
     * occupy that slot. Used for the "the job's own window always wins" fallback - prefer
     * {@link #tryReserve} when a concurrency limit should actually be enforced.
     */
    public void reserve(String zone, String jobIdentity, Instant slotStart) {
        synchronized (lockFor(zone)) {
            reserveLocked(zone, jobIdentity, slotStart);
        }
    }

    /**
     * @return the number of job identities, other than {@code jobIdentity} itself, already reserved to
     *         start at {@code slotStart} within {@code zone}.
     */
    public int countOthersAt(String zone, String jobIdentity, Instant slotStart) {
        synchronized (lockFor(zone)) {
            return countOthersAtLocked(zone, jobIdentity, slotStart);
        }
    }

    /**
     * Atomically checks whether fewer than {@code maxConcurrentPerSlot} other identities already occupy
     * {@code slotStart} within {@code zone}, and if so, reserves it for {@code jobIdentity} - all under
     * the same per-zone lock, so no other {@code tryReserve}/{@code reserve}/{@code countOthersAt} call
     * for the same zone can interleave between the check and the reservation.
     *
     * @return {@code true} if the slot was reserved; {@code false} if the limit was already reached, in
     *         which case no state is changed.
     */
    public boolean tryReserve(String zone, String jobIdentity, Instant slotStart, int maxConcurrentPerSlot) {
        synchronized (lockFor(zone)) {
            if (countOthersAtLocked(zone, jobIdentity, slotStart) >= maxConcurrentPerSlot) {
                return false;
            }
            reserveLocked(zone, jobIdentity, slotStart);
            return true;
        }
    }

    private Object lockFor(String zone) {
        return zoneLocks.computeIfAbsent(zone, z -> new Object());
    }

    private void reserveLocked(String zone, String jobIdentity, Instant slotStart) {
        ConcurrentMap<String, Instant> reservedByIdentity = reservedSlotByZoneAndIdentity.computeIfAbsent(zone,
                z -> new ConcurrentHashMap<>());
        ConcurrentMap<Instant, Set<String>> slots = slotsByZone.computeIfAbsent(zone, z -> new ConcurrentHashMap<>());

        Instant previous = reservedByIdentity.put(jobIdentity, slotStart);
        if (previous != null && !previous.equals(slotStart)) {
            Set<String> previousOccupants = slots.get(previous);
            if (previousOccupants != null) {
                previousOccupants.remove(jobIdentity);
                if (previousOccupants.isEmpty()) {
                    slots.remove(previous, previousOccupants);
                }
            }
        }
        slots.computeIfAbsent(slotStart, s -> ConcurrentHashMap.newKeySet()).add(jobIdentity);
    }

    private int countOthersAtLocked(String zone, String jobIdentity, Instant slotStart) {
        ConcurrentMap<Instant, Set<String>> slots = slotsByZone.get(zone);
        if (slots == null) {
            return 0;
        }
        Set<String> occupants = slots.get(slotStart);
        if (occupants == null) {
            return 0;
        }
        return occupants.contains(jobIdentity) ? occupants.size() - 1 : occupants.size();
    }
}
