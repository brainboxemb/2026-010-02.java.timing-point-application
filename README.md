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

## NetBeans development configurations

The Maven parent project exposes three selectable NetBeans configurations
in **Project > Properties > Configurations** / **Set Configuration**:

- **dev-reference** — Run and Debug SI-01 with `config/application.yml`.
  This uses `reference` EventData, providing algorithmic tag/registration mapping.
- **dev-simulation** — Run and Debug SI-01 with `config/development-simulation.yml`.
  This uses the same `reference` EventData mapping as normal startup but a
  separate engineering LogBook.
- **dev-client** — Build, Run and Debug SI-02 Engineering Client from the same
  **Timing Point Parent** project. Maven automatically includes and orders
  `test-client-central` before the runnable `test-client` module.

Normal/default still starts SI-01 with `config/application.yml`. Select a
configuration on the **Timing Point Parent** Maven project, then run or debug the
project. The SI-01 configurations and `dev-client` all use an external Java
process (`exec:exec`), not Maven's in-process `exec:java`. `dev-client`
requires JDK 21; the normal SI-01 reactor remains Java-8-compatible and does not
include the client modules unless that profile is selected.

**SimulatedAntenna produces tag observations.** EventData only maps TagIds,
RegistrationIds and TeamIds, regardless of whether the antenna is physical or
simulated. The `reference` provider supplies the same mapping in both profiles.
The capability is advertised by the actual antenna composition and topology.
Check the connected SI-01's `TAG_SCENARIO_SIMULATION` value in the SI-02
**API** tab.

On successful start, the SI-01 log records `Startup parameters` with the
selected YAML's resolved path and the configured SystemIds, NodeIds and provider
names. It does not dump the YAML file or secrets.

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
test-client-central/  isolated SI-02 Central IF-06 simulator boundary
test-client/          standalone JavaFX Development Client
docs/                 focused repository support documentation
```

The Engineering Client is **SI-02**, a reusable desktop software item.

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
