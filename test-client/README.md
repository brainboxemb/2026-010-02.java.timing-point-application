# Event Timing Development Client

The `test-client/` Maven project is the project's standalone **Development Client** for
interactive development, integration and diagnostics against the public boundaries of the
**Timing Point Application** (SI-01).

The directory/module name remains `test-client` for now. The development role and UI
baseline are defined in the meta-repository SDE:

- [50-SDE-03 — Development Client development and UI baseline](https://github.com/brainboxemb/2026-010-01.meta.event-timing-software/blob/main/docs/50-SDE-03-development-client.md)

This application is **not SI-02** and is not part of the Java-8/Pi SI-01 runtime. It is a
standalone desktop Maven project with no dependency on `timing-point-core` or
`timing-point-app`. Repository co-location is intentional while SI-01 public interfaces
and the Development Client evolve together.

## Baseline

- Java 17
- JavaFX 21.0.10
- Maven Wrapper from the repository root
- Jackson 2.21.2 for independent JSON parsing
- independent client services for IF-03, Remote Shell and live diagnostics

The JavaFX Maven setup follows the normal OpenJFX Maven model: JavaFX modules and
platform-specific native libraries are resolved as Maven dependencies. The executable
entry point is `TestClientApplication`, a plain Java class. The actual JavaFX subclass
is kept internal as `TestClientFxApplication`; this prevents the JVM or an IDE from
treating the selected main class as a special JavaFX launcher target.

## External boundaries

The Development Client communicates with SI-01 only through supported external
interfaces.

Current client services are:

```text
TestClientFxApplication
  +-- ApiClient             IF-03 HTTP / JSON
  +-- ApiEventClient        IF-03 WebSocket
  +-- RemoteShellClient     line-oriented Remote Shell
  +-- LiveLogClient         LoggingServer diagnostics socket
```

JavaFX event handlers do not own HTTP/WebSocket, shell or live-log protocol semantics.
The client must not import SI-01 implementation classes or mutate SI-01 domain state
directly.

## Run on Windows

With `JAVA_HOME` pointing to a JDK 17 installation:

```powershell
.\mvnw.cmd -f test-client\pom.xml clean javafx:run
```

Start SI-01 separately from the normal Java-8 project/NetBeans run configuration.
For the formal Step-4 V04 / `VC-ST1-003` running-system check, follow
[STEP4-DEMO.md](STEP4-DEMO.md); it uses a dedicated demo storage file so normal
development TimingData is not modified.

The Development Client reads its target and presentation defaults from one file:

```text
config/development-client.properties
```

The default file configures HTTP :8081, Events :8082, Remote Shell :8023 and
LoggingServer :8030 on `127.0.0.1`, plus the Development Client's own log path/level
and initial registration prefix. The resolver accepts both the repository root and
`test-client` as the working directory, so root-level Maven and NetBeans launches use
the same file. Use `--config=<path>` to select another client configuration file.

The window title includes the Development Client software version. **Help → About** shows
the client's own build identity and selected client-config path.

## Current API-first UI

The reviewed tab order is:

```text
API | Events | Device Log | Terminal | Client Log
```

The top target bar is config-driven. It identifies the target host and shows separate
port/state controls for IF-03 HTTP, Events, Remote Shell and SI-01/Device logging.
Client-local logging is always available independently from SI-01.

### API

**API** is the primary work surface. It combines version/status inspection with the
selected TimingNode controls, registration test input, LogBook/TimingData history and a
raw response/selected-record pane.

The Development Client deliberately does not predict SI-01 domain acceptance from cached
TimingNode state. Once a TimingNode is known, supported Open/Close requests remain available so processed
results can be exercised and inspected. **Open** sends the LocationId currently entered
in the same request. The separate IF-03 Set Location operation remains available to
protocol/automated tests, but is not a normal workbench control. SI-01 remains
authoritative.

Registration input uses a separate prefix and numeric field plus readable local date and
whole-second clock time. The client converts that structured value to the canonical API
timestamp only when sending the request.

### Events

The **Events** tab uses Java 17's built-in WebSocket client and keeps raw events visible.
Its connection is controlled from the target bar. `STATUS_SNAPSHOT` /
`STATUS_CHANGED` and `TIMING_DATA_COMMITTED` are parsed separately while unknown
future event types remain visible as raw diagnostics.

### Device Log

**Device Log** is a top-level tab. It shows live records from the connected SI-01
`LoggingServer` and has its own current-level display and temporary runtime level control.

### Terminal

The **Terminal** tab remains the line-oriented Remote Shell client with its connection
controlled from the target bar. It is raw UTF-8 TCP, not an SSH/Telnet emulator.

### Client Log

**Client Log** is the final top-level tab. It contains retained local Development Client
startup/configuration/connection/request diagnostics and has an independent current-level
display and runtime threshold control. It remains available and controllable when SI-01 is
offline. A client-level change is runtime-only; the configured startup level is restored
on the next Development Client start.

Device Log and Client Log remain independent. Both use the readable project log-line shape
`HH:mm:ss.SSS - [LEVEL] - message - [sourceClass.sourceMethod]`; Development Client
records use their actual caller source context rather than one generic client marker.

## Step-4 behaviour retained inside the API workbench

The LogBook/live-event synchronisation from the Step-4 Timing implementation remains in
the API workbench:

- reads the 1..N `nodes[]` status model and addresses one selected TimingNode;
- shows current node state and LocationId;
- sends node-addressed IF-03 Open/Close controls without local lifecycle-state
  permission rules; OPEN carries the entered LocationId as one request;
- discovers `DIRECT_REGISTRATION_SIMULATION` before enabling dev `auto-reg`;
- composes RegistrationId from the presentation prefix + numeric field and converts
  readable date/time to the canonical API timestamp at send time;
- shows the returned source `seq` as the operation result;
- shows committed TimingData **Type** and **Code** in separate LogBook columns;
- queries LogBook metadata without downloading the full LogBook;
- loads bounded LogBook pages and merges live committed TimingData by stable
  `TimingNodeId + sequenceNumber` key;
- marks cached history stale during disconnect/reconnect while leaving SI-01 responsible
  for accepting/rejecting supported API commands;
- exposes **Sync view** as the manual resynchronisation action; it refreshes the client-side status/capabilities/LogBook baseline and does not rebuild SI-01 domain data;
- buffers live status/TimingData events that arrive during resynchronisation, applies them
  after the HTTP status/LogBook baseline in delivery order, and only then marks
  the Timing view LIVE.

The current SI-01 runtime may compose one TimingNode, but the client model does not
hard-code that limitation. With one node selection is implicit; with multiple reported
nodes the same API workbench addresses the selected node.

The Step-4 slice deliberately does **not** add RFID/tag/filter controls,
StageStartTimes/NextUpTeams/RaceData editors or Upstream/DebugConnector simulation UI.
Those remain later increments.

### Current IF-03 resources used

```text
GET  /api/v1/version
GET  /api/v1/status
GET  /api/v1/capabilities

PUT  /api/v1/node/{id}/location        {"locationId": <positive integer>}  # protocol/test use
POST /api/v1/node/{id}/open             {"locationId": <positive integer>}
POST /api/v1/node/{id}/close

GET  /api/v1/node/{id}/logbook
GET  /api/v1/node/{id}/logbook?from=...&limit=...
GET  /api/v1/node/{id}/logbook?last=...

POST /api/v1/dev/node/{id}/auto-reg

WS   /api/v1/events
```

## Documentation screenshots

The planned documentation workflow uses a deterministic Development Client
**documentation/demo mode** with public synthetic fixture data.

The intended CI flow is:

```text
GitHub Actions
  +-- JDK 17 / pinned JavaFX
  +-- virtual display when required
  +-- deterministic documentation fixture
  +-- render named JavaFX view
  +-- application-owned scene/window snapshot
  +-- retain PNG as generated documentation evidence
```

This is intentionally not generic desktop mouse/keyboard automation. A JavaFX-owned
snapshot can wait until the scene is rendered and does not depend on window-manager
coordinates.

The first screenshot proof should stay small; the API-first workbench is the primary
candidate, with Events/Logs/Terminal captured only when they add useful evidence.
Screenshot generation remains presentation evidence rather than behavioural proof.

## VC-ST1-003 transition

Issue #127 / `VC-ST1-003` owns the manual running-system Development Client
verification against the current API-first workbench. `STEP4-DEMO.md` is the maintained
procedure for that baseline.

## Verify

```powershell
.\mvnw.cmd -f test-client\pom.xml verify
```

API HTTP/WebSocket, live-log and remote-shell client logic remain outside the JavaFX event
handlers so the UI does not become the owner of protocol semantics. The API client code
remains independent of SI-01 implementation classes, matching the headless black-box
client boundary.
