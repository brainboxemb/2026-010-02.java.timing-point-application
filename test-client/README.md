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
To exercise the built-in simulated-tag profiles on the Windows development fallback,
start SI-01 with:

```powershell
java -jar app\target\timing-point-app-<version>.jar config\development-simulation.yml
```

That configuration selects the public `simulation` EventData profile
(`N0001` through `N2000`, each with `-A` and `-B` tags) while keeping
simulation TimingData in a dedicated file. The normal `config/application.yml`
continues to use the empty/reference EventData profile.

For the formal `VC-ST1-003` running-system check, follow
[VC-ST1-003.md](VC-ST1-003.md); it uses dedicated verification storage so normal
development TimingData is not modified.

The Development Client reads its target and presentation defaults from one file:

```text
config/development-client.properties
```

The default file configures the startup target host, HTTP :8081, Events :8082,
Remote Shell :8023 and LoggingServer :8030, plus the Development Client's own log
path/level and initial registration prefix. The target host/IP remains editable in the
top target bar at runtime; **Apply target** changes the active host for all external
boundaries without rewriting the config file. Changing the active host disconnects
stateful Events/Terminal/Device Log connections so they cannot silently remain attached
to the previous target.

The resolver accepts both the repository root and `test-client` as the working
directory, so root-level Maven and NetBeans launches use the same file. Use
`--config=<path>` to select another client configuration file.

The window title includes the Development Client software version. **Help → About** shows
the client's own build identity and selected client-config path.

## Current API-first UI

The reviewed tab order is:

```text
API | Events | Device Log | Terminal | Client Log
```

The top target bar starts from the configured host but exposes the host/IP as an editable
field. **Apply target** makes the entered host active for IF-03 HTTP, Events, Remote
Shell and Device Log while the per-boundary ports remain config-driven.

The API control is deliberately clickable. IF-03 HTTP has no persistent connection, so
the control performs an explicit availability **CHECK** and reports CHECKING, READY or
UNREACHABLE instead of pretending that a long-lived HTTP session was opened. Events,
Terminal and Device Log retain their explicit connect/disconnect controls. Client-local
logging is always available independently from SI-01.

### API

**API** is the primary work surface. It combines version/status inspection with the
selected TimingNode controls, registration test input, LogBook/TimingData history and a
raw response/selected-record pane.

The Development Client deliberately does not predict SI-01 domain acceptance from cached
TimingNode state. Once a TimingNode is known, supported Open/Close requests remain available so processed
results can be exercised and inspected. **Open** sends the LocationId currently entered
in the same request; there is no separate Set Location operation. SI-01 remains
authoritative.

The Timing workbench uses two complementary views of committed data:

- **Registrations** is the interpreted operator-oriented projection. Its compact columns
  are **Time | Type | TeamID | Code | action**. Type is `AUTO` or `MAN` for the
  registration origin. Code is only needed for manual registrations to show the
  effective-time origin (`AUTO` or `MAN`); automatic registrations leave Code blank;
- **LogBook / committed TimingData** is the technical/audit view and continues to show
  every committed source record, sequence, Type, Code, UTC-effective time and recorded
  time.

The workbench is laid out as two top-aligned columns. The left column starts with
API/application identity and continues with TimingNode/control and registration input.
The right column starts at the same vertical position with the interpreted Registrations
view above the technical LogBook. TeamID is an interpreted
reference-data value, not a renamed RegistrationId. Until reference/RaceData mapping is
available, the normal view shows TeamID as unresolved while the technical LogBook keeps
the actual RegistrationId. A manual registration with manually entered time therefore deliberately shows
`Type = MAN` and `Code = MAN`; a manual registration whose time was captured
automatically by the client shows `Type = MAN` and `Code = AUTO`. An automatic registration already carries all needed
meaning in `Type = AUTO`, so its Code cell is empty. A REV record keeps the interpreted row present and replaces the icon-only trash action
with a visible **DELETED** marker. The trash action has no text header. When selected,
the client sends the original registration family, LocationId, RegistrationId, time and,
for manual records, the original AUTO/MAN time-source classification to the normal
node-scoped revoke operation. SI-01 appends the REV record; it does not delete or rewrite
the ADD record. The immutable LogBook therefore continues to show both ADD and REV.

Registration input uses a separate prefix and numeric field plus readable local civil
date and hundredth-second clock time. The UI shows the interpreted client time zone next
to the field (for example `Europe/Amsterdam`) and converts that explicit local value to
the canonical UTC API timestamp when sending. **Now** captures the current client
date/time and marks the manual-registration time source as `AUTO`; editing the date or
time marks it as `MAN`.

