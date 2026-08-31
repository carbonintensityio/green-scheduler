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
 */
public class ConcurrencySlotTracker {

    private final ConcurrentMap<String, ConcurrentMap<Instant, Set<String>>> slotsByZone = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ConcurrentMap<String, Instant>> reservedSlotByZoneAndIdentity = new ConcurrentHashMap<>();

    /**
     * Reserves the given slot for the given job identity within the given zone, replacing any previous
     * reservation this identity held in that zone.
     */
    public void reserve(String zone, String jobIdentity, Instant slotStart) {
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

    /**
     * @return the number of job identities, other than {@code jobIdentity} itself, already reserved to
     *         start at {@code slotStart} within {@code zone}.
     */
    public int countOthersAt(String zone, String jobIdentity, Instant slotStart) {
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
