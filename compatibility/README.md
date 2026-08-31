# compatibility/

Tooling behind the compatibility matrix that checks whether green-scheduler still works on newer
Java/Quarkus/Spring Boot versions. The design reasoning lives in
`docs/adr/0001-compatibility-testing-strategy.md`; this file is the practical "how do I actually
change something" companion to that.

- `policy.yaml` - which framework lines are required vs. canary, and where their release data
  comes from. Never contains a version number.
- `resolve-matrix` - reads `policy.yaml` plus live (or fixture) release data and prints the
  resolved test matrix as JSON. Run `compatibility/resolve-matrix --help`-style usage by just
  calling it with no arguments, or see the header comment in the script.
- `test-consumer` - builds and runs one `compatibility-tests/<module>` app against a single
  framework/version combination.
- `report-failure` - opens, updates or closes the GitHub issue tracking one
  `framework:line:tier` key.

Try it locally with recorded data instead of hitting the network:

```shell
compatibility/resolve-matrix required --fixture compatibility/fixtures/2026-08-29
```

## Changing which lines are required vs. canary

Tier assignment lives entirely in `policy.yaml`, under each framework's `selection` block - it
names a rule (`active-lts-lines`, `latest-stable-non-lts-line`, and so on), never a version number.
Moving a line from canary to required (or back) means changing which rule feeds which tier for
that framework, then running `compatibility/resolve-matrix all` locally to see the effect before
it goes out in a PR.

Do this once the team has actually decided to support a line - not just because resolve-matrix
started returning it. Spring Boot 4.0/4.1 is the template: someone verified the extension against
the new line first (context loads, autoconfiguration and `@ConstructorBinding`/`@Validated`
property binding all work), *then* the policy changed to mark it required. "The canary check has
been green a few times" is a useful signal that a line is ready to look at, but it isn't the same
as having actually verified it - the canary tier exists so a line can prove itself over several
scheduled runs before anyone commits to it.

## Adding a third framework (the Micronaut case)

Micronaut is the concrete example we've already scoped out but not built, so here's what bringing
it in would actually take:

1. A new `frameworks.micronaut` block in `policy.yaml`, with its own `sources` (a
   release-lifecycle feed, if Micronaut publishes one usable the way Quarkus's `releases.yaml` and
   Spring Boot's endoflife.date entry are) and its own `selection` rules for required/canary.
2. A `compatibility-tests/micronaut-app` directory, structured the same way as `quarkus-app` and
   `spring-boot-app` under `compatibility-tests/`: a standalone pom (no `<parent>` from the main
   reactor - see the comment at the top of `compatibility-tests/pom.xml` for why), at least one
   `@GreenScheduled` job registered with `green-scheduler.start-mode=HALTED` and an unreachable
   `api-url` so the check stays deterministic and offline, and a test asserting the scheduler knows
   about that job (the same shape as `CompatibilityJobTest` in the other two apps).
3. A `resolve_micronaut` adapter function in `resolve-matrix`, following the same shape as
   `resolve_quarkus`/`resolve_spring_boot`. `test-consumer` and `report-failure` need no
   framework-specific changes at all - both are already generic over `framework`/`module`, so this
   step is the only piece of resolver logic that's actually new.

Two Micronaut-specific things worth knowing before starting, so this doesn't have to be
rediscovered from scratch:

- Micronaut's version lines aren't labelled LTS the way Quarkus's are, and its support policy is
  major-version based rather than per-minor-line - so the `active-lts-lines`-style rule doesn't
  translate directly; the adapter will need its own selection logic (something closer to "the
  current major and the previous one," similar to the Spring Boot adapter).
- Micronaut 5 has a hard floor of Java 25, while Micronaut 4 still runs on Java 17. The fixed
  `java-versions: [17, 25]` axis in `policy.yaml` that every other framework crosses against won't
  work here unmodified - the Micronaut adapter needs to pick the Java version per major rather than
  testing every line against the same two JDKs (a Micronaut 4 entry tested only on 17, a Micronaut
  5 entry tested only on 25, for example).

## Promoting a freshly released major from canary to required

Before flipping a new major's tier in `policy.yaml`, check it the same way we checked Spring Boot
4: does the extension's context/augmentation still work against it, do properties still bind the
way consumers expect, and has the canary check actually been green across more than one scheduled
run rather than just once. Once that's settled, the policy change itself is small - move the
line's selection rule into `required` and the PR workflow's "newest required line" logic picks it
up automatically from there.
