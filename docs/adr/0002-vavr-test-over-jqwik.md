# 2. Use vavr-test, not jqwik, for property-based testing

## Status

Accepted

## Context

We wanted property-based tests alongside our existing example-based tests, starting with
`CarbonIntensityJsonParser` (round-trip properties) and `FixedWindowExpressionParser` (DST-sensitive
date arithmetic). `jqwik` is the de facto standard for this on the JVM, but since v1.10.0 it ships an
anti-AI clause: earlier releases embedded a hidden prompt-injection in test output instructing AI
coding agents to delete tests/code, and current releases still print an explicit directive telling AI
agents to disregard jqwik's results. In repos where AI coding agents routinely run the test suite,
that's a real prompt-injection risk, not just a licensing quibble - so jqwik is excluded here
regardless of the maintainer's intent.

## Decision

We use `io.vavr:vavr-test` instead.

### Considered options

- **jqwik** - the de facto standard, rejected for the reason above.
- **QuickTheories** - no such issue, but effectively abandoned (last commit 2019, last release 2018).
- **Newer libraries** (e.g. hegel-java) - too immature/unproven (a handful of GitHub stars) for
  production test code.
- **vavr-test** - chosen: part of the actively maintained `vavr` project, targets JUnit 5, and its API
  is framework-agnostic (`Property.def(...).forAll(...).check().assertIsSatisfied()`), so it's called
  directly from plain (non-CDI) test classes without a separate test engine.

Full research trail: [wayfinder map CIIO-309](https://first8.atlassian.net/browse/CIIO-309), ticket
[CIIO-326](https://first8.atlassian.net/browse/CIIO-326). See also the equivalent ADR in
carbonintensity-api (`docs/adr/0001-vavr-test-over-jqwik.md`), from ticket CIIO-320 in the same
wayfinder map.
