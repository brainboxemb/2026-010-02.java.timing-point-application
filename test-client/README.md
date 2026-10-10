# Event Timing Engineering Desktop Client

The `test-client/` Maven project implements **SI-02 — Engineering Desktop Client** for
development, integration, commissioning, diagnostics and system testing against the
supported public boundaries of the **Timing Point Application** (SI-01).

The directory name remains `test-client` for repository continuity. The software-item
requirements and architecture are defined by 41-02-SSD in the meta repository; SDE-03
defines the Engineering Client development/UI baseline.

SI-02 is a standalone desktop Maven application with its own Java 21 runtime and no
dependency on `timing-point-core` or `timing-point-app`. Repository co-location does
not remove the software-item boundary.

## Baseline

- Java 21
- JavaFX 21.0.10
- BentoFX 0.16.0 workbench
- Maven Wrapper from the repository root
- Jackson 2.21.2 for independent JSON parsing
- independent client services for IF-03, Remote Shell and live diagnostics

The JavaFX Maven setup follows the normal OpenJFX Maven model: JavaFX modules and
platform-specific native libraries are resolved as Maven dependencies. The executable
entry point is `TestClientApplication`, a plain Java class. The actual JavaFX subclass
is kept internal as `TestClientFxApplication`; this prevents the JVM or an IDE from
treating the selected main class as a special JavaFX launcher target.

## External boundaries

The Engineering Client communicates with SI-01 only through supported external
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

With `JAVA_HOME` pointing to a JDK 21 installation:

```powershell
.\mvnw.cmd -f test-client\pom.xml clean javafx:run
```

Start SI-01 separately from the normal Java-8 project/NetBeans run configuration.
To exercise the built-in simulated-tag profiles on the Windows development fallback,
start SI-01 with:

```powershell
java -jar app\target\timing-point-app-<version>.jar config\development-simulation.yml
```

That configuration selects the public `simulation` EventData profile for
`RT-A-0001` through `RT-A-2000`, using physical tag pairs
`TT-A-NNNN-1` and `TT-A-NNNN-2`, while keeping simulation TimingData in a
dedicated file. The normal `config/application.yml` uses the algorithmic
`reference` EventData profile.

For the formal `VC-ST1-003` running-system check, follow
[VC-ST1-003.md](VC-ST1-003.md); it uses dedicated verification storage so normal
development TimingData is not modified.

## Windows app-image

With `JAVA_HOME` pointing to a full JDK 21, build the self-contained Windows
application image with:

```powershell
.\test-client\package-windows.ps1
```

The script verifies JDK 21, builds the client, collects its runtime Maven
dependencies and invokes the JDK `jpackage` tool. The default output is:

```text
test-client\target\jpackage\EventTimingEngineeringClient\
```

This is deliberately an app-image rather than an MSI/installer. Installer and
auto-update policy are separate later decisions.

The Engineering Client reads its target and presentation defaults from one file:

```text
config/engineering-client.properties
```

The default file configures the startup target host, HTTP :8081, Events :8082,
Remote Shell :8023 and LoggingServer :8030, plus the Engineering Client's own log
path/level and initial registration prefix. The target host/IP remains editable in the
top target bar at runtime; **Apply target** changes the active host for all external
boundaries without rewriting the config file. Changing the active host disconnects
stateful Events/Terminal/Device Log connections so they cannot silently remain attached
to the previous target.

The resolver accepts both the repository root and `test-client` as the working
directory, so root-level Maven and NetBeans launches use the same file. Use
`--config=<path>` to select another client configuration file.

The window title includes the Engineering Client software version. **Help → About** shows
the client's own build identity and selected client-config path.

On Windows 10/11, the main window uses a native-caption extension to put
**View/Help and the application title in one Windows title-bar row**, with
the host and boundary controls in a compact toolbar beneath. The extension
retains the Win32 decorated-window frame and integrates system caption
hit-testing, drag, minimize/maximize/close, and Windows Snap support. BentoFX
floating windows are not modified. On other OSes the JavaFX MenuBar stays below
the platform's own native caption. The native extension can be disabled with
`-Dsi02.nativeTitleBar=false` for troubleshooting; manual Windows verification
is required for maximize/restore, high-DPI, Windows Snap and detached windows.

## Current Engineering workbench

The Engineering Client uses BentoFX for workbench composition. Functional panes remain
ordinary JavaFX nodes and do not depend on BentoFX APIs.

The default composition is:

