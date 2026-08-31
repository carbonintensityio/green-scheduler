# Security Policy

## Supported Versions

green-scheduler ships as a single, continuously released stream of versions (see the [Release workflow](.github/workflows/release.yml) and [`.github/project.yml`](.github/project.yml) - every merge that bumps the version there produces a new GitHub release marked as the latest). There are no maintained older branches or long-term-support versions, so only the **latest released version** is supported with security fixes. If you're affected by a vulnerability on an older version, please upgrade to the latest release first and confirm the issue still reproduces there.

## Reporting a Vulnerability

Please **do not** open a public GitHub issue for security vulnerabilities.

Instead, use GitHub's private vulnerability reporting for this repository:

https://github.com/carbonintensityio/green-scheduler/security/advisories/new

This opens a private draft security advisory that only you and the maintainers can see, so the issue can be discussed and fixed before any details are made public. Please include:

- The green-scheduler version (or commit) affected
- Which integration is involved (Spring Boot starter, Quarkus extension, or core)
- Steps to reproduce, or a minimal reproducer if you have one
- The impact you believe the vulnerability has

We'll acknowledge new reports as soon as we can and keep you updated as we work through the fix. Once a fix is released, we'll publish the advisory and credit the reporter unless you'd prefer to stay anonymous.
