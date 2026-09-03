package io.carbonintensity.executionplanner.spi;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.vavr.Tuple;
import io.vavr.test.Arbitrary;
import io.vavr.test.Gen;
import io.vavr.test.Property;

/**
 * Model-based property test for {@link ConcurrencySlotTracker}, alongside the example-based tests in
 * {@link TestConcurrencySlotTracker}.
 * <p>
 * {@link ConcurrencySlotTracker}'s three public methods are all serialized per zone under a single internal
 * lock, so the interesting invariants are not thread-race invariants but <em>sequential state-machine</em>
 * invariants: they must hold after every single step of an arbitrarily long, arbitrarily ordered sequence of
 * {@code reserve}/{@code tryReserve}/{@code countOthersAt} calls, regardless of which zones/identities/slots
 * those calls target. That fits a single-threaded model-based property test: generate a random command
 * sequence, replay it against a fresh tracker and a plain-map shadow model in lockstep, and check the
 * invariants after every step rather than only at the end.
 * <p>
 * The invariants under test are:
 * <ul>
 * <li>(a) each identity occupies at most one slot per zone at any time;</li>
 * <li>(b) {@code tryReserve} never lets more than {@code maxConcurrentPerSlot} other identities occupy a
 * slot;</li>
 * <li>(c) zones never interfere with each other.</li>
 * </ul>
 * These are not asserted directly (the tracker exposes no "list current occupants" accessor to check them
 * against) but indirectly and exhaustively: the shadow model records, per zone, at most one slot per
 * identity - so by construction it can only ever agree with the real tracker if (a) holds - and after every
 * step {@link #invariantsHold} cross-checks {@link ConcurrencySlotTracker#countOthersAt} against the model
 * for every zone/slot/identity combination in play, including an identity that never reserves anything. A
 * cleanup bug that leaves a stale occupant behind in the wrong slot (e.g. a skipped
 * {@code previousOccupants.remove}/{@code slots.remove}) or that leaks a reservation across zones would make
 * some real count exceed the model's, and would be caught by that cross-check on the very step it happens.
 * (b) is additionally checked directly by comparing {@code tryReserve}'s return value against the model's
 * own prediction at every step.
 * <p>
 * See {@code docs/adr/0002-vavr-test-over-jqwik.md} (in carbonintensity-api) for why vavr-test rather than
 * jqwik is used here.
 */
class TestConcurrencySlotTrackerProperties {

    // A small, deliberately overlapping pool of zones/identities/slots so that a typical generated sequence
    // produces plenty of collisions (same identity/slot revisited, different identities racing for the same
    // slot, several identities moving between slots) rather than every call landing on fresh state.
    private static final List<String> ZONES = List.of("Z1", "Z2");
    private static final List<String> IDENTITIES = List.of("id-1", "id-2", "id-3", "id-4");
    // Never targeted by a generated command - exercises the "identity that has never reserved anything"
    // branch of countOthersAt in the invariant cross-check below.
    private static final String NEVER_RESERVED_IDENTITY = "id-ghost";
    private static final List<String> CHECK_IDENTITIES = List.of("id-1", "id-2", "id-3", "id-4", NEVER_RESERVED_IDENTITY);
    private static final List<Instant> SLOTS = List.of(
            Instant.parse("2024-08-27T12:00:00Z"),
            Instant.parse("2024-08-27T13:00:00Z"),
            Instant.parse("2024-08-27T14:00:00Z"));

    private static final Gen<String> ZONE_GEN = Gen.choose(ZONES);
    private static final Gen<String> IDENTITY_GEN = Gen.choose(IDENTITIES);
    private static final Gen<Instant> SLOT_GEN = Gen.choose(SLOTS);
    private static final Gen<Integer> MAX_CONCURRENT_GEN = Gen.choose(0, 3);

    private static final Gen<Command> RESERVE_GEN = ZONE_GEN
            .flatMap(zone -> IDENTITY_GEN
                    .flatMap(identity -> SLOT_GEN
                            .map(slot -> new Reserve(zone, identity, slot))));

    private static final Gen<Command> TRY_RESERVE_GEN = ZONE_GEN
            .flatMap(zone -> IDENTITY_GEN
                    .flatMap(identity -> SLOT_GEN
                            .flatMap(slot -> MAX_CONCURRENT_GEN
                                    .map(max -> new TryReserve(zone, identity, slot, max)))));

    private static final Gen<Command> COUNT_OTHERS_AT_GEN = ZONE_GEN
            .flatMap(zone -> IDENTITY_GEN
                    .flatMap(identity -> SLOT_GEN
                            .map(slot -> new CountOthersAt(zone, identity, slot))));

