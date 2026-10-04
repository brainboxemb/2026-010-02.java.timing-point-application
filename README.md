# Timing Point Application

Public Java implementation of **SI-01 — Timing Point Application** for the
BrainboxEmb event-timing software project.

Use this repository when you want to build or run the headless timing application,
work on its Java implementation, consume the shared TimingData library, use the
Development Client, or execute implementation verification.

Project planning, product requirements, interfaces and architecture are owned by the
companion
[Event Timing Software meta repository](https://github.com/brainboxemb/2026-010-01.meta.event-timing-software).

## Quick start

Linux/POSIX:

```bash
./bootstrap.sh
./mvnw verify
java -jar app/target/timing-point-app-<version>.jar config/application.yml
```

Windows:

```powershell
.\bootstrap.ps1
.\mvnw.cmd verify
java -jar app\target\timing-point-app-<version>.jar config\application.yml
```

The default verification command runs normal product tests. Formal packaged-process
black-box verification is explicit:

```bash
./mvnw verify -Psystem-test
```

## Repository map

```text
shared/timing-data/   shared IF-05 TimingData Java library
core/                 reusable SI-01 application core
app/                  runnable Timing Point Application
system-test/          black-box packaged-process verification (profile-only)
test-client/          standalone JavaFX Development Client
docs/                 repository documentation
```

The Development Client is engineering tooling, not SI-02.

## Read next

- [Documentation overview](docs/README.md) — implementation manuals, design and verification.
- [Development guide](docs/20-01-development.md) — build, test, tooling and release workflow.
- [User guide](docs/20-02-user.md) — run/configure/inspect the application.
- [Implementation design](docs/40-00-design.md) — current Java module/package/runtime decomposition.
- [Verification](docs/50-00-verification.md) — test levels, formal cases and evidence.
- [Development Client](test-client/README.md) — interactive public-interface inspection.
- [AGENTS.md](AGENTS.md) — repository-specific working guidance for agents.
- [CHANGELOG.md](CHANGELOG.md) — chronological implementation history.

Current project work and accepted software design are read from the companion meta
repository rather than duplicated here.

## Generated build evidence

Repository CI publishes retained build/test output separately from source:

- pull request N: `dev/pr-N/bld`;
- protected main: `prod/bld`;
- release tag: `rel/vX.Y.Z/bld`.

These branches are generated evidence/publication output and are not hand-maintained.

## Public scope

Keep production identities, proprietary protocols, credentials, encryption keys and
private mappings out of this public repository.
