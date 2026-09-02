# 2. Spreading concurrent scheduled jobs across carbon-intensity slots

## Status

Accepted

## Context

Every `@GreenScheduled` job picks its own greenest moment independently: `SingleJobStrategy.bestTimeslot(ws, we,
duration, carbonIntensity)` looks only at that one job's window, duration and `carbonIntensityZone`. There is no
coordination across jobs - `SchedulerContextImpl` holds the full job list for an application but never uses it
for that purpose, and the existing `ConcurrentExecution` enum only guards re-entrancy of a *single* job's repeated
runs (and explicitly doesn't work across a cluster; see the "Concurrent executions" page in the docs site, which
covers that different problem).

The origin was a scaling concern raised by Wilko Zonnenberg: if an application has, say, ten `@GreenScheduled`
jobs and the greenest moment for all of them happens to be the same instant, they all fire together and can
overload the machine - even though several of them would have been just as green a few minutes or hours later.

This belongs in `green-scheduler`, not in `carbonintensity-api`: the API is a stateless, job-agnostic
time-series provider with no concept of a job, its duration, or concurrency between jobs. All "best moment"
logic already lives here, in the planners.

The design was worked out via a design review with advisor input (issue #234) before implementation (PR #235,
which resolved #234). This ADR captures the five decisions reached there, checked against what actually shipped.

## Decision

### Scope: JVM-local only

Coordination happens only between `@GreenScheduled` jobs running in the same application instance. Cluster-wide
coordination between replicas of the *same* job is an explicit non-goal - it's a separate, much larger problem
(distributed locking / leader election) that would need its own design.

This is reflected directly in the shipped code: `SimpleScheduler` owns a single `ConcurrencySlotTracker` instance
per `SimpleScheduler` (i.e. per running application instance) and hands it to every `FixedWindowPlanner` and
`SuccessivePlanner` it creates. Nothing about the tracker is shared or persisted beyond that JVM's process
lifetime. Users who also need cross-instance coordination are pointed at ShedLock (already used for the
unrelated re-entrancy problem) rather than at this feature.

### Granularity: per zone, not global per application

Jobs targeting different `CarbonIntensityZone`s have independent optimal moments and must not compete for the
same slot count just because they happen to run in the same application. `ConcurrencySlotTracker` reflects this
directly: its bookkeeping (`slotsByZone`, `reservedSlotByZoneAndIdentity`) is keyed first by zone, so two jobs in
different zones never contend with each other regardless of timing.

Within a zone, only jobs whose candidate windows actually overlap in time end up contending for a slot - this
falls out naturally from keying reservations by exact instant (`countOthersAt(zone, jobIdentity, slotStart)`),
rather than needing an explicit "window" concept in the tracker itself. A job whose window never proposes a
given instant simply never reserves it.

### Default: opt-in, off by default

`maxConcurrentPerSlot` defaults to `0` in `SchedulerConfig`/`SchedulerDefaults`, and both the Quarkus
(`green-scheduler.max-concurrent-per-slot`) and Spring Boot equivalents carry the same default through. `0`
means spreading is disabled: existing behavior (jobs may collide on the same moment) is preserved unless a user
opts in. This is shipped as a **minor** release, not a breaking one - spreading changes *when* a job fires, which
is scheduling semantics a user has to consent to, unlike an operational safety knob such as `jobExecutors`.

Note that in the shipped code the tracker is always constructed and always passed into every `FixedWindowPlanner`
and `SuccessivePlanner` (there's no "tracker is null when disabled" branch at the `SimpleScheduler` level); each
planner instead checks `maxConcurrentPerSlot <= 0` and, when true, calls `strategy.bestTimeslot(...)` exactly as
before the feature existed, bypassing the tracker entirely. The externally observable behavior is identical to
"off" either way, but it's worth knowing the off-switch lives in the planners' `getNextExecutionTime`, not in
whether a tracker exists.

### Configuration shape: one global property

A single property, `maxConcurrentPerSlot`, analogous to `jobExecutors`. It lives once in `core`'s
`SchedulerConfig`/`SchedulerDefaults` and is surfaced through both the Quarkus `GreenSchedulerProperties`
(`@ConfigMapping`, `OptionalInt maxConcurrentPerSlot()`) and the Spring Boot `GreenSchedulerProperties`/
`SchedulerConfigBuilder`, exactly as `jobExecutors` already is. There is no per-job attribute on
`@GreenScheduled` - every job in an application shares the same limit. A per-job override was explicitly left
for later, only if concretely needed.

### Overflow behavior: the job's own window always wins

A job's configured window is a hard promise to the user and always takes priority over the concurrency limit. In
`FixedWindowPlanner.pickTimeslot(...)`, `SingleJobStrategy.rankedTimeslots(...)` returns all candidate slots in
the window ordered by intensity; the planner walks that list and reserves the first slot where
`slotTracker.countOthersAt(zone, identity, slot) < maxConcurrentPerSlot`. If none qualify, the job still runs at
its single greenest slot in the window - the limit is best-effort, not a hard cap - and a `log.warn(...)` records
that the limit was exceeded for that zone/slot/job.

The original #234 write-up expected this fallback to be needed only in `FixedWindowPlanner`, reasoning that
`SuccessivePlanner` "has no such conflict since it can simply look further ahead." **The shipped implementation
diverges here**: `SuccessivePlanner` also has a bounded window for concurrency purposes - `ws`..`we`, computed as
either `initialStartTime`..`initialStartTime + initialMaximumDelay` (first run) or
`lastExecutionTime + minimumGap`..`lastExecutionTime + maximumGap` (subsequent runs) - and it cannot look past
`we` without breaking its own gap contract. `SuccessivePlanner.pickTimeslot(...)` therefore implements the exact
same ranked-list-then-fallback logic as `FixedWindowPlanner`, including its own `log.warn(...)` ("...to honor its
gap window"). In practice both planners end up with identical overflow handling; the "always honor the window"
promise turned out to apply uniformly rather than being specific to `FixedWindowPlanner`.

One further implementation detail not spelled out in #234: the concurrency check and reservation
(`pickTimeslot`) live inside `FixedWindowPlanner`/`SuccessivePlanner` themselves, not in
`SimpleScheduler.checkTriggers()` or a separate coordinator class as #234's "suggested implementation shape"
sketched. The shared state (`ConcurrencySlotTracker`) is still owned and created once by `SimpleScheduler` and
injected into each planner in `createTrigger(...)`, which achieves the same JVM-wide coordination goal; only the
call site of the actual pick-a-slot decision differs from what was sketched. `SingleJobStrategy` itself stayed
stateless and per-job, as intended - it only gained `rankedTimeslots(...)` (with `bestTimeslot(...)` now built on
top of it) so callers can walk multiple candidates instead of only the single best.

## Consequences

Jobs in the same zone with overlapping windows no longer have to collide on the exact same moment once a user
opts in, without changing default behavior for anyone who doesn't. The cost is a small amount of shared mutable
state per application instance (`ConcurrencySlotTracker`) and two planners that now each carry both a stateless
"pick the best slot" path and a stateful "pick the best non-conflicting slot, falling back to best-effort" path,
rather than a single code path. The limit is intentionally soft: a job can still exceed `maxConcurrentPerSlot` if
its window leaves no other option, which is the correct trade-off given decision 3 above (a job's own window is a
promise, the limit is not), but it means `maxConcurrentPerSlot` alone doesn't guarantee an upper bound on
simultaneous starts under a tight configuration - only a `log.warn` records when that happens. Whether that
warrants a metric (beyond the log) is tracked separately, outside this ADR's scope.

Cross-instance coordination (multiple replicas of the same application) and per-job limit overrides remain
explicitly out of scope, as decided in #234; both would need their own design work if ever needed.