```text
left / controls & diagnostics         right / data
-----------------------------------   ------------------------
Systems  | TimingNode / Registration  Registrations
Explorer | / Simulation (tabs)        ------------------------
-----------------------------------   LogBook
Device Log                            ------------------------
-----------------------------------   Raw Data / Events (tabs)
Terminal
-----------------------------------
Client Log
```

The top bar has Host/IP followed immediately by **Connect**, which applies
the chosen host and independently checks/connects API, Events, Terminal and
Device Log; no startup auto-connect. The four corresponding option buttons
retain short stable labels without Connect/Disconnect text. Green means that
boundary has actually connected or (for stateless HTTP) passed its API check;
yellow means busy, red means failed, neutral means disconnected. Clicking an
individual button connects/disconnects that interface (or checks API again).
Tooltips show status and operation. Client Log is always local and active;
individual boundary ports stay in configuration.

The API control is deliberately clickable. IF-03 HTTP has no persistent connection, so
the control performs an explicit availability **CHECK** and reports CHECKING, READY or
UNREACHABLE instead of pretending that a long-lived HTTP session was opened. Events,
Terminal and Device Log remain independently toggleable without changing button captions. Client-local
logging is always available independently from SI-01. The Systems pane shows the
current target and its reported TimingNodes, state and location, and provides
**Sync view**. Click a node to select it for node-scoped controls.

Docking panes can be dragged into separate windows. Empty original dock
areas are pruned, so the remaining panes use that space. A visible **↩** action
in a moved panel's tab header or the tab's **right-click → Dock back** action
restores its original dock area, including after that area was pruned.
**View → Reset layout** reconstructs the entire original dock arrangement,
without changing the connected target or SI-01 state.

### Timing, registration and data panes

**TimingNode**, **Registration** and **Simulation** share the local control-tab area.
Application/version identity is shown with the Registrations pane. The Engineering Client
deliberately does not predict SI-01 domain acceptance from cached TimingNode state. Once a
TimingNode is known, supported Open/Close requests remain available so processed results
can be exercised and inspected. **Open** sends the LocationId currently entered in the
same request; there is no separate Set Location operation. SI-01 remains authoritative.

The right-hand workbench column keeps two complementary views of committed data visible as
separate dock areas:

- **Registrations** is the interpreted user-facing projection. Its compact columns are
  **Time | Type | TeamID | Code | action**. Type is `AUTO` or `MAN` for the
  registration origin. Code is only needed for manual registrations to show the
  effective-time origin (`AUTO` or `MAN`); automatic registrations leave Code blank;
- **LogBook / committed TimingData** is the technical/audit view and continues to show
  every committed source record, sequence, Type, Code, UTC-effective time and recorded
  time. Its dock header displays the source record count.

**Raw Data** and **Events** share the lower-right detail area as tabs. **Device Log**,
**Terminal** and **Client Log** are independent dock areas in the middle column, rather
than application-level tabs.

Rendering is change-driven: a semantic TimingNode status change updates controls
and the Systems tree; a new committed timing record updates only the history
views. Duplicate WebSocket and HTTP status/record messages no longer redraw the
same information. Queued timing events can share one JavaFX history pass, while
an in-flight command keeps its controls disabled throughout concurrent events.

The Registrations and LogBook tables each have one empty final presentation row.
Selecting that row follows the latest incoming records. Selecting a historical row
or scrolling upward pauses following for that table only; selecting the empty
final row resumes. This row is never an actual registration or LogBook record.
TeamID is an interpreted reference-data value, not a renamed RegistrationId. In the
default/reference profile, `RT-A-NNNN` projects directly to TeamID `NNNN`;
`RT-R-NNNN` remains unresolved until reserve assignment data is available. The
technical LogBook always keeps the actual RegistrationId. A manual registration with manually entered time therefore deliberately shows
`Type = MAN` and `Code = MAN`; a manual registration whose time was captured
automatically by the client shows `Type = MAN` and `Code = AUTO`. An automatic registration already carries all needed
meaning in `Type = AUTO`, so its Code cell is empty. A REV record keeps the interpreted row present and replaces the icon-only trash action
with a visible **DELETED** marker. The trash action has no text header. When selected,
the client sends the original registration family, LocationId, RegistrationId, time and,
for manual records, the original AUTO/MAN time-source classification to the normal
node-scoped revoke operation. SI-01 appends the REV record; it does not delete or rewrite
the ADD record. The immutable LogBook therefore continues to show both ADD and REV.

Registration input uses a compact shared form with RegistrationId (prefix + number),
local date/time and a Now button. The manual submit button directly shows its
AUTO/MAN time-source classification; normally supported direct capability no
longer adds an extra status row. Unsupported capability is explained only while
the connected status is LIVE.

