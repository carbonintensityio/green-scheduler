# 3. Retry, last-known-value cache and the existing fallback route replace the static fallback dataset

## Status

Accepted

## Context

During the internal pilot (`green-scheduler-pilot`, CIIO-449) a handful of `decision_timeline_entry` rows had a
completely unrealistic `intensity_value` (11346-13926, then 12066 and 12504), while normal values in the same
dataset are 4-120. PL and FR received the exact same value (12066) at the exact same moment - a strong signal
that this was not a real measurement.

The root cause: whenever the real CarbonIntensity REST API was briefly unreachable, `CarbonIntensityDataFetcherImpl`
silently fell back to a bundled, static dataset (`CarbonIntensityFileApi`, reading
`core/src/main/resources/fallback/{zone}/{timezone}.json`, or a generic `fallback/z.json` for any zone without its
own file). Two compounding defects in that dataset:

- Only `nl` had a zone-specific file; every other zone (including PL and FR) silently shared the same generic
  `z.json`, explaining the identical values.
- Even the NL-specific data contained values in the 40,000-117,000 range - obviously a synthetic test fixture,
  never intended as a realistic gCO2/kWh reading.

For every consumer, not just the pilot, this meant: the moment the REST API hiccups, the scheduler writes an
absurd intensity value with no marker anywhere that it came from fallback data rather than a real measurement.
This was tracked and resolved (grilled) as CIIO-470.

## Decision

Remove the static fallback dataset entirely - `core/src/main/resources/fallback/**` and `CarbonIntensityFileApi`
are deleted, with no replacement dataset per zone and no generic placeholder. In its place, a combination of four
mechanisms, implemented once in `execution-planner` (never per-extension: `CarbonIntensityRestApi`/
`CarbonIntensityDataFetcherImpl` use the bare JDK `HttpClient` and always have - Quarkus/Spring/Micronaut have no
client setup of their own for this call, only for `@GreenScheduled` annotation detection).

### 1. A short, bounded, synchronous retry on the critical path

`CarbonIntensityRestApi.getCarbonIntensity(...)` retries up to `retryMaxAttempts` times in total (default 3: one
original attempt plus two retries), with exponential backoff (`retryInitialBackoff` default 300ms,
`retryBackoffMultiplier` default 3.0, giving 300ms then 900ms), bounded by a hard `retryBudget` (default 2s) that
overrides any further retry regardless of how much of `retryMaxAttempts` is left.

Only a transient, connection-level failure is retried (`ConnectException`, `UnresolvedAddressException`, or any
other `IOException` - see `TransientConnectivityErrors`), never a real HTTP error response
(`CarbonIntensityApiException`, raised for any non-200 status): a 4xx/5xx will not be fixed by an immediate retry,
and retrying a 429 only makes the situation worse.

This budget is deliberately short and hard, not a matter of taste: `SimpleScheduler.checkTriggers()` iterates
*serially* over every registered job on a shared `ScheduledThreadPoolExecutor(2)`, and
`CarbonIntensityDataFetcherImpl.fetchCarbonIntensity()` blocks on `.join()`. A longer retry here would delay
trigger evaluation for every other job sharing that scheduler instance, not only the affected one.

### 2. A last-known-value cache, independent of the existing period-keyed cache

`LastKnownIntensityCache` remembers the most recently, successfully fetched *real* value per zone (a single
representative `BigDecimal`, picked from whichever data point in a fresh fetch covers "now", or the first point
otherwise - see `CarbonIntensityDataFetcherImpl.representativeValue`). As long as that value is not older than
`stalenessThreshold` (default 4h, within the agreed 3-6h margin), reusing it still counts as a genuine
carbon-aware decision: no new `DecisionReason` is introduced (see "Deviations from the original spec" below - the
`DecisionReason`/decision-timeline enum this ticket assumed already exists on `main` does not, yet). When reused,
a synthetic `CarbonIntensity` is built spanning the *requested* window with that single value applied uniformly
(`synthesizeFromLastKnownValue`): there's no way to rank sub-slots within the window without real granular data,
so every slot reports the same intensity and the planner picks the earliest one - the honest thing to do with a
single stale reading, rather than fabricating a shape that looks more precise than it is.

