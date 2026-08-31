---
name: verify-change
description: Verify a pending change to green-scheduler before opening a pull request - runs the minimal affected-module build first, then the full canonical `verify`, checks the change has adequate test coverage, and drafts the "How I verified this" text for the PR template. Use when asked to verify, check, or validate a change is ready for a PR, or before submitting a PR in this repo.
---

# Verify a green-scheduler change

Use this before opening a pull request against `carbonintensityio/green-scheduler`, or whenever asked to check that a change is ready to submit.

## Background: module map

The reactor is a single Maven multi-module build under `${revision}` (currently `999-SNAPSHOT` locally), flattened by `flatten-maven-plugin` (`resolveCiFriendliesOnly`). Sibling modules depend on each other via `${revision}`/`${project.version}`, not a fixed released version, so they generally aren't already sitting in the local `.m2` repo with a matching version - the reactor has to build them. That's why `-am` ("also make") is required whenever you build a subset with `-pl`: without it, Maven can fail to resolve an in-repo dependency that isn't installed locally yet.

Path prefix → module → artifactId:

| Path prefix | Module (`-pl`) | artifactId |
|---|---|---|
| `core/` | `core` | `green-scheduler-core` |
| `execution-planner/` | `execution-planner` | `execution-planner` |
| `extensions/spring-boot-starter/` | `extensions/spring-boot-starter` | `green-scheduler-spring-boot-starter` |
| `extensions/quarkus/deployment/` | `extensions/quarkus/deployment` | `quarkus-green-scheduler-deployment` |
| `extensions/quarkus/runtime/` | `extensions/quarkus/runtime` | `quarkus-green-scheduler` |
| `extensions/quarkus/runtime-dev/` | `extensions/quarkus/runtime-dev` | `quarkus-green-scheduler-dev` |
| `integration-tests/` | `integration-tests` | `scheduler-integration-tests-parent` (currently a placeholder parent pom with no submodules - if this is the only thing touched, skip step 3 and go straight to the full verify) |

Dependency direction to know when picking `-pl`: `extensions/spring-boot-starter` and `extensions/quarkus/runtime` both depend on `core`. `extensions/quarkus/deployment` and `extensions/quarkus/runtime-dev` depend on `extensions/quarkus/runtime`. `execution-planner` has no internal module dependencies. Anything under `support-projects/`, `bom/`, `build-parent/`, root `pom.xml`, or `.github/` is build infrastructure - treat it as reactor-wide and skip straight to the full verify (step 4) rather than guessing a module subset.

`compatibility-tests/` and `compatibility/` are deliberately **not** part of the reactor (no `-pl` target applies, and running the full reactor `verify` proves nothing about them - see the comment at the top of `compatibility-tests/pom.xml` for why they're kept separate). If a change touches either:

- `compatibility-tests/quarkus-app/` or `compatibility-tests/spring-boot-app/` - verify with `./mvnw -Dquickly install` from the repo root (to get a `999-SNAPSHOT` of whatever extension the app depends on into the local repo) followed by `./mvnw -f compatibility-tests/pom.xml verify`.
- `compatibility/policy.yaml`, `compatibility/resolve-matrix`, `compatibility/test-consumer`, or `compatibility/report-failure` - `resolve-matrix` can be exercised offline against `compatibility/fixtures/<date>/` (see `compatibility/README.md`); `test-consumer` and `report-failure` are otherwise best checked by re-running the `Compatibility (PR)` workflow on the branch, since they're mostly thin wrappers around `mvnw`/`gh`.

Treat these two as their own verification track, not as "reactor-wide" - lumping them into step 4's full verify would silently skip the thing that actually changed.

## Steps

1. **Find what changed.** Run `git diff --name-only <base-branch>...HEAD` (use `main` as the base unless told otherwise) to get the list of changed files.

2. **Map paths to modules.** For each changed path, match it against the table above using the longest matching prefix (e.g. a file under `extensions/quarkus/runtime/` maps to `extensions/quarkus/runtime`, not the parent `extensions/quarkus`). Collect the distinct set of affected modules. If any changed path falls outside all known modules (build infra, root files, `docs/`, `.github/` workflow files), note that and treat the change as reactor-wide.

3. **Run the minimal relevant subset first.** With the affected modules from step 2, run:

   ```bash
   ./mvnw -Dno-format -pl <module1>,<module2>,... -am test
   ```

   This gives fast feedback on the modules that actually changed (and whatever they depend on) before paying for a full reactor build. Skip this step only when the change is reactor-wide (per step 2) - go directly to step 4 instead.

4. **Run the full canonical verification.** Regardless of how step 3 went, run the same command CI and CONTRIBUTING.md treat as canonical:

   ```bash
   ./mvnw -B --settings .github/mvn-settings.xml -Dno-format verify
   ```

   This is the command documented in CONTRIBUTING.md's Build section and is what `./mvnw -Dquickly` deliberately does *not* cover (it skips tests, integration tests, and the enforcer's version checks) - don't substitute `-Dquickly` for this step. If CONTRIBUTING.md's documented command ever changes, use whatever it says instead of what's written here.

5. **Check test coverage heuristics.** Before drafting the verification text, sanity-check the diff against the project's "tests are not optional" rule:
   - A bug fix should come with a test that demonstrably fails on the pre-fix code and passes after (if you can, check this out by temporarily reverting just the fix and confirming the new test fails).
   - A new public configuration property (an `@ConfigProperty`/`@ConfigMapping` field, a Spring `@ConfigurationProperties` field, a new Quarkus build-time config item) needs a binding/wiring test in whichever extension exposes it (`extensions/spring-boot-starter` or `extensions/quarkus/*`), not just a unit test on the core POJO.
   - A new public API method/class in `core` or `execution-planner` needs a direct test, not just incidental coverage through an extension's integration test.
   - If none of these apply (pure refactor, docs, build config), say so explicitly rather than leaving the verification text vague.

6. **Draft the PR template's "How I verified this" text.** Using the results of steps 3-5, write the exact text for that field. Be concrete about commands and what they proved, for example:

   ```
   - `./mvnw -Dno-format -pl extensions/quarkus/runtime,extensions/quarkus/deployment -am test` - green, confirms the new config property is picked up by the Quarkus build step.
   - `./mvnw -B --settings .github/mvn-settings.xml -Dno-format verify` - full reactor build green, including integration tests.
   - Added `QuarkusRuntimeConfigTest#disablesSchedulerWhenPropertyUnset` to cover the new property; ran it against the pre-fix code to confirm it fails without the change.
   ```

   Paste the equivalent for the actual change into the PR's "How I verified this" section - don't leave it as a template placeholder.

## Non-goals

- This skill doesn't run formatting (`./mvnw process-sources`) or fix findings - it verifies and reports. If step 3 or 4 fails, fix the underlying issue and re-run rather than skipping ahead.
- It doesn't decide whether a change needs a changelog label or version bump - that's unrelated to `.github/project.yml`'s release flow.