    // Weighted towards tryReserve, since that's the compound check-and-reserve operation where a race or a
    // cleanup bug would actually be observable; plain reserve and read-only countOthersAt calls are mixed in
    // so sequences also cover the "fallback always wins" and pure-read cases interleaved with it.
    private static final Gen<Command> COMMAND_GEN = Gen.frequency(
            Tuple.of(3, RESERVE_GEN),
            Tuple.of(5, TRY_RESERVE_GEN),
            Tuple.of(2, COUNT_OTHERS_AT_GEN));

    private static final Arbitrary<io.vavr.collection.List<Command>> COMMAND_SEQUENCE = Arbitrary.list(COMMAND_GEN.arbitrary());

    @Test
    void invariantsHoldAfterEveryStepOfAnyCommandSequence() {
        Property.def("ConcurrencySlotTracker keeps its per-identity/per-zone/limit invariants after every step")
                .forAll(COMMAND_SEQUENCE)
                .suchThat(TestConcurrencySlotTrackerProperties::replayAndCheckInvariantsThroughout)
                .check()
                .assertIsSatisfied();
    }

    private static boolean replayAndCheckInvariantsThroughout(io.vavr.collection.List<Command> commands) {
        ConcurrencySlotTracker tracker = new ConcurrencySlotTracker();
        // Shadow model: zone -> (identity -> the one slot it currently holds in that zone). Its very shape
        // enforces invariant (a) - one slot per identity per zone - so agreement with the real tracker below
        // is only possible if the tracker maintains that invariant too.
        Map<String, Map<String, Instant>> model = new HashMap<>();

        for (Command command : commands) {
            if (!applyAndVerify(command, tracker, model)) {
                return false;
            }
            if (!invariantsHold(tracker, model)) {
                return false;
            }
        }
        return true;
    }

    private static boolean applyAndVerify(Command command, ConcurrencySlotTracker tracker,
            Map<String, Map<String, Instant>> model) {
        if (command instanceof Reserve r) {
            tracker.reserve(r.zone(), r.identity(), r.slot());
            model.computeIfAbsent(r.zone(), z -> new HashMap<>()).put(r.identity(), r.slot());
        } else if (command instanceof TryReserve t) {
            boolean expectedSuccess = countOthersInModel(model, t.zone(), t.identity(), t.slot()) < t.maxConcurrentPerSlot();
            boolean actualSuccess = tracker.tryReserve(t.zone(), t.identity(), t.slot(), t.maxConcurrentPerSlot());
            if (actualSuccess != expectedSuccess) {
                return false;
            }
            if (expectedSuccess) {
                model.computeIfAbsent(t.zone(), z -> new HashMap<>()).put(t.identity(), t.slot());
            }
        } else if (command instanceof CountOthersAt c) {
            int expected = countOthersInModel(model, c.zone(), c.identity(), c.slot());
            int actual = tracker.countOthersAt(c.zone(), c.identity(), c.slot());
            if (actual != expected) {
                return false;
            }
        }
        return true;
    }

    // Exhaustively cross-checks the real tracker against the shadow model for every zone/slot/identity
    // combination in play - not just the ones the just-applied command happened to target - so a stale
    // occupant left behind in the wrong slot, or a reservation that leaked into the wrong zone, is caught on
    // the very step it happens rather than only if a later command happens to query that exact spot.
    private static boolean invariantsHold(ConcurrencySlotTracker tracker, Map<String, Map<String, Instant>> model) {
        for (String zone : ZONES) {
            for (Instant slot : SLOTS) {
                for (String identity : CHECK_IDENTITIES) {
                    int expected = countOthersInModel(model, zone, identity, slot);
                    int actual = tracker.countOthersAt(zone, identity, slot);
                    if (actual != expected) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static int countOthersInModel(Map<String, Map<String, Instant>> model, String zone, String identity,
            Instant slot) {
        Map<String, Instant> reservationsInZone = model.get(zone);
        if (reservationsInZone == null) {
            return 0;
        }
        int count = 0;
        for (Map.Entry<String, Instant> entry : reservationsInZone.entrySet()) {
            if (!entry.getKey().equals(identity) && entry.getValue().equals(slot)) {
                count++;
            }
        }
        return count;
    }

    /** A single call against {@link ConcurrencySlotTracker}, as one step of a generated command sequence. */
    private sealed interface Command permits Reserve, TryReserve, CountOthersAt {
    }

    private record Reserve(String zone, String identity, Instant slot) implements Command {
    }

    private record TryReserve(String zone, String identity, Instant slot, int maxConcurrentPerSlot) implements Command {
    }

    private record CountOthersAt(String zone, String identity, Instant slot) implements Command {
    }
}