This is deliberately a *different* cache from the existing, period-keyed `CarbonIntensityCache` (which caches a
full fetched result, keyed by `(hour, zone)`, including a negative/"no data" result for one hour -
`DEFAULT_TTL_EMPTY_VALUES`). The two compose: a negative-cached period still consults the last-known-value cache
before reporting "no data" to the caller (see decision 4).

### 3. No usable data (after retry and cache) lets the existing fallback route take over

Proven while grilling this: `constraints.getFallbackCronExpression()` is never absent for a `fixedWindow()` job -
`GreenScheduledAnnotationParser.getFallBackCronExpression()`/`calculateFallBackCronExpression()` computes one
itself (the midpoint of the window) whenever the user didn't configure one. `successive()` jobs have the same
guarantee structurally, via the already-mandatory min/max-gap parameters (`SimpleScheduler.SuccessiveTrigger`
falls back to a plain `IntervalTrigger` using their average as the interval). There is no scenario in which the
framework has nothing to fall back to - the static dataset solved a problem that cannot structurally exist.

`FixedWindowPlanner.canSchedule(...)` and `SuccessivePlanner.canSchedule(...)` used to be `return constraints !=
null` - in practice a no-op, since `CarbonIntensityDataFetcherImpl` used to always return *some* result (real or
fabricated). Both now actually fetch (reusing whatever the period cache and last-known-value cache already
resolved) and return `hasData()`. When that's `false`, `FixedWindowTrigger`/`SuccessiveTrigger` already had the
code path to fall through to their cron-midpoint/plain-interval fallback (`super.evaluate(now)`); it simply never
used to trigger before this change.

Making `canSchedule()` a real, cache-backed fetch surfaced a latent mismatch in `SimpleScheduler.SuccessiveTrigger`:
`getNextFireTime()`/`evaluate()`/`isOverdue()` all called `successivePlanner.canSchedule(constraints)` using the
trigger's original, never-updated `constraints` field, while `getNextExecutionTime(...)` (called moments later in
the same method) is passed `effectiveConstraints()` - the same constraints with `lastExecutionTime` folded in once
the job has fired at least once. Harmless while `canSchedule()` was a no-op, but with a real fetch behind it this
meant `canSchedule()` queried an ever-more-stale, permanently frozen "day one" window forever, while
`getNextExecutionTime()` queried the correct, advancing one - two different `CarbonIntensityCache` keys, so the
second call was never the cheap cache hit it looks like at a glance, and past roughly a day of the job running,
`canSchedule()` would degrade into a real, repeated live fetch (or `BackgroundCarbonIntensityRefresher` cycle) for a
window that could never again be used. Fixed as part of this change: all three call sites now pass
`effectiveConstraints()` to `canSchedule()`, matching `getNextExecutionTime()` and restoring the free-cache-hit
property `FixedWindowTrigger` already had (its own `constraints` field is reassigned on every fire, so both of its
calls always agree).

### 4. An asynchronous background recovery poller, off the critical path

`BackgroundCarbonIntensityRefresher` starts polling a zone (every 30s, internal and not configurable - see below)
the moment a foreground fetch for that zone fails, and stops as soon as either it succeeds (refreshing
`LastKnownIntensityCache`) or it exhausts a total wall-clock `recoveryBudget` (default 10 minutes, a further
foreground failure restarts it). It runs on its own single daemon-thread executor and never blocks a trigger
evaluation.

