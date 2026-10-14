# 4. Mutation testing with PIT: local and manual, no CI gate (yet)

## Status

Accepted

## Context

Line coverage only proves a line *ran*, not that a test would fail if its logic broke. Several classes
here had high line coverage while their tests asserted little or nothing about the behaviour they
covered - a test calling a method without checking its return value, or never placing a value exactly
on a `<=`/`<` boundary. Mutation testing makes that visible: it injects small faults ("mutants") and
checks whether the test suite notices.

That raised two questions: which tool, and whether a mutation score should gate CI the way the build
and the PMD/Checkstyle ratchet already do.

## Decision

We use **PIT** (`pitest-maven` with `pitest-junit5-plugin`) as a **local, manual diagnostic only**:

- Configured in `core/pom.xml` and `execution-planner/pom.xml`, scoped with `targetClasses` to the
  packages where logic errors are most costly (scheduler runtime, planners, strategies) rather than
  whole modules.
- Not bound to any lifecycle phase: `mvn verify` and CI never run it. Developers run it explicitly
  (see CONTRIBUTING.md, "Mutation testing").
- No `mutationThreshold`/`coverageThreshold`: nothing fails on a score.
- Versions pinned in the root `pom.xml` (`pitest-maven` 1.30.0, `pitest-junit5-plugin` 1.2.3), because
  PIT's coverage mapping has had version-specific breakage with bytecode-instrumenting tools such as
  JaCoCo, fixed only in recent releases.

### Why not a CI gate

- **No baseline yet.** A threshold only means something relative to a known starting point. The first
  measurement (below) is the baseline; a gate is a separate decision to make once that has been
  observed over a few changes, just as the coverage gate could only be set once coverage measurement
  itself was reliable.
- **Fragile tooling.** `pitest-junit5-plugin` shades an older JUnit Platform; an open upstream issue
  reports PIT silently finding 0 tests (0% coverage) when a project moves to the JUnit 6 platform. We
  are on JUnit 5.14 today, but a dependency bump could turn a gate into a false failure - or a false
  pass - without any code change.
- **`core` takes far too long for a CI job.** `execution-planner` finishes in 15 seconds, but `core`
  takes about 35 minutes (see the baseline below). Its tests start real schedulers and wait on
  real time (Awaitility timeouts, 1000-iteration property tests), so PIT's coverage analysis alone takes
  ~40 s instead of ~1 s, and every mutant that makes such a test hang has to run into PIT's timeout
  before it is counted. Those `TIMED_OUT` mutants count as killed, which also makes the score less
  trustworthy: a hang is not the same as a test that detected the fault.
- **A hard threshold invites gaming:** tests written to kill mutants rather than to pin down behaviour.

### Baseline (first measurement)

| Module | Mutants | Killed | Test strength | PIT run time |
|---|---|---|---|---|
| `execution-planner` | 136 | 103 (76%) | 84% | 15 s |
| `core` | 344 | 259 (75%), 8 of them by timeout | 87% | 34 min 40 s |

### Considered options

- **PIT, local only** - chosen, for the reasons above.
- **PIT as a CI gate on whole modules** - rejected for now: no baseline, tooling fragility.
- **PIT as a gate on changed classes only** - the likely next step. PIT's ready-made git/PR
  integration is part of the commercial Arcmutate extension; without it, CI would derive
  `targetClasses` from the diff itself.
- **Arcmutate** (commercial PIT extension) - not needed for this scope.
- **Stryker** - its JVM variant (Stryker4s) targets Scala only.
- **Older JVM mutation testers** (Jumble, Jester, Major) - unmaintained.

## Revisit when

- the baseline above has been re-measured after a handful of changes, so a realistic threshold is
  known; and
- the PIT run time for the scoped classes fits in a CI job - for `core` that means first making its
  real-time tests cheap under mutation (an injectable clock instead of waiting, a lower PIT
  `timeoutConstant`, or excluding the slowest lifecycle tests from `targetTests`); and
- `pitest-junit5-plugin` supports the JUnit Platform version in use.

Then the first step is a **non-blocking** CI job (or a scheduled run on `main`, like the compatibility
monitor) that publishes the report, before any blocking threshold.
