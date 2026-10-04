# Timing Point Application documentation

This directory is the maintained documentation collection for the Java implementation
repository of **SI-01 — Timing Point Application**.

The companion
[Event Timing Software meta repository](https://github.com/brainboxemb/2026-010-01.meta.event-timing-software)
owns project planning, product requirements, interfaces, architecture and formal
verification specifications. This repository documents how that design is implemented,
built, run, maintained and verified in Java.

## Where should I start?

| Need | Start here |
| --- | --- |
| What implementation work is active? | [10-00 — Plan and authority](10-00-plan.md) |
| How do I develop, build or release it? | [20-01 — Development](20-01-development.md) |
| How do I run/use the application? | [20-02 — User](20-02-user.md) |
| Why does this repository exist? | [30-00 — Repository specification](30-00-specification.md) |
| How is the current Java implementation put together? | [40-00 — Implementation design](40-00-design.md) |
| How is it tested and qualified? | [50-00 — Verification](50-00-verification.md) |
| What exact shared tooling baseline is consumed? | [20-10 — Tooling baseline](20-10-tooling-baseline.md) |

Component-specific entrypoints remain close to the component:
- [Development Client](../test-client/README.md)
- [Black-box system-test module](../system-test/README.md)

## Authority boundary

Use the meta repository for:
- SIP roadmap and current project sequence;
- SI-01 requirements and architecture;
- IF-03 / IF-05 / IF-11 contracts;
- focused SDDs;
- SVP/VTS verification intent.

Use this repository for:
- source and tests;
- current Java module/package implementation;
- build/run/development instructions;
- repository-owned CI and retained build/test evidence;
- current implementation-support documentation.

`CHANGELOG.md` is the chronological implementation record. Do not reconstruct the
current design by reading the changelog as if it were a specification.