**Update (post-pilot):** the total attempt budget for this poller was originally a second internal constant
(`DEFAULT_MAX_ATTEMPTS = 20`, alongside the 30s poll interval, together ~10 minutes) rather than configuration
like the five knobs above - sized against no real-world data at design time. Two independent pilot incidents (see
the addendum below) exceeded that fixed budget before the poller could observe recovery on its own, so this
attempt count was replaced with a sixth configurable knob, `carbonIntensityRecoveryBudget`: a single `Duration`
expressing the *total wall-clock time* the poller may keep trying, not a raw attempt count. `CarbonIntensityDataFetcherImpl`
converts it into an attempt count against the still-internal, still-fixed 30s poll interval via
`Math.max(1, recoveryBudget.dividedBy(pollInterval))` - always at least one attempt, even for a budget shorter than
a single poll interval - and passes both to `BackgroundCarbonIntensityRefresher`'s existing 5-arg constructor. The
poll interval itself remains an internal, non-configurable constant: unlike the retry budget (which directly bounds
how long a *trigger evaluation* can block) or the staleness threshold (which directly decides whether a job gets a
carbon-aware slot), the poll interval has no user-visible scheduling effect of its own to tune - only the *total*
time the safety net keeps trying is operationally meaningful, exactly the same reasoning that already applies to
`retryBudget` overriding `retryMaxAttempts` above. Exposing the poll interval itself, if that ever becomes
necessary, is a separate, still-open decision - see the note at the end of the addendum below about the
upstream-load guarantee that depends on it staying internal.

This exists because of `CarbonIntensityCache`'s existing one-hour negative-result TTL (`DEFAULT_TTL_EMPTY_VALUES`,
"when we get no data, we retry in one hour"): without an independent recovery path, a real outage of only a few
seconds could otherwise leave scheduling non-carbon-aware for up to an hour, purely because of that unrelated TTL.
The background poller keeps `LastKnownIntensityCache` warm regardless of when the next foreground fetch happens to
occur, so `CarbonIntensityDataFetcherImpl.fetchCarbonIntensity` can still hand back a fresh-enough last-known value
even while the period cache's negative entry is still within its TTL (see the flow in decision 2).

### 5. The retrospective carbon-impact batch is out of scope on `main` today, but designed with it in mind

