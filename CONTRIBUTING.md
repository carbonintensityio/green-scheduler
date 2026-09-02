# Contributing guide

**Want to contribute? Great!**
We try to make it easy, and all contributions, even the smaller ones, are more than welcome. This includes bug reports,
fixes, documentation, examples... But first, read this page (including the small print at the end).

- [Legal](#legal)
- [Reporting an issue](#reporting-an-issue)
- [Before you contribute](#before-you-contribute)
    * [Code reviews](#code-reviews)
    * [Coding Guidelines](#coding-guidelines)
    * [Logging Guidelines](#logging-guidelines)
    * [Continuous Integration](#continuous-integration)
    * [Tests and documentation are not optional](#tests-and-documentation-are-not-optional)
- [Setup](#setup)
    * [IDE Config and Code Style](#ide-config-and-code-style)
        + [Eclipse Setup](#eclipse-setup)
        + [IDEA Setup](#idea-setup)
- [Build](#build)
- [Release your own version](#release-your-own-version)
- [Usage](#usage)
- [The small print](#the-small-print)

## Legal

All original contributions to the scheduler are licensed under the
[ASL - Apache License](https://www.apache.org/licenses/LICENSE-2.0), version 2.0 or later, or, if another license is
specified as governing the file or directory being modified, such other license.

All contributions are subject to the [Developer Certificate of Origin (DCO)](https://developercertificate.org/). The DCO
text is also included verbatim in the [dco.txt](dco.txt) file in the root directory of the repository.

## Reporting an issue

This project uses GitHub issues to manage the issues. Open an issue directly in GitHub.

If you believe you found a bug, and it's likely possible, please indicate a way to reproduce it, what you are seeing and
what you would expect to see. Don't forget to indicate your scheduler and Java version.

## Before you contribute

To contribute, use GitHub Pull Requests, from your **own** fork.

Also, make sure you have set up your Git authorship correctly:

```sh
git config --global user.name "Your Full Name"
git config --global user.email your.email@example.com
```

If you use different computers to contribute, please make sure the name is the same on all your computers.

We use this information to acknowledge your contributions in release announcements.

### Code reviews

All submissions, including submissions by project members, need to be reviewed by at least one committer before
being merged.

[GitHub Pull Request Review Process](https://docs.github.com/en/pull-requests/collaborating-with-pull-requests/reviewing-changes-in-pull-requests/about-pull-request-reviews)
is followed for every pull request.

### Coding Guidelines

* We decided to disallow `@author` tags in the Javadoc: they are hard to maintain, especially in a very active project,
  and we use the Git history to track authorship. GitHub also
  has [this nice page with your contributions](https://github.com/carbonintensityio/green-scheduler/graphs/contributors). For each major
  scheduler release, we also publish the list of contributors in the announcement post.
* Commits should be atomic and semantic. Please properly squash your pull requests before submitting them. Fixup commits
  can be used temporarily during the review process but things should be squashed at the end to have meaningful commits.
  We use merge commits so the GitHub Merge button cannot do that for us. If you don't know how to do that, just ask in
  your pull request, we will be happy to help!
* Please limit the use of lambdas and streams as much as possible in code that executes at runtime, in order to minimize runtime footprint.
* Code is easy to follow and doesn’t contain commented-out code 
* No obvious duplication or dead code
* Don't break public API methods in a refactor. Deprecate them and keep the old ones working instead, even if we don't
  have confirmed external users for that particular method yet - we'd rather be conservative here.
* Give parameters and test names an actual meaning: no one-letter parameter names, and test method names should describe
  the functional scenario (or match the class under test) rather than just restating the method being tested, e.g.
  `testSchedulerAnnotationsAreDiscovered` instead of `testGet`.
* When we depend on an external library (ShedLock, for example), don't duplicate its documentation in our own README -
  link to the official docs instead. That way our documentation only changes when we actually change something.
* Don't reinvent something the underlying framework already provides, and don't duplicate logic between the Quarkus and
  Spring Boot extension modules - if both need it, it belongs in a shared place.
* Builder methods shouldn't silently depend on each other's call order (e.g. a `withEnd()` that assumes `withStart()`
  was called first). If two builder calls are really one concept, combine them into a single method instead.

### Logging Guidelines
* All log entries must be made using the SLF4J logger - including in tests, never `System.out`/`println`.
* Never log sensitive data, including personally identifiable information (PII) protected under GDPR, passwords,
  authentication tokens, or any other confidential information.
* When behavior depends on which implementation or strategy is active, log that choice so it's visible to whoever's
  running the scheduler, instead of something they have to infer from configuration.
Be mindful of the performance cost of logging:
* Don't over-log: Avoid logging in tight loops or in situations that would produce excessive output.
* Log errors only once and avoid redundant logging.
* Use the appropriate log levels:
   * `DEBUG` – Internal details such as inputs or computed values.
   * `INFO` – Key events like "Scheduler started" or "Job completed".
   * `WARN` – Recoverable issues or unexpected conditions.
   * `ERROR` – Unrecoverable failures requiring investigation.

### Continuous Integration

Because we are all humans, and to ensure the scheduler is stable for everyone, all changes must go through the scheduler continuous
integration. The scheduler CI is based on GitHub Actions, which means that everyone has the ability to automatically execute
CI in their forks as part of the process of making changes. We ask that all non-trivial changes go through this process,
so that the contributor gets immediate feedback, while at the same time keeping our CI fast and healthy for everyone.

The process requires only one additional step to enable Actions on your fork (clicking the green button in the actions
tab). [See the full video walkthrough](https://youtu.be/egqbx-Q-Cbg) for more details on how to do this.

To keep the caching of non-scheduler artifacts efficient (speeding up CI), you should occasionally sync the `main` branch
of your fork with `main` of this repo (e.g. monthly).

A merge to `main` also triggers a full build, not just pull requests, so you can expect `main` to always reflect an
actually-verified state.

### Compatibility testing

Next to the regular build, every PR also runs a `Compatibility (PR)` workflow. It builds the
extensions from your branch and boots two small consumer apps under `compatibility-tests/` (one
Quarkus, one Spring Boot) against the currently pinned framework versions plus the newest actively
supported line of each - on JDK 17, and JDK 25 for the pinned combination. If a combination fails,
it's also tried against the PR's base branch: if the base fails too, it's flagged as pre-existing
and doesn't block your PR; only a combination that passes on the base but fails on your branch is
treated as a real regression.

What it does *not* check: it doesn't rebuild the library against every supported framework line
(only the newest one, to keep PR turnaround reasonable), and it always builds from source, so it
can't catch a binary incompatibility between a published deployment artifact and a newer framework
at augmentation time the way the scheduled compatibility monitor does (see
`docs/adr/0001-compatibility-testing-strategy.md`, this is exactly what issue #199 was).

To run the same checks locally:

```shell
./mvnw -Dquickly install
./mvnw -f compatibility-tests/pom.xml verify
```

or against a specific framework version, e.g. to try a Quarkus release that isn't in the matrix
yet:

```shell
./mvnw -f compatibility-tests/quarkus-app/pom.xml verify -Dquarkus.platform.version=3.40.0
```

The full compatibility matrix (which framework lines are required vs. canary, and why) lives in
`compatibility/policy.yaml` and is explained in the ADR mentioned above. `compatibility/README.md`
covers how to change that policy - promoting a line from canary to required, or adding a new
framework.

### Tests and documentation are not optional

Don't forget to include tests in your pull requests. Also don't forget the documentation (reference documentation,
javadoc...).

## Setup

If you have not done so on this machine, you need to:

* Make sure you have a case-sensitive filesystem. Java development on a case-insensitive filesystem can cause headaches.
    * Linux: You're good to go.
    * macOS: Use the `Disk Utility.app` to check. It also allows you to create a case-sensitive volume to store your code projects. See this [blog entry](https://karnsonline.com/case-sensitive-apfs/) for more.
    * Windows: [Enable case sensitive file names per directory](https://learn.microsoft.com/en-us/windows/wsl/case-sensitivity)
* Install Git and configure your GitHub access
    * Windows:
        * enable longpaths: `git config --global core.longpaths true`
        * avoid CRLF breaks: `git config --global core.autocrlf false`
* Install Java SDK 17+ (OpenJDK recommended)

### IDE Config and Code Style

The scheduler has a strictly enforced code style. Code formatting is done by the Eclipse code formatter, using the config
files found in the `support-projects/ide-config` directory. By default, when you run `./mvnw install`, the code will
be formatted automatically. When submitting a pull request the CI build will fail if running the formatter results in
any code changes, so it is recommended that you always run a full Maven build before submitting a pull request.

If you want to run the formatting without doing a full build, you can run `./mvnw process-sources`.

#### Eclipse Setup

Open the *Preferences* window, and then navigate to _Java_ -> _Code Style_ -> _Formatter_. Click _Import_ and then
select the `eclipse-format.xml` file in the `support-projects/ide-config` directory.

Next navigate to _Java_ -> _Code Style_ -> _Organize Imports_. Click _Import_ and select the `eclipse.importorder` file.

#### IDEA Setup

Open the _Preferences_ window (or _Settings_ depending on your edition), navigate to _Plugins_ and install
the [Adapter for Eclipse Code Formatter](https://plugins.jetbrains.com/plugin/6546-eclipse-code-formatter) from the
Marketplace.

Restart your IDE, open the *Preferences* (or *Settings*) window again and navigate to _Adapter for Eclipse Code
Formatter_ section on the left pane.

Select _Use Eclipse's Code Formatter_, then change the _Eclipse workspace/project folder or config file_ to point to the
`eclipse-format.xml` file in the `support-projects/ide-config/src/main/resources` directory. Make sure the _Optimize Imports_ box is
ticked. Then, select _Import Order from file_ and make it point to the `eclipse.importorder` file in the `support-projects/ide-config/src/main/resources` directory.

Next, disable wildcard imports:
navigate to _Editor_ -> _Code Style_ -> _Java_ -> _Imports_
and set _Class count to use import with '\*'_ to `999`. Do the same with _Names count to use static import with '\*'_.

## Build

* Clone the repository: `git clone https://github.com/carbonintensityio/green-scheduler.git`
* Navigate to the directory: `cd green-scheduler`
* Invoke `./mvnw -Dquickly` from the root directory as a quick sanity check

```bash
git clone https://github.com/carbonintensityio/green-scheduler.git
cd green-scheduler
./mvnw -Dquickly
# Wait... success!
```

`./mvnw -Dquickly` is only a quick sanity check: it skips tests, integration tests, and the enforcer's version checks,
so a green result here doesn't mean the change is actually verified.

To run the full test suite the same way CI does, use:

```bash
./mvnw -B --settings .github/mvn-settings.xml -Dno-format verify
```

The `-Dno-format` flag matters: without it, the formatter profile silently rewrites your source files to the expected
style instead of failing the build when it finds a formatting violation, which is not what CI does. CI itself runs
with `-Dno-format`, so leaving it off locally means a formatting problem that would fail the build on GitHub instead
just gets fixed on your machine without telling you.

When contributing to the scheduler, it is recommended to respect the following rules.

> **Note:** The `impsort-maven-plugin` uses the `.cache` directory on each module to speed up the build.
> Because we have configured the plugin to store in a versioned directory, you may notice over time that the `.cache` directory grows in size. You can safely delete the `.cache` directory in each module to reclaim the space.
> Running `./mvnw clean -Dclean-cache` automatically deletes that directory for you.

**Contributing to an extension**

When you contribute to an extension, after having applied your changes, run:

* `./mvnw -Dquickly` from the root directory to make sure you haven't broken anything obvious
* `./mvnw -f extensions/<your-extension> clean install` to run a full build of your extension including the tests, e.g.
  `./mvnw -f extensions/quarkus clean install` or `./mvnw -f extensions/spring-boot-starter clean install`

**Contributing to a core artifact**

Obviously, when you contribute to a core artifact of the scheduler, a change may impact any part of the scheduler. 
So the rule of thumb would be to run the full test suite locally but this is clearly impractical as it takes a lot of
time/resources.

Thus, it is recommended to use the following approach:

* run `./mvnw -Dquickly` from the root directory to make sure you haven't broken anything obvious
* run any build that might be useful to test the behavior you changed actually fixes the issue you had (might be an
  extension build, an integration test build...)
* push your work to your own fork of the scheduler to trigger CI there
* you can create a draft pull request to keep track of your work
* wait until the build is green in your fork (use your own judgement if it's not fully green) before marking your pull
  request as ready for review (which will trigger the scheduler CI)

**Modules at a glance**

| Module | What it's for | Test stack |
|---|---|---|
| `core` | Core scheduling engine (window scheduling logic, annotation parsing, method invocation) | JUnit 5, AssertJ, Mockito |
| `execution-planner` | Carbon-intensity-aware planning logic and the client for the external carbon-intensity data source | JUnit 5, AssertJ, Mockito |
| `extensions/quarkus/deployment` | Quarkus build-time processing for `@GreenScheduled` | `quarkus-junit5-internal`, REST Assured |
| `extensions/quarkus/runtime`, `extensions/quarkus/runtime-dev` | Quarkus runtime support and dev-mode UI | no dedicated test sources |
| `extensions/spring-boot-starter` | Spring Boot auto-configuration for `@GreenScheduled` | JUnit 5, AssertJ, Mockito |
| `integration-tests` | Reserved for cross-framework integration tests | empty aggregator today, no submodules yet |

## Release your own version

You might want to release your own patched version of the scheduler to an internal repository.

Commit the changes, then run:

```shell
./mvnw --settings your-maven-settings.xml \
    clean deploy \
    -DskipTests -DskipITs \
    -Prelease \
    -Drevision=x.y.z-yourcompany
```

If your Maven settings are in your global Maven settings file located in the `.m2/` directory, you can drop the `--settings your-maven-settings.xml` part.

## Usage

After the build was successful, the artifacts are available in your local Maven repository.

To include them into your project you need to make sure to reference version `999-SNAPSHOT`.

### Test Coverage

The scheduler uses Jacoco to generate test coverage. Run `mvn install jacoco:report -Ptest-coverage` in the module
you want a report for (or with `-f ...`); the report is generated in that module's `target/site/jacoco/`.

For an aggregated, multi-module report covering `core` and `execution-planner` together, run
`mvn -pl core,execution-planner,coverage-report -am install -Ptest-coverage` from the repo root; the report is
generated in `coverage-report/target/site/jacoco-aggregate/`.

### Check security vulnerabilities

When adding a new extension or updating the dependencies of an existing one,
it is recommended to run in the extension directory the [OWASP Dependency Check](https://jeremylong.github.io/DependencyCheck) with `mvn -Dowasp-check`
so that known security vulnerabilities in the extension dependencies can be detected early.

## The small print

This project is an open source project, please act responsibly, be nice, polite and enjoy!
