# 1. Compatibility testing strategy

## Status

Accepted

## Context

Issue #199 showed us a failure mode our regular build simply can't catch: the published
`quarkus-green-scheduler-deployment` jar, compiled against one Quarkus version, threw a
`NoSuchMethodError` when it ran as a build-time plugin inside a consumer's project on a *different*
Quarkus version. `SchedulerProcessor` called `AnnotationProxyProvider$AnnotationProxyBuilder.build`
against a signature that had moved out from under us as Quarkus's ArC moved from Gizmo 1 to Gizmo
2. Nothing in our own build ever exercised that path, because our build always compiles the
deployment module against whatever Quarkus version is currently pinned - it never sees the mismatch
a real consumer sees.

That's a binary compatibility problem, not a source compatibility one. If we "test" it by
recompiling green-scheduler against the newer Quarkus version, the incompatibility disappears,
because now everything is built consistently again - which is exactly what a real consumer,
sitting on our already-published jar, cannot do. Any compatibility check that rebuilds from source
against the target version would have missed #199 entirely. That constraint shaped most of the
decisions below.

## Decision

### A matrix, built around lifecycle role rather than "the last N versions"

We test specific framework lines, chosen by what role they play in the framework's own release
lifecycle, not by counting back from the newest release:

- **Quarkus**: every currently active LTS line, plus the newest non-LTS ("latest stable") line as
  a canary.
- **Spring Boot**: every still-supported line of the current major, plus the last line of the
  previous major (people don't jump majors overnight - that line stays relevant well past its own
  end-of-life date).

"Last N releases" sounds simpler, but Quarkus alone ships a new minor roughly every four weeks. Take
the last three releases today and in a few months you'd be testing lines that are already end of
life while missing the LTS line half our users are actually running - the one line that would
really matter if it broke. Anchoring to lifecycle role instead of a release count means the matrix
stays meaningful without us having to remember to update it by hand every quarter. `compatibility/resolve-matrix`
recomputes the actual version numbers from each framework's release-lifecycle feed on every run;
`compatibility/policy.yaml` only encodes the *rule*, never a version number.

### Why the pinned version isn't in required or canary

The version actually pinned in the extension poms (currently Quarkus 3.38.1, Spring Boot 3.5.14)
never shows up in `quarkus_required_entries`/`quarkus_canary_entries` or their Spring Boot
equivalents - it isn't a lifecycle-selected line at all. Quarkus 3.38 was never an LTS line, was
never the "latest stable" canary line either, and by the time you read this it's likely EOL in its
own right (Quarkus 3.38 went EOL on 2026-08-26) - it doesn't fit either category, on purpose.

That's not a gap, because the pinned combination is already covered two other ways. `build.yml`
compiles the deployment module against exactly this version on every single build - a regression
there fails the regular build with no need for the compatibility matrix at all. And
`compatibility-pr.yml`'s `pr_matrix()` includes it explicitly and separately from the lifecycle
selection, precisely so every PR gets one consumer-like build against exactly what we'd actually
ship if this PR were released today.

The required/canary tiers exist for a different job: catching drift after a release, when a
framework version we didn't rebuild against changes underneath us. Feeding the pinned version into
that same rotation would test nothing new - by definition, "what we're compiling against right
now" cannot have drifted since itself.

The pin itself is the day-to-day development target, nothing more - the version `build.yml`
compiles and tests against on every single commit. That's a different thing from "the floor we
guarantee," which is what the required tier is for. The two axes really are independent: the
current pin (Quarkus 3.38) was never itself an LTS line, was never the newest stable line either,
and is by now EOL - none of that has any bearing on which lines are required.

As a rule of thumb, the pin should track the active LTS line or the newest stable Quarkus/Spring
Boot release, and should never be left sitting on a line that's gone EOL. What actually triggers
bumping it: routine upkeep (not developing against something already EOL), a newer framework
feature or API the extension needs, or a security fix that only landed in a newer release.