Registration input uses a separate prefix and numeric field plus readable local civil
date and hundredth-second clock time. The UI shows the interpreted client time zone next
to the field (for example `Europe/Amsterdam`) and converts that explicit local value to
the canonical UTC API timestamp when sending. **Now** captures the current client
date/time and marks the manual-registration time source as `AUTO`; editing the date or
time marks it as `MAN`.

**Direct auto-reg** injects an accepted registration at the TimingNode and
does not simulate reading an antenna. Its independent **Direct time** choice
defaults to **TimingNode clock** (omit the `time` field from IF-03), or select
**Provided time** to send the visible local date/time translated to UTC for
repeatable replay. Manual-entry edits do not change this choice. Client Log
and the operation result show node, RegistrationId, acceptance sequence and
chosen time source; SI-01 LogBook is the authority for committed records and
optional `tagSrc`/`timeSrc` provenance (old records may omit those).

The **Simulated tags** pane is separate from direct `auto-reg`. It uses
`TAG_SCENARIO_SIMULATION` to start one `simple`, `normal` or `edge` profile
through the real SimulatedAntenna -> AntennaManager -> TagProcessor path. A batch selects
a count and numeric RegistrationId range, uses ascending or seedable pseudo-random order,
and starts scenarios at the configured interval. **Stop** prevents later scenario starts;
an HTTP request already accepted by SI-01 is not undone. The selected profile owns the
TagObservation pattern inside each passage.

### Events

The **Events** detail tab uses Java 21's built-in WebSocket client and keeps raw events
visible. Its connection is controlled from the target bar. `STATUS_SNAPSHOT` /
`STATUS_CHANGED` and `TIMING_DATA_COMMITTED` are parsed separately while unknown
future event types remain visible as raw diagnostics.

### Device Log

**Device Log** is an independent dock area. It shows live records from the connected SI-01
`LoggingServer`. Its dock header shows the effective log-level abbreviation
`[T/D/I/W/E]`; right-click inside the log to change the SI-01 runtime level
when connected. An IF-03 registration request is not itself a device-log line.

### Terminal

**Terminal** is an independent dock area containing the line-oriented Remote Shell client.
Its connection is controlled from the target bar. It is raw UTF-8 TCP, not an SSH/Telnet
emulator.

### Client Log

**Client Log** is an independent dock area containing retained local Engineering Client
startup/configuration/connection/request diagnostics and an independent current-level
display and runtime threshold control through the dock header and right-click menu.
It remains available and controllable when SI-01 is offline. A client-level change is runtime-only; the configured startup level is restored
on the next Engineering Client start.

Device Log and Client Log remain independent. Both use the readable project log-line shape
`HH:mm:ss.SSS - [LEVEL] - message - [sourceClass.sourceMethod]`; Engineering Client
records use their actual caller source context rather than one generic client marker.

## Timing workbench behaviour

The SI-02 client/application state uses the following LogBook/live-event synchronisation behaviour:

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
- shows the Timing-view synchronisation state in the dockable Systems tree,
  separate from registration controls and source-data views;
- connecting **Events** immediately starts the status/capabilities/LogBook baseline
  synchronisation; the client does not wait for a later snapshot to decide to start;
- exposes **Sync view** as the manual re-synchronisation action while Events is connected;
- buffers live status/TimingData events, including an initial STATUS_SNAPSHOT, while the
  HTTP baseline is rebuilt, applies them after that baseline in delivery order and only
  then marks the Timing view LIVE;
- keeps Open/Close/auto-reg controls disabled while the Timing view is not LIVE.

One `EngineeringSystemContext` represents one connected SI-01 system instance. The current
UI presents one primary context, while the client model and workbench avoid global target
state so later multi-system/scripted workflows can create additional instances.

The current SI-01 runtime may compose one TimingNode, but the client model does not
hard-code that limitation. With one node selection is implicit; with multiple reported
nodes the same API workbench addresses the selected node.

The simulated-tag pane is an engineering scenario driver, not a raw RFID/tag/filter
editor. The Engineering Client still does not expose arbitrary TagObservation injection,
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

Documentation screenshots use a deterministic Engineering Client
**documentation/demo mode** with public synthetic fixture data.

The documentation flow is:

```text
GitHub Actions
  +-- JDK 21 / pinned JavaFX
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

The workbench deliberately uses flat, square-edged JavaFX controls throughout,
similar to a conventional Windows IDE. **Events** shows the raw JSON stream only;
the synchronized current state belongs in Systems, and raw event payloads remain
independently available for engineering inspection.