The **Simulated tags** pane is separate from direct `auto-reg`. It uses
`TAG_SCENARIO_SIMULATION` to start one `simple`, `normal` or `edge` profile
through the real SimulatedAntenna -> AntennaManager -> TagProcessor path. A batch selects
a count and numeric RegistrationId range, uses ascending or seedable pseudo-random order,
and starts scenarios at the configured interval. **Stop** prevents later scenario starts;
an HTTP request already accepted by SI-01 is not undone. The selected profile owns the
TagObservation pattern inside each passage.

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

## Timing workbench behaviour

The API workbench uses the following LogBook/live-event synchronisation behaviour:

- reads the 1..N `nodes[]` status model and addresses one selected TimingNode;
- shows current node state and LocationId;
- sends node-addressed IF-03 Open/Close controls without local lifecycle-state
  permission rules; OPEN carries the entered LocationId as one request;
- discovers `DIRECT_REGISTRATION_SIMULATION` before enabling direct dev `auto-reg`;
- discovers `TAG_SCENARIO_SIMULATION` independently and enables the simulated-tag
  batch controls only when the server composition advertises it;
- exposes normal manual-registration ADD independently from both engineering simulations;
- composes simulated-tag batches client-side from repeated single-scenario API calls;
  count/range/order/interval belong to the client while profile observation timing remains
  server-side simulation behaviour;
- composes RegistrationId from the presentation prefix + numeric field, shows the
  interpreted client time zone explicitly and converts the entered local civil time to
  a hundredth-second canonical UTC API timestamp;
- defaults the interpreted Registrations view to the currently OPEN LocationId and
  provides **All** for synchronized history across locations;
- shows the returned source `seq` as the operation result;
- shows committed TimingData **Type** and **Code** in separate LogBook columns;
- projects ADD/REV registration history in the client and uses the original ADD values
  when the trash action requests an append-only revoke;
- keeps node lifecycle `NODE_INFO` records in the technical LogBook while excluding
  them from the interpreted Registrations projection;
- queries LogBook metadata without downloading the full LogBook;
- loads bounded LogBook pages and merges live committed TimingData by stable
  `TimingNodeId + sequenceNumber` key;
- marks cached history stale during disconnect/reconnect while leaving SI-01 responsible
  for accepting/rejecting supported API commands;
- shows the Timing-view synchronisation state prominently at the top of the API tab,
  above Version/Status;
- connecting **Events** immediately starts the status/capabilities/LogBook baseline
  synchronisation; the client does not wait for a later snapshot to decide to start;
- exposes **Sync view** as the manual re-synchronisation action while Events is connected;
- buffers live status/TimingData events, including an initial STATUS_SNAPSHOT, while the
  HTTP baseline is rebuilt, applies them after that baseline in delivery order and only
  then marks the Timing view LIVE;
- keeps Open/Close/auto-reg controls disabled while the Timing view is not LIVE.

The current SI-01 runtime may compose one TimingNode, but the client model does not
hard-code that limitation. With one node selection is implicit; with multiple reported
nodes the same API workbench addresses the selected node.

The simulated-tag pane is an engineering scenario driver, not a raw RFID/tag/filter
editor. The Development Client still does not expose arbitrary TagObservation injection,
TagProcessor/filter mutation, StageStartTimes/NextUpTeams/RaceData editors or
Upstream/DebugConnector simulation UI. Those capabilities require their own public
engineering/client use case before they are added here.

### Current IF-03 resources used

```text
GET  /api/v1/version
GET  /api/v1/status
GET  /api/v1/capabilities

POST /api/v1/node/{id}/open             {"locationId": <positive integer>}
POST /api/v1/node/{id}/close
POST /api/v1/node/{id}/registration/manual
POST /api/v1/node/{id}/registration/revoke

GET  /api/v1/node/{id}/logbook
GET  /api/v1/node/{id}/logbook?from=...&limit=...
GET  /api/v1/node/{id}/logbook?last=...

POST /api/v1/dev/node/{id}/auto-reg
POST /api/v1/dev/node/{id}/simulation/registration

WS   /api/v1/events
```

## Documentation screenshots

Documentation screenshots use a deterministic Development Client
**documentation/demo mode** with public synthetic fixture data.

The documentation flow is:

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

The API-first workbench is the primary screenshot view; Events/Logs/Terminal are
captured only when they add useful evidence.
Screenshot generation remains presentation evidence rather than behavioural proof.

## Manual verification

The meta-repository VTS owns `VC-ST1-003` as the manual running-system Development
Client verification case. [VC-ST1-003.md](VC-ST1-003.md) is the repository-local
execution checklist and shall not redefine the VTS.

## Verify

```powershell
.\mvnw.cmd -f test-client\pom.xml verify
```

API HTTP/WebSocket, live-log and remote-shell client logic remain outside the JavaFX event
handlers so the UI does not become the owner of protocol semantics. The API client code
remains independent of SI-01 implementation classes, matching the headless black-box
client boundary.