One warning worth recording, because it already happened once: Quarkus and Spring Boot bumps are
deliberately excluded from dependabot's auto-merge (see `dependabot-auto-merge.yml`) - a framework
bump can itself cause a #199/#229-style break, so it always needs a deliberate, human look. In
practice, that look didn't happen for months: a dozen or so proposed `quarkus.version` bumps
between March and June sat closed, one after another, each superseded by the next one dependabot
opened, none ever merged - the pin stayed put the whole time until it finally moved by hand, as
part of unrelated work (#209). Keeping the pin current isn't something that happens on its own; it
needs an active, recurring habit, not an assumption that someone will get to it.

### Required and canary tiers

Not every line we test deserves to block anything. A line goes in the `required` tier once we've
decided we support it for real - failing there blocks the PR check and opens an issue immediately
from the monitor. A line starts in the `canary` tier when it's too new for us to have made that
call yet: canary failures don't block a PR, and the monitor only opens an issue after the *same*
key fails on two consecutive scheduled runs, so a broken milestone or RC build doesn't turn into
noise for something nobody uses in production yet.

A brand-new major version defaults to canary for exactly this reason - not because we expect it to
break, but because "required" is a statement that we've verified it and are committing to it. Spring
Boot 4.0 and 4.1 are the current exception: we already went through that verification (context
loads, autoconfiguration and `@ConstructorBinding`/`@Validated` property binding all work) and
decided to support them, so they're required from day one rather than sitting in canary first. That
verification is a deliberate, recorded decision, not something resolve-matrix infers on its own -
see "Maintaining this" below for how that promotion actually happens.

### How the required-tier floor moves - and how it never does

Before this matrix existed, there was no explicit floor at all - "supported" meant whatever
version happened to be pinned at the time, never a decision anyone had actually made. Issue #229
is the proof: the #199 fix (#209) broke support for Quarkus 3.33 LTS without anyone deciding that,
or even noticing, until the required-tier check caught it.

A line can only leave the required tier two ways, and both are explicit:

1. It goes EOL at the vendor - the `eol_date` from the official release data passes. That's
   calendar-driven and known ahead of time, so it doesn't need a separate team decision each time
   it happens.
2. A deliberate, reviewed change to `compatibility/policy.yaml`.

A silent regression is never a valid third way out. If a required line breaks because of a code
change, it stays required, the monitor keeps showing it red, and the alert issue stays open until
a human either fixes the code or deliberately edits the policy to drop the line. #229/#231 is the
concrete example: the mechanism surfaced a silent regression instead of quietly letting it stand,
and the fix - not a policy change - is what closed it out.

### Two workflows, not one

`compatibility-pr.yml` and `compatibility-monitor.yml` exist separately because they're answering
different questions and need different baselines to do it:

- The PR workflow asks "did this change break something that used to work?" - which needs a
  before/after comparison against the PR's base branch. A framework line that already fails on
  `main` isn't this PR's fault, and blocking on it would just be noise; a line that passes on
  `main` and fails on the PR head is a real regression. That comparison only makes sense scoped to
  a single PR.
- The monitor asks a different question: "does anything we've already shipped break against a
  framework version that showed up since we shipped it?" There's no PR to compare against here -
  it runs on a schedule, independent of any change to this repo.

Splitting them also keeps the PR check fast (a light subset: the pinned combination plus the
newest required line per framework) while letting the monitor run the full required+canary matrix
without holding up anyone's PR.

### The monitor tests the published artifact, never `main`

This is the direct lesson from #199: testing against a rebuild from `main` cannot reproduce a
binary incompatibility in an already-published jar, because rebuilding removes the very version
mismatch that caused the problem. So `compatibility-monitor.yml` resolves whatever is actually on
Maven Central right now and points the consumer apps at that. Only when a combination fails does it
also build `main` as a diagnostic, purely to tell us which of two situations we're in: main is
already fixed (so what's needed is a patch release) or main is broken the same way (so there's
still work to do). That distinction shows up directly in the issue body.

### Issue key is the minor line, not the patch

`compatibility/report-failure` de-duplicates and tracks issues by `framework:line:tier` (for
example `quarkus:3.33:required`), never by the exact patch version. A new patch release on a line
we're already tracking should update the existing issue, not spawn a second one - patch releases
on an LTS line come out for months after the line itself does, and treating each one as a fresh
incident would bury the actual signal.

### Micronaut: deliberately not included yet

The resolver is written with a per-framework adapter (`compatibility/policy.yaml`'s
`frameworks.<name>.adapter`, plus a matching resolver function and a `compatibility-tests/<name>-app`
directory) specifically so a third framework is additive rather than a rewrite. Micronaut isn't
one of those adapters yet - that's a separate decision the team hasn't made, not an oversight. See
"Maintaining this" below for what bringing it in would actually involve, since some of the
groundwork has already been thought through.

**Update (CIIO-250):** Micronaut has since been added, entirely in the canary tier - see
`compatibility/policy.yaml`'s `frameworks.micronaut` block and the `resolve_micronaut`-style
adapter functions in `compatibility/resolve-matrix`. The two things this section already flagged
turned out to matter, but not quite as first assumed: the adapter uses "every major not yet EOL"
per endoflife.date's own `eol` field, not a fixed "current plus previous major" count - the first
version of this adapter hardcoded that count and it silently dropped Micronaut 3, which
endoflife.date still lists as non-EOL and which kept receiving patch releases (3.10.10, July 2026)
more than three years after 4.0 shipped. The adapter also resolves its own Java version per entry
(majors 3 and 4 on 17, major 5 on 25) rather than crossing against the shared `java-versions` axis.
Promoting a line to required still needs the same explicit, verified decision described under
"Maintaining this" below.

## Maintaining this

Changing tier assignments, promoting a freshly released major to required, or adding a third
framework (Micronaut is the concrete case we've already scoped out) are all practical, hands-on
changes rather than architectural ones - they don't need a new ADR. `compatibility/README.md`
walks through all three with the exact files to touch; the short version is that
`compatibility/policy.yaml` never encodes a version number, only *rules*, so promoting a line is a
policy edit, not a code change, and the per-framework adapter structure in `resolve-matrix` is what
makes adding Micronaut additive rather than a rewrite.

## Consequences

The matrix has real infrastructure cost: two workflows, a shared `compatibility/` toolset, and
consumer apps to keep in sync with whatever the extensions' public API looks like at any given
time. In exchange, we get a repeatable answer to "does this still work on what people are actually
running" instead of finding out from a bug report - which is exactly how we found out about #199 in
the first place.
