# Timing Point Application

Public Java implementation of **SI-01 — Timing Point Application** for the
BrainboxEmb event-timing software project.

Use this repository when you want to build or run the headless timing application,
work on its Java implementation, consume the shared TimingData library, use the
Development Client, or execute implementation verification.

Project planning, requirements, interface contracts and software design are owned by the
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

Formal packaged-process black-box verification is explicit:

```bash
./mvnw verify -Psystem-test
```

## Startup command line

Show the startup options provided by the packaged application:

```text
java -jar app/target/timing-point-app-<version>.jar --help
```

Start explicitly with an IF-11 YAML file:

```text
java -jar app/target/timing-point-app-<version>.jar --config config/application.yml
```

The original positional form remains supported:

```text
java -jar app/target/timing-point-app-<version>.jar config/application.yml
```

Generate a complete example configuration from the same JAR:

```text
java -jar app/target/timing-point-app-<version>.jar --generate-config
java -jar app/target/timing-point-app-<version>.jar --generate-config my-application.yml
```

Without an explicit output path the generator writes `application.yml`. It
creates missing parent directories but deliberately refuses to overwrite an
existing file. The generated example is packaged from the repository's canonical
`config/application.yml`, so the example belongs to the exact application
version being executed.

Use `--version` to print application version and source provenance. Running the
JAR without arguments remains the packaged-artifact smoke check used by build
verification.

## Repository map

```text
shared/timing-data/   shared IF-05 TimingData Java library
core/                 reusable SI-01 application core
app/                  runnable Timing Point Application
system-test/          black-box packaged-process verification
runtime-characterization/ engineering-only runtime measurement harness (optional profile)
test-client/          standalone JavaFX Development Client
docs/                 focused repository support documentation
```

The Development Client is engineering tooling, not SI-02.

## Read next

- [Event Timing Software meta repository](https://github.com/brainboxemb/2026-010-01.meta.event-timing-software) — planning, requirements, interfaces, design and formal verification specifications.
- [Java tooling baseline](docs/tooling-baseline.md) — exact shared tooling consumed by this repository.
- [Development Client](test-client/README.md) — interactive public-interface inspection.
- [System-test module](system-test/README.md) — packaged-process black-box verification support.
- [AGENTS.md](AGENTS.md) — repository-specific working guidance.
- [CHANGELOG.md](CHANGELOG.md) — chronological implementation history.

Current product design and project planning are intentionally not duplicated in this
README.

## Generated build evidence

Repository CI publishes retained build/test output separately from source:

- pull request N: `dev/pr-N/bld`;
- protected main: `prod/bld`;
- release tag: `rel/vX.Y.Z/bld`.

These branches are generated evidence/publication output and are not hand-maintained.

## Public scope

Keep production identities, proprietary protocols, credentials, encryption keys and
private mappings out of this public repository.