The ticket's spec assumes a retrospective "actual" carbon-impact batch (CIIO-278 infrastructure,
`CarbonIntensityApiType.ACTUAL`) already exists and must never use a cached or fallback value - only ever a fresh
real reading, leaving a backlog window in place for a later attempt otherwise. That infrastructure
(`CarbonImpactBatchInvoker` and friends) lives only on an unmerged branch (`ciio-413/decision-timeline-core`,
descended from PRs #280/#281) and is not present on `main` as of this change, so there is nothing to wire up here
yet. `CarbonIntensityDataFetcherImpl` still applies the last-known-value reuse unconditionally today, since it has
exactly one caller (`SimpleScheduler`'s single, `PREDICTED`-typed instance).

Checked against that unmerged branch while preparing the pilot rebuild for this change (see below): the good news is
this requirement is satisfied by construction, not by luck. `SimpleScheduler` there constructs its
`actualCarbonIntensityApi` as a bare `new CarbonIntensityRestApi(config, CarbonIntensityApiType.ACTUAL)` and hands
it straight to `CarbonImpactBatchInvoker` - never wrapped in a `CarbonIntensityDataFetcherImpl`. Since the retry
logic lives in `CarbonIntensityRestApi` itself but the last-known-value cache and the empty/"no data" substitution
both live one layer up, in `CarbonIntensityDataFetcherImpl`, the actual/retrospective path automatically gets the
short bounded retry (a reasonable, arguably desirable behaviour there too - a transient blip shouldn't fail a
backlog attempt it doesn't need to) while automatically **not** getting cache reuse or fabricated "no data": there
is no fetcher-level cache to reuse from, and a real failure there simply propagates as an exception for
`CarbonImpactBatchInvoker` to handle by leaving the window in the backlog, exactly as required. No follow-up change
is needed here after all - only a note for whoever lands that merge to re-verify this still holds, since it
depends on that branch continuing to construct `CarbonIntensityRestApi` directly rather than switching to
`CarbonIntensityDataFetcher`.

### Configuration

All six numeric/duration knobs above (except the background poller's internal, fixed 30s poll interval) are
configurable, following the exact existing `apiKey`/`apiUrl` pattern: each extension's `GreenSchedulerProperties`/
`GreenSchedulerConfigurationProperties` to `SchedulerConfigBuilder`/`GreenSchedulerFactory` to
`CarbonIntensityApiConfig.Builder` (execution-planner). Every field defaults to `null` at the properties/builder
level (mirroring `apiKey`, not `apiUrl`'s hardcoded default) and `CarbonIntensityApiConfig` alone owns the actual
default values (`DEFAULT_RETRY_MAX_ATTEMPTS = 3`, `DEFAULT_RETRY_INITIAL_BACKOFF = 300ms`,
`DEFAULT_RETRY_BACKOFF_MULTIPLIER = 3.0`, `DEFAULT_RETRY_BUDGET = 2s`, `DEFAULT_STALENESS_THRESHOLD = 4h`,
`DEFAULT_RECOVERY_BUDGET = 10m`) - this keeps a single source of truth rather than duplicating the six literal
defaults across `core` and three extensions. This is not because deployments are expected to want different values
in practice, but so a deployment *can* tune them from outside without a code change. Unlike the other five,
`recoveryBudget` additionally enforces a genuine lower bound (it must be positive) rather than only `notNull`, at
both `CarbonIntensityApiConfig.Builder` and each extension's `SchedulerConfigBuilder`/`GreenSchedulerFactory`
level - a zero or negative budget would be a configuration mistake worth failing fast on, rather than silently
falling through to the `Math.max(1, ...)` attempt-count floor described above.

## Deviations from the original spec

The grilled resolution on CIIO-470 (both comments) is written as if `DecisionReason`/a decision-timeline concept
(with `GREENEST_AVAILABLE_SLOT` and `FALLBACK_TO_PLAIN_INTERVAL` reasons) and the CIIO-278 carbon-impact-batch
infrastructure already exist on `main`. Neither does: both live only on the unmerged
`ciio-413/decision-timeline-core` branch (itself descended from PRs #280 and #281, referenced in the pilot's
"local integration snapshot" recipe). This implementation branches from a fresh `origin/main` as instructed, so:

- No new `DecisionReason` is added anywhere (there is nothing to add it to on `main`); the ticket's "no new reason
  needed" instruction is honored by construction rather than by choice.
- Decision 5 above (the retrospective batch) is a forward-looking design note, not a code change, since the code
  it would change does not exist yet on this branch.
- When `ciio-413/decision-timeline-core` (or its successor) merges into `main`, whoever does that merge should
  confirm `GREENEST_AVAILABLE_SLOT` is the reason recorded for a within-staleness-threshold last-known-value reuse,
  and `FALLBACK_TO_PLAIN_INTERVAL` for the `canSchedule() == false` path - both should already fall out naturally
  from the existing decision points this change touches, but that needs eyes once the taxonomy exists to check
  against.

## Consequences

A live outage of the CarbonIntensity API - from a few seconds to several hours - now degrades through three
honestly-labeled stages (fresh live data, then a still-genuine but aging last-known value, then the scheduler's
own always-available non-carbon-aware fallback) instead of silently substituting numbers that look precise but
are fabricated. The `impl` package's `CarbonIntensityDataFetcherImpl(CarbonIntensityApi, CarbonIntensityApi)`
constructor is deprecated rather than deleted (per this project's "don't break public API methods" convention)
even though its `fallbackApi` parameter is now inert - keeping the removed fallback semantics alive behind that
constructor would directly contradict the point of this change, so it logs a warning and ignores the parameter
instead. Two Quarkus/Micronaut test doubles that used to delegate to `CarbonIntensityFileApi` for convenience now
return fixed in-memory data directly. `core`'s `TestFixedWindowScheduler` and `TestSuccessiveWindowScheduler` had
both come to rely on the bundled fallback dataset's specific values as an implicit "which hour is greenest" test
oracle (their default `CarbonIntensityApi` fixture, previously the misleadingly-swapped-in
`DisabledDummyCarbonIntensityApi`, actually resolved to `CarbonIntensityFileApi` under the old
`CarbonIntensityDataFetcherImpl`, since that constructor swapped to the fallback API whenever `isEnabled()` was
false); they now use a dedicated, explicitly-test-only `FixtureCarbonIntensityApi` backed by the same values, moved
to `src/test/resources` - a legitimate deterministic test fixture, no longer reachable from production code or
mistakable for one. `DisabledDummyCarbonIntensityApi` itself (still used by `TestFallbackProgrammatic`/
`TestDefaultCarbonIntensityProgrammatic`, which only need "no data at all", not specific values) now returns a
properly failed `CompletableFuture` instead of `null`, since `null` was only ever safe under the old
fallback-swapping constructor.

## Addendum: the full timing picture (added after a week of pilot observation)

This decision introduces or touches six independent timing constants across the scheduler and this mechanism.
They solve six different problems and were designed one at a time in the sections above; this table exists so a
future reader (or a future incident) doesn't mistake one for a duplicate of another, or "fix" one by squinting at
a symptom that actually belongs to a different one:

| # | Mechanism | Value | Problem it solves |
|---|---|---|---|
| 1 | `SimpleScheduler.CHECK_PERIOD` | 1 second | The scheduler's own tick - how often any job's trigger gets (re-)evaluated at all. Predates this ADR entirely; nothing carbon-specific about it. |
| 2 | `CarbonIntensityCache` (period-keyed), positive hit | until `value.getEnd()` | The original "fetch from ENTSO-E about once an hour" behaviour - the cache key truncates to the hour, so a real HTTP call happens at most once per hour per zone actually queried. Untouched by this ADR. |
| 3 | `CarbonIntensityCache`, negative ("no data") hit | `DEFAULT_TTL_EMPTY_VALUES` = 1 hour | "If we got nothing, try again in an hour" - a separate, deliberately conservative TTL for the empty case specifically. |
| 4 | `CarbonIntensityRestApi` retry (critical path, synchronous) | `retryMaxAttempts`=3, 300ms/900ms backoff, hard `retryBudget`=2s | How long a scheduler tick may block on a network hiccup, bounded because `checkTriggers()` iterates serially on a shared 2-thread pool. |
| 5 | `LastKnownIntensityCache` staleness | `stalenessThreshold` = 4h | How old a real, previously-fetched value may be and still count as an honest carbon-aware decision. |
| 6 | `BackgroundCarbonIntensityRefresher` (async, off critical path) | poll every 30s (internal, fixed), budget configurable via `carbonIntensityRecoveryBudget`, default ~10 min | How persistently the off-critical-path recovery poller tries before giving up, so mechanism 3's one-hour negative TTL doesn't let a short outage degrade scheduling for longer than necessary. |

Row 6 was originally the only one of the six with an internal, non-configurable constant instead of a
five-knob-pattern config field (see "Configuration" above) - its ~10-minute total budget was sized against no
real-world data at design time. Two independent, unrelated outages during the pilot's first week exceeded that
fixed budget before the background poller could observe recovery on its own (instead, recovery only happened once
some later, unrelated foreground fetch happened to succeed):

- A recurring day-rollover gap (~40 minutes, three zones simultaneously) around local midnight, where the
  upstream API had not yet published the new day's data - each foreground call failed with a real `404`, not a
  timeout, correctly not retried on the critical path (see decision 1), but the background poller gave up around
  the 10-minute mark, well before the data appeared.
- A ~20-minute run of `ConnectException: Network is unreachable` correlated with the laptop entering
  `DarkWake`/clamshell sleep (`powerd` logs), the same root cause that originally motivated this whole ADR.

As a result, row 6's *budget* is now a sixth configurable knob, `carbonIntensityRecoveryBudget` (see
"Configuration" above and the "Update (post-pilot)" note in decision 4) - it is no longer "fully internal". Its
30s poll interval, however, deliberately remains internal and fixed: this mechanism's guarantee of no effect on
shared upstream load (at most one recovery request roughly every 30s per zone being recovered, regardless of how
large the configured budget is) depends specifically on the poll interval, not the budget, and was only ever
computed against a fixed 30s value. A later change that also makes the poll interval itself configurable must
re-derive and re-check that upstream-load guarantee - it does not automatically carry over.
