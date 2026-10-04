# 2026-010-02.java.timing-point-application

Public Java application core and executable for reusable event timing and time-registration applications.

Project-wide planning, requirements, architecture, interface design and verification coordination live in the companion meta repository: [**2026-010-01.meta.event-timing-software**](https://github.com/brainboxemb/2026-010-01.meta.event-timing-software).

## Current scope

This repository is the public implementation repository for **SI-01 — Timing Point Application**. `v0.2.3` is the accepted SIP Step-4 first-registration baseline; development continues on `0.2.4-SNAPSHOT`. Step 5 moves one TimingNode's input boundary outward through a built-in `SimulatedAntenna` and `TagProcessor`, using deterministic synthetic TagId-to-RegistrationId reference data while retaining the existing TimingNode-owned commit, LogBook and persistence path. Runtime measurement/characterization is developed alongside that simulated-input path. Real RFID hardware, multi-node field behaviour and upstream/backoffice integration remain later-step work.

## Artifact and package model

Architectural responsibilities are not automatically Maven artifacts.

The default reactor contains three product artifacts. TimingData is an independently
reusable shared IF-05 library because both SI-01 and the standalone Development Client
are real consumers. It deliberately remains one artifact containing the semantic contracts,
default/reference profile, codec and factory/provider; those responsibilities are not split
into separate API/default JARs. The verification-only
`system-test` module is added only when the explicit Maven `system-test`
profile is selected:

```text
shared/
  timing-data/    event-timing-data        shared Java-8 IF-05 TimingData library
core/             timing-point-core        reusable SI-01 application core
app/              timing-point-app         runnable/default application
system-test/      timing-point-system-test  black-box verification only (profile-only)
```

`system-test` has no Java dependency on the product artifacts. It starts the built app JAR as
a separate JVM process and verifies only external interfaces. It is not a release/publication
artifact. The root `timing-point-parent` POM is build/aggregation metadata rather than a deployed
product component.

The shared TimingData library models committed registrations with a small common
`TimingData` contract plus type-safe nested `TimingData.AutomaticRegistration` and
`TimingData.ManualRegistration` variants. One immutable `TimingDataFactory.Context`
carries the common source/sequence/location/time values. A configured
`TimingDataFactory` returns the typed variant and a matching `TimingDataCodec`
handles representation. Small shared identity values are grouped under the
source-only `TimingDataTypes` holder as `NodeId`, `LocationId` and
`RegistrationId`; `TimingTimestamp` remains a standalone value type. Source-domain
`TagId` / `TeamId` resolution happens before this API boundary.

The `io.github.brainboxemb.eventtiming` namespace denotes the software-system/product family; reusable SI-01 code is rooted under `io.github.brainboxemb.eventtiming.timingpoint` because SI-01 is the software running locally at a timing observation point. `TimingNode` remains a logical domain aggregate inside that application and is not the package root.

The reusable `timing-point-core` JAR is organised by logical responsibility, but a
layer/package is not represented by a runtime marker object merely to make the source tree mirror
the architecture diagram.

Current real application-core behaviour is deliberately small and follows the package boundaries directly:

```text
io.github.brainboxemb.eventtiming.timingpoint.application.PresentationGateway
io.github.brainboxemb.eventtiming.timingpoint.application.TimingNodeProxy
io.github.brainboxemb.eventtiming.timingpoint.application.TimingNodeStatus
io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode
io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeLogic   # package-private
io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes   # source-code grouping
io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TagProcessor
io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TagId
io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TagRegistrationResolver
io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.DefaultTimingDataPersistence
io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.Antenna
io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.SimulatedAntenna
io.github.brainboxemb.eventtiming.timingpoint.io.storage.AppendOnlyRecordStore
io.github.brainboxemb.eventtiming.timingpoint.io.storage.FileAppendOnlyRecordStore
io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialWorker
io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity
io.github.brainboxemb.eventtiming.timingpoint.runtime.config.YamlLoader
io.github.brainboxemb.eventtiming.timingpoint.runtime.Application
io.github.brainboxemb.eventtiming.timingpoint.runtime.Composition
io.github.brainboxemb.eventtiming.timingpoint.runtime.Lifecycle
io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.HttpEndpoint
io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.WebSocketEndpoint
```

The responsibilities are deliberately distinct:

```text
runtime/
  Application
  Composition
  Lifecycle
  config/
    Config
    Presentation
    Api

infra/
  BuildIdentity
  EmbeddedBuildIdentityLoader
  config/
    YamlLoader
  logging/
  loggingserver/

io/
  devices/
    antenna/
      Antenna
      SimulatedAntenna
  storage/
    AppendOnlyRecordStore
    FileAppendOnlyRecordStore

platform/
  execution/
    SerialWorker
  events/
    Event
```

TimingData-specific persistence semantics remain above the generic storage layer: `DefaultTimingDataPersistence` owns codec, TimingNodeId and sequence validation; `io.storage` owns only opaque record/file mechanics and imports no Domain/Application classes.

`runtime.Composition` owns knowledge of the concrete running application graph. Infrastructure provides supporting/cross-cutting mechanisms only; I/O and Platform remain separate responsibilities. The package namespace carries the context, so runtime classes use the short names `Application`, `Composition` and `Lifecycle`. There is no second bootstrap object and no application builder.

A constructed `TimingNode` is always complete: TimingData persistence, factory and TimeSource are required constructor dependencies. There is no lifecycle-only or capability-partial production node.

`TimingNodeTypes` groups the public status/result/exception value types in one Java source file; it has no runtime state and is not a separate architecture component.

Local events keep publish ownership inside the component. Consumers receive a subscription-only `EventSource<T>` and subscribe directly, e.g. `gateway.timingNode().statusChangedEvent().subscribe(...)`. The underlying `Event<T>` registry is thread-safe, but delivery remains synchronous on the emitting thread and concurrent emits are not serialized by the generic event primitive.

`TimingNode` remains the visible Domain component boundary used by Application. Presentation adapters reach it only through `PresentationGateway` and the node-scoped `TimingNodeProxy`. It serializes typed commands and consistency-sensitive queries through `SerialWorker`, while package-private `TimingNodeLogic` keeps the mutable node state, `LocationId`, LogBook interaction and registration commit behaviour readable. Result-bearing callers use `invoke(TimingNodeCommands....)` and may wait for the processed domain result. Producer/callback paths use `submit(TimingNodeCommands....)` and receive only immediate bounded-queue admission, so RFID/TagProcessor ingress does not wait for later node processing. Reads use `query(TimingNodeQueries....)`; the current bounded LogBook queries traverse the owned history on the serial lane and build only the requested response representation. The Domain automatic-registration command is `addAutomaticRegistration(...)`. Presentation uses the node-scoped `TimingNodeProxy.applyAutomaticRegistration(action, registrationId, time)` boundary; the current IF-03 engineering resource remains `/auto-reg` and supplies the implemented `ADD` action.

Step-5 antenna ingress uses a separate path into the same command: `SimulatedAntenna` emits a decoded tag observation through the normal `Antenna` callback, `TagProcessor` resolves the source `TagId` to the canonical `RegistrationId`, filters unknown tags and calls `TimingNode.submit(TimingNodeCommands.addAutomaticRegistration(...))`. The antenna callback receives only immediate bounded-lane admission; sequence allocation, active LocationId, recorded time, persistence, LogBook visibility and the committed event remain owned by the existing TimingNode path.

The executable artifact remains thin:

```text
io.github.brainboxemb.eventtiming.timingpoint.app/
  Main
```

It supplies the configuration path, starts the reusable logging infrastructure and hands the parsed runtime configuration to `runtime.Composition`. The default IF-11 YAML parser/mapping lives in `runtime.config.YamlLoader` because it maps directly to the concrete runtime configuration model. The executable selects `slf4j-jdk14`; the core artifact itself still selects no SLF4J provider.

### Artifact rule

A package or architecture layer is **not** a publication boundary by itself. Introduce another Maven library artifact only when a real reason exists, such as:

- another application needs to consume it independently;
- an optional integration brings a significant independent dependency/lifecycle boundary;
- deployment, ownership or release/versioning requires separation;
- public/private implementation boundaries require independent composition.

This keeps logical responsibilities inside the one core artifact while real package boundaries emerge from implemented behaviour. A later capability such as RabbitMQ, a platform-specific implementation, or reusable test support can be split only when its consumer and boundary are concrete.

### Derived applications

The application core is intended to support more than one executable composition. Examples that may later become separate applications include:

```text
single-TimingNode application compose exactly one TimingNode
multi-TimingNode application  compose and coordinate 1..N TimingNode objects
```

Those applications should reuse the same application-core library and inject/select their own concrete components. New Step-3 configuration/domain types use `TimingNode` / `TimingNodeId` directly; the repository does not introduce legacy `Waypoint` / `UniqueID` compatibility names.

The working design is coordinated in the meta repository, especially `docs/31-01-SDD-02-java-component-design.md`.

### Current application configuration

The executable uses one external YAML file for the single TimingNode currently composed by the
application. The current configuration baseline includes the implemented presentation listeners explicitly:

```yaml
timingNodeId: TN-01

presentation:
  remoteShell:
    bindAddress: 127.0.0.1
    port: 8023
  api:
    http:
      bindAddress: 127.0.0.1
      port: 8081
    webSocket:
      bindAddress: 127.0.0.1
      port: 8082
```

A08 also configures cross-cutting runtime logging independently from presentation/status:

```yaml
logging:
  level: INFO
  file:
    path: logs
    rotateBytes: 1048576
    retainedFiles: 5
  live:
    bindAddress: 127.0.0.1
    port: 8030
```

The application core still logs only through SLF4J. Executable infrastructure `Logging` maps the
semantic startup level to `slf4j-jdk14 -> java.util.logging`, applies the same compact formatter
to console output, and writes retained rotating file logs under timestamped names such as
`20250514-101657.txt`. Retained file records use
`HH:mm:ss.SSS - [LEVEL] - message - [sourceClass.sourceMethod]`, while the executable optionally
exposes a dedicated best-effort live-log TCP listener. The JavaFX
`LoggingServer` owns that live socket; the engineering client initiates the connection and its
**Logs** tab can inspect new records and
temporarily change the process-wide log level. A runtime level change is not persisted to YAML and
restart restores the configured level. The live diagnostics stream is separate from IF-03
`/api/v1/events`.

The remote shell is a small line-oriented TCP development/service endpoint. It is **not** an SSH
or Telnet protocol implementation. IF-03 is the general **API**; A06/A07 implement its first
HTTP version/status and WebSocket event slice:

```text
GET /api/v1/version                         http://127.0.0.1:8081
GET /api/v1/status                          http://127.0.0.1:8081
WS  /api/v1/events                          ws://127.0.0.1:8082
```

The WebSocket adapter sends a complete `STATUS_SNAPSHOT` immediately after connect/reconnect.
`STATUS_CHANGED` is reserved for real authoritative status changes; the current Step-3
TimingNode remains `CLOSED`, so no synthetic change is generated merely to exercise the
transport.

API HTTP and WebSocket are grouped under `presentation.api` because they are two
transports of the same functional interface. A future browser/iPad `presentation.web` interface
may have its own HTTP/WebSocket endpoints without sharing the API namespace.

The committed development example keeps all network presentation listeners loopback-only;
binding to another interface must be a deliberate configuration change.

A synthetic development example is kept at `config/application.yml`. After building, start the
configured application with:

```bash
java -jar app/target/timing-point-app-<version>.jar config/application.yml
```

The configured process stays running until the JVM receives a normal shutdown request. On a
development terminal, **Ctrl+C** remains a normal stop route on Windows and Linux; the JVM shutdown
hook closes the application through the same lifecycle path used by tests.

The local console and A05 remote terminal use the same command session:

```text
help      show available commands
version   show application/build version
status    show current TimingNode identity and lifecycle
quit      stop the application cleanly
exit      alias for quit
```

A remote client disconnect ends only that terminal session. A later connection can reconnect to
the same listener. `quit` / `exit` intentionally retain the same meaning as the local console and
request graceful application shutdown.

The temporary no-argument startup remains only for the existing artifact smoke check. Multiple
TimingNodes, further presentation endpoints, platform/profile overlays and I/O configuration are
added only when their SIP activities provide a real consumer.

### Development Client

`test-client/` is the standalone Java 17 / JavaFX **Development Client** used for manual integration, diagnostics and public-interface inspection. It remains development tooling rather than SI-02 and deliberately has no dependency on SI-01 implementation classes.
It is deliberately not part of the Java-8 SI-01 Maven reactor and has no dependency on
`timing-point-core` or `timing-point-app`.

With JDK 17 selected:

```powershell
.\mvnw.cmd -f test-client\pom.xml javafx:run
```

The **API** tab is the primary work surface for Version/Status, selected TimingNode
inspection, OPEN-with-LocationId, Close, dev `auto-reg`, bounded LogBook history and
raw API/selected-record inspection. The LogBook table shows TimingData **Type** and
**Code** separately. **Events** uses
`ws://127.0.0.1:8082/api/v1/events` for status/TimingData event inspection.
**Logs** keeps Development Client and SI-01/Device records as distinct sources, and
**Terminal** connects directly to the development shell on `127.0.0.1:8023`.

See `test-client/README.md`; the formal Step-4 V04 manual flow is in
`test-client/STEP4-DEMO.md`.


## Local checkout and project tooling

The repository uses two reusable tooling layers:

```text
tools/tool.git-project   generic Git lifecycle/bootstrap and affected analysis
tools/tool.java-project  Java/Maven canonical build/test/evidence tooling
```

`tool.git-project` is pinned directly by its committed gitlink. `project.yml` declares `tool.java-project` as a managed tooling dependency and points to `project.java.yml` for Java-specific configuration. The reviewed Migration-006 baseline is documented in `docs/tooling-baseline.md`.

A normal clone does not require `--recurse-submodules`.

Windows:

```powershell
git clone https://github.com/brainboxemb/2026-010-02.java.timing-point-application.git
cd 2026-010-02.java.timing-point-application
.\bootstrap.ps1
.\mvnw.cmd verify
```

Linux/POSIX shell:

```bash
git clone https://github.com/brainboxemb/2026-010-02.java.timing-point-application.git
cd 2026-010-02.java.timing-point-application
./bootstrap.sh
./mvnw verify
```

The default root `verify` builds the product reactor and runs its normal tests, but does **not**
launch the separate application process. Deliberate full system verification is explicit:

```powershell
.\mvnw.cmd verify -Psystem-test
```

```bash
./mvnw verify -Psystem-test
```

That profile adds `system-test` after the application JAR has been packaged. Formal
verification-case IDs are preserved in the Java class names:
`VC-ST1-001 -> VcSt1_001Test`, `VC-ST1-002 -> VcSt1_002Test` and
`VC-ST1-004 -> VcSt1_004Test`. VC-ST1-001
launches the JAR as a child JVM with temporary loopback ports and verifies version, compact
status, WebSocket snapshot/reconnect and controlled shutdown. VC-ST1-002 drives the Step-4
public registration flow through IF-03: capabilities, OPEN-with-LocationId plus separate
closed-state location control, invalid OPEN-state location change, dev `auto-reg`,
live committed TimingData and bounded LogBook history. It then verifies a WebSocket reconnect and a full SI-01 process restart
against the same TimingData file: the committed record remains queryable while the node
starts CLOSED with no operational location, and recovered history is not emitted as a
new `TIMING_DATA_COMMITTED` event. VC-ST1-004 supplies persisted TimingData owned by a
different NodeId and verifies degraded startup containment: SI-01 remains running,
IF-03 and the Remote Shell remain diagnostic, the affected node reports `ERROR` with
`TIMING_DATA_RECOVERY_FAILED`, normal node operations are rejected, reconnect
snapshots remain consistent and controlled shutdown still works. The verifier imports no
core/application classes, so this remains process-level black-box verification rather than
another in-process component test.

Use `update-repo.ps1` / `update-repo.sh` for a controlled dependency-alignment pass after changing refs in `project.yml`. The generic tool refuses to overwrite local changes inside a managed dependency.

### A04 Windows / NetBeans acceptance check

From a clean Windows checkout, run `.\bootstrap.ps1` and open the repository root in NetBeans as
the Maven project.

On first open, NetBeans may perform a **priming build** to resolve the reactor/dependencies. That
Maven preparation can compile and run tests; it is not the application Run action.

The repository contains `nbactions.xml` so **Build Project** uses `install` and
**Clean and Build Project** uses `clean install`, both with `maven.test.skip=true`. Those IDE
build actions are intentionally fast and do not compile or run tests.

**Run Project** on the root Maven project first installs the current product reactor sources with
tests skipped, then starts the executable `app/` module with `config/application.yml`. This
ensures the app uses the sibling application core from the same checkout rather than an older local
SNAPSHOT. The root POM remains build/aggregation metadata and is not made into an executable
application.

Use **Run Project** (or **Debug Project** when debugging), then enter:

```text
help
version
status
quit
```

`help` must list every supported local command, `version` and `status` must return the shared
application values, and `quit` must terminate the process through the normal graceful shutdown
path.

Run and Debug use the same configured application path; Debug only adds the NetBeans JPDA debugger.

The command-line split is:

```powershell
# Normal product verification: core/app tests, no separate process launch
.\mvnw.cmd verify

# Deliberate VC-ST1 black-box verification (VC-ST1-001 + VC-ST1-002 + VC-ST1-004)
.\mvnw.cmd verify -Psystem-test

java -jar app\target\timing-point-app-0.2.3-SNAPSHOT.jar config\application.yml
```

## Toolchain baseline

```text
Java              Eclipse Temurin 8u504-b01 (`8.0.504+1` in CI)
Java source/API    Java SE 8
Maven              3.9.16
Maven Wrapper      3.3.4
Moon               2.5.4 through `tool.git-project`
```

The Java-specific baseline is recorded in `project.java.yml`. Maven remains the authoritative project-version and Java build/test source. Moon does not implement or cache the Maven lifecycle; it only declares which repository changes affect the canonical Java capability and which narrower changes require native full-Windows qualification.

## Minimal Step-2 application lifecycle

After a reactor build, run the executable application using the version from the root `pom.xml`:

```bash
java -jar app/target/timing-point-app-<version>.jar
```

The executable loads its application/build identity from a Maven-filtered resource, starts its minimal lifecycle, reaches `RUNNING`, and then shuts down to `STOPPED`. The embedded identity deliberately separates software identity from deterministic source/build provenance:

```text
application    timing-application
version        Maven ${project.version}
revision       exact Git commit
sourceRef      branch, tag or CI ref
buildOrigin    local or github-actions
dirty          true when uncommitted source changes were present
apiVersion     IF-03 major version
```

`pl.project13.maven:git-commit-id-plugin:4.9.10` supplies the Git revision, local source ref and dirty-state during Maven `initialize`; GitHub Actions supplies the CI source ref/origin through stable environment context. Normal resource filtering packages only those values the runtime needs. The executable bootstrap reads them into `BuildIdentity`; the core value itself never knows about the resource file or a working Git checkout.

Wall-clock build time, CI run/build id and actor/user are deliberately **not** embedded. Repeating a build with the same version/revision/ref/origin/dirty inputs must not become a different artifact merely because it ran at another time or under another run id.

A Git tag does **not** silently determine or override the application version. If the POM still contains a `-SNAPSHOT` version, building a commit tagged as a release still reports that snapshot version. A valid release deliberately aligns Maven version, CHANGELOG release section and Git tag.

Lifecycle diagnostics use the selected logging composition:

```text
timing-point-core       -> SLF4J API + reusable JUL logging infrastructure; no SLF4J provider selected
timing-point-app        -> selects slf4j-jdk14 -> java.util.logging
```

`java.util.logging` writes the lifecycle INFO records through the runtime logging backend. Startup logging includes the concrete Git revision, source ref, build origin and dirty-state. The stable stdout smoke line used by CI intentionally remains independent of build-specific provenance:

```text
timing-application lifecycle OK version=<version> state=STOPPED
```

This short-lived process is intentional for Step 2. Long-running service behaviour and public version/status transports belong to later SIP steps.

## Production CI and test evidence

This repository consumes released `tool.git-project v0.2.8` and `tool.java-project v0.3.2`. The exact Java owner commit is `c0ca2e1365a64bc626ca331a8170d13340ae0b36`; exact generic Git provenance is recorded in `docs/tooling-baseline.md`.

The consumer owns only product metadata/checks, trigger policy, impact declarations and artifact names. Shared Java execution is:

```text
product metadata + logging-boundary check
        ↓
exact base-to-head Java affected preflight
        ↓
        ├─ unrelated -> stop before JDK/Maven/Windows/publication
        │
        └─ affected -> one Linux canonical Maven Wrapper `verify`
                       + selected Windows qualification
        ↓
prepared canonical `bld` tree
        ↓
generated-output publication without rebuilding Maven output
```

`moon.yml` contains two impact-only capabilities:

```text
java.canonical      changes that require canonical Java execution
java.windows-full   narrower build/toolchain/workflow/platform-sensitive changes
```

The Java/Moon decision does not run the build. `tool.java-project` owns the one canonical Linux Maven producer, Maven dependency caching, Surefire evidence, toolchain/build provenance and generated-output finalization.

Windows qualification is selected by event and impact:

```text
pull request, ordinary Java impact       auto -> smoke
pull request, build/tooling impact       auto -> full
ordinary protected main publication     none
manual non-tag qualification             explicit, default full
exact release-tag qualification          full
```

`smoke` runs the exact Linux-produced application JAR on Windows without a second Maven build. `full` adds an independent native Windows Maven `verify`; that native Windows build can start in parallel with the Linux canonical producer after preflight, while exact-artifact smoke waits for Linux output. A normal protected-main publication deliberately does not allocate Windows again after the pull request has already qualified the change.

The canonical Linux producer stages all three product JARs:

```text
artifacts/
  event-timing-data-<version>.jar
  timing-point-core-<version>.jar
  timing-point-app-<version>.jar
```

Producer evidence remains under:

```text
evidence/
  executions/java-canonical/
    execution.json
    execution.log
  tests/
  toolchain-build-provenance.txt
```

Current orchestration evidence is retained separately rather than rewriting producer evidence:

```text
orchestration/
  preflight/
    decision.json
    preflight.log
    affected/
  timing.json
  timing.md
```

`timing.md`/`timing.json` record actual GitHub job/step timings and Maven-reported time so Java work can be distinguished from runner, checkout, setup and artifact-transfer overhead.

The canonical Maven `verify` intentionally excludes the black-box `system-test` profile.
Pull-request CI therefore runs normal product tests without launching VC-ST1-001 on every commit.
After an affected change is integrated into protected `main`, a repository-owned Linux job runs
`verify -Psystem-test` explicitly. Exact release-tag qualification runs the same explicit profile
on both Linux and Windows; both Surefire result sets are copied into the final release evidence
bundle.

Generated output is published as:

```text
pull request #N -> dev/pr-N/bld
main            -> prod/bld
release tag     -> rel/vX.Y.Z/bld
```

Closing a pull request removes only its `dev/pr-N/bld` preview through the released generic cleanup workflow. `prod/bld` and release output are not affected.

## Release workflow

Development normally uses a Maven `-SNAPSHOT` version. A software release is prepared through a normal reviewed PR that:

- changes the complete Maven reactor to the intended non-SNAPSHOT Maven version;
- moves the relevant `CHANGELOG.md` content into a matching release section;
- passes the normal affected PR qualification.

After that release-preparation commit is merged to protected `main`, the normal main workflow performs the canonical Linux build/publication with Windows disabled. If release metadata is valid, it creates the immutable `v<version>` tag on that exact commit and explicitly dispatches the same workflow on the tag.

The exact tag is the release qualification boundary. Tag verification always performs:

```text
exact tagged Linux canonical Maven build
native Windows Maven verify       (parallel with Linux)
explicit VC-ST1 profile verify on Linux
explicit VC-ST1 profile verify on Windows
exact Linux-produced app JAR smoke on Windows
rel/vX.Y.Z/bld publication
product artifact/build-identity validation
GitHub Release asset publication
```

A tagged release build must satisfy all of the following:

```text
Maven version       X.Y.Z
CHANGELOG heading   ## X.Y.Z — <date>
Git tag             vX.Y.Z
BuildIdentity       version=X.Y.Z
BuildIdentity       revision=<tagged commit SHA>
```

Release assets contain:

- `event-timing-data-X.Y.Z.jar`;
- `timing-point-core-X.Y.Z.jar`;
- `timing-point-app-X.Y.Z.jar`;
- SHA-256 checksums;
- a compressed evidence bundle containing build provenance, tests and orchestration evidence.

`prod/bld` remains the browsable output of `main`; tagged qualification publishes separately to `rel/vX.Y.Z/bld`. If exact tag qualification fails, the repository preserves the failed candidate through its existing `vX.Y.Z-failed` archival semantics rather than silently reusing the version.

After a successful release, a separate normal PR advances `main` to the next planned `-SNAPSHOT` version. Release CI never rewrites the development version behind the review workflow.

## Development workflow

Changes use issue → feature branch → draft PR → implementation/test/evidence → review → merge.

Keep real deployment identities, proprietary protocols, credentials, encryption keys and production mappings out of this public repository.

Docker is not required for the normal Java build/unit-test path. It may be introduced later for integration tests that need real external services. Long-term dependency preservation/offline rebuilding is coordinated separately in the meta-project rather than assuming the normal online Maven path will remain available forever.
