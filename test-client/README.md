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

## Client-side Lua scripting

The **Scripts** dock tab (beside Simulation) runs local `.lua` engineering
scripts. Open a file with **Open .lua**, drop it onto the Scripts panel, or
edit the script in place; **Run** is always explicit and dropping a file does
not execute it. **Stop** cancels the script, including a CPU-bound Lua loop
or a delay; it cannot undo a simulation request already accepted by SI-01.
Scripts run in a dedicated worker, not on the JavaFX thread or SI-01 runtime.

Use the small supported scripting facade, not internal Java objects:

```lua
timing.log("Example batch")
timing.batch{count=10, from=1, to=2000, order="random",
             seed=42, intervalMs=500, profile="normal"}
timing.simulate("A0100", "simple")
timing.sleep(1000)
```

`order` accepts `linear` or `random` (seeded/reproducible); the profiles
are `simple`, `normal` or `edge`. Registrations use the same
`A%04d` generator as the Simulation GUI. The currently selected
LIVE TimingNode is captured when Run starts; disconnect, node change or
loss of connection stops the script. Simulation calls separately require the
TAG_SCENARIO_SIMULATION capability. Script and GUI simulation batches cannot
run concurrently. IF-03 remains the only operation boundary.
See the [Lua language/API reference](docs/lua-scripting.md) for supported calls,
requirements and troubleshooting, and [examples/batch.lua](examples/batch.lua)
for an editable example.

The interpreter intentionally does **not** expose `luajava`, filesystem
`io`, process `os`, package loaders or Java reflection. These restrictions
reduce accidental misuse; Lua scripts are still **trusted, operator-selected
engineering inputs**, not a secure sandbox for hostile programs. No script is
executed automatically on startup or file drop.

## Baseline

- Java 21
- JavaFX 21.0.10
- BentoFX 0.16.0 workbench
- Maven Wrapper from the repository root
- Jackson 2.21.5 for client JSON parsing
- shared public `event-upstream-data` / TimingData contracts for IF-06 semantics
- independent client services for IF-03, Remote Shell and live diagnostics

**JavaFX runtime consistency:** SI-02 requires all `javafx.base`,
`javafx.graphics`, `javafx.controls` and `javafx.fxml` JARs to resolve
from the same Maven version (21.0.10). A NetBeans run action that adds an
old JavaFX SDK or JARs on its own classpath/module-path can compile the
application but fail at runtime with `NoSuchMethodError` (for example,
`MappingChange.<init>` or `ObservableValue.map` via BentoFX). Do not
try to fix this by changing TableView refresh logic. Use the Maven Wrapper
command below or a packaged Windows app-image, remove IDE-injected old
JavaFX libraries, and ensure the Run main class is `TestClientApplication`.
The plain main launcher checks JavaFX versions **before** creating any UI
and prints loaded module origins with a corrective command on mismatch.

The JavaFX Maven setup follows the normal OpenJFX Maven model: JavaFX modules and
platform-specific native libraries are resolved as Maven dependencies. The executable
entry point is `TestClientApplication`, a plain Java class. The actual JavaFX subclass
is kept internal as `TestClientFxApplication`; this prevents the JVM or an IDE from
treating the selected main class as a special JavaFX launcher target.

The checked-in NetBeans Run/Debug actions start SI-02 with Maven `exec:exec` in a
**separate child JVM** rather than `exec:java` inside Maven's own JVM. The child
executable is `${java.home}/bin/java`, so it uses the same Java 21-capable JDK that
successfully builds the client. The action supplies the Maven runtime classpath,
enables long-classpath handling for Windows, and injects the NetBeans JDWP address
into the child JVM for Debug. Maven/JGit/ForkJoin build-tool threads therefore do
not appear as Engineering Client runtime threads.

## External boundaries

The Engineering Client communicates with SI-01 only through supported external
interfaces.

Current client services are:

```text
TestClientFxApplication
  +-- ApiClient             IF-03 HTTP / JSON
  +-- ApiEventClient        IF-03 WebSocket + typed IF-06 UpstreamData
  +-- CentralSystemSimulator Central-side IF-06 state (transport-independent)
  +-- RemoteShellClient     line-oriented Remote Shell
  +-- LiveLogClient         LoggingServer diagnostics socket
```

JavaFX event handlers do not own HTTP/WebSocket, shell or live-log protocol semantics.
The client must not import SI-01 core/application implementation classes or mutate SI-01
domain state directly. It may consume the shared public Event Timing semantic artifacts
used at IF-05/IF-06 boundaries.

## Run on Windows

With `JAVA_HOME` pointing to a JDK 21 installation, install the shared public
IF-06 contract into the local Maven repository and then run the standalone client:

```powershell
.\mvnw.cmd -pl shared/upstream-data -am -DskipTests install
.\mvnw.cmd -f test-client\pom.xml clean javafx:run
```

Start SI-01 separately from the normal Java-8 project/NetBeans run configuration.
To exercise the built-in simulated-tag profiles on the Windows development fallback,
start SI-01 with:

```powershell
java -jar app\target\timing-point-app-<version>.jar config\development-simulation.yml
```

That configuration uses the same algorithmic `reference` EventData provider
as `config/application.yml`: normal tag/registration mappings span
`A0001` through `A9999`, with paired tags such as `A0001A` and `A0001B`.
Its dedicated TimingData file isolates engineering experiments from normal
development history. A simulated antenna, not a special EventData provider,
generates tag observations.

The IF-03 `TAG_SCENARIO_SIMULATION` capability is now determined by an actual
composed `SimulatedAntenna` (plus single-system/single-node topology and nonempty
EventData), not by a particular EventData provider name. On the one-node Windows
development fallback, the normal `reference` profile can therefore support
simulated tags as well. Physical-antenna compositions do not advertise it.
The **API** tab displays the raw capability IDs with their `supported` and
`enabled` values after an Events baseline sync. Before sync, the values are
unknown—not disabled.

For the formal `VC-ST1-003` running-system check, follow
[VC-ST1-003.md](VC-ST1-003.md); it uses dedicated verification storage so normal
development TimingData is not modified.

The default script in **Scripts** is a harmless local Lua smoke test; uncomment the
`timing.batch` example only when the connected SI-01 advertises an enabled
`TAG_SCENARIO_SIMULATION` capability. A capability error reports SI-01's
current response; reconnect/restart SI-01 after switching startup profiles.

## Windows app-image

With `JAVA_HOME` pointing to a full JDK 21, build the self-contained Windows
application image with:

```powershell
.\test-client\package-windows.ps1
```

The script verifies JDK 21, builds the client, collects its runtime Maven
dependencies, and invokes the JDK `jpackage` tool with the branded
`test-client/src/main/resources/icons/event-timing.ico` Windows launcher icon.
The same graphic is loaded from `event-timing.png` by JavaFX, including during
an ordinary Maven/NetBeans run.
The editable icon artwork is stored in `test-client/assets/event-timing.svg`.
The stopwatch dial is deliberately drawn **large** (approximately 82% of the
icon including its stroke) so it stays legible on Windows taskbars at 16–32px.
The ICO includes explicit 16, 24, 32, 48, 64, 128 and 256px variants, while
JavaFX uses the 256px PNG. To regenerate both from SVG when the artwork changes:

```powershell
python -m pip install cairosvg Pillow
python test-client/assets/generate_icons.py
```

These Python packages are needed **only for artwork generation**, never for
Maven/JavaFX or the Windows launcher. A new build must be started after
regeneration. If the taskbar still shows the previous icon, unpin the old
shortcut and pin the rebuilt packaged launcher because Windows caches icons. The default output is:

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
path/level and the LocationIds initially known to the Central System Simulator through
`central.locationIds`. RegistrationId input is a single canonical value such as
`A0001`; it is not a client-configuration prefix. The target host/IP remains editable
in the top target bar at runtime; **Apply target** changes the active host for all external
boundaries without rewriting the config file. Changing the active host disconnects
stateful Events/Upstream, Terminal and Device Log connections so they cannot silently remain
attached to the previous target.

The resolver accepts both the repository root and `test-client` as the working
directory, so root-level Maven and NetBeans launches use the same file. Use
`--config=<path>` to select another client configuration file.

The window title includes the Engineering Client software version. **Help → About** shows
the client's own build identity and selected client-config path.

On Windows 10/11, the caption shows the application icon and flat
**View / Help** menus on the left, plus the **application name and version**
centered within the available space. The last 138 px are explicitly reserved
for the caption library's three window buttons to prevent text/control overlap.
Host and connection options stay on the next row. Both a real JavaFX Stage
icon and a multi-resolution `.ico` for the Windows packaged launcher are
provided (the generic Java taskbar icon should no longer be used). Menu items
remain JavaFX controls; the caption extension performs Win32 hit testing.
If Windows still shows a previously pinned Java icon, unpin the old shortcut
and re-pin the new launcher to refresh its cached taskbar identity.
The detached BentoFX windows remain ordinary native-decorated windows. On
other systems JavaFX MenuBar stays below the OS titlebar. Use
`-Dsi02.nativeTitleBar=false` to revert to the ordinary titlebar if needed.
Packaged Windows smoke tests check startup but cannot prove caption visuals,
drag/double-click, Snap Layouts or DPI correctness; verify interactively.

## Current Engineering workbench

The Engineering Client uses BentoFX for workbench composition. Functional panes remain
ordinary JavaFX nodes and do not depend on BentoFX APIs.

The default composition is:

```text
left / controls & diagnostics         right / data
-----------------------------------   ------------------------
A|Systems / C|Nodes / C|Locations    Registrations [LocationId]
(tabs)     | TimingNode / API /        ------------------------
           | Registration / Simulation
           | C|RaceData / Scripts
-----------------------------------   LogBook
Device Log                            ------------------------
-----------------------------------   Raw Data / Events (tabs)
Terminal
-----------------------------------
Client Log
```

The compact toolbar vertically aligns **Host**, the target IP and all link
buttons. The main power-icon/text **Connect** button checks HTTP API
availability and opens normal **Events**, Terminal and Device Log together; once one
or more stateful links are active, it becomes **Disconnect**, allowing the
user to close all of them with the same action. It is green when all three
stateful links are connected, amber when partially connected or connecting,
and neutral when disconnected. The API's HTTP check is stateless, so it
cannot be "disconnected" and has its own reachability button.

**Central** is deliberately separate from that aggregate connection. Normal Events uses
`/api/v1/events` and does not make SI-01 believe a Central upstream peer exists.
Clicking **Central** reconnects the same `ApiEventClient` as
`/api/v1/events?role=upstream`; that one role-specific session continues to carry
normal IF-03 events and additionally carries typed `UPSTREAM_DATA` in both directions.
Turning Central off reconnects the same client to ordinary Events again. There is never a
second simultaneous WebSocket client service. A changed Host
does not silently redirect existing links: disconnect before switching.
The redundant `● Client Log` label is gone from the toolbar: Client Log
is a local, always-running dock pane whose own tab identifies it.
Individual boundary ports stay in the configuration.

The API control is deliberately clickable. IF-03 HTTP has no persistent connection, so
the control performs an explicit availability **CHECK** and reports CHECKING, READY or
UNREACHABLE instead of pretending that a long-lived HTTP session was opened. Events,
Terminal and Device Log remain independently toggleable without changing button captions. Client-local
logging is always available independently from SI-01. The Systems pane shows the
current target and its reported TimingNodes, state and location, and provides
**Sync view**. Click a node to select it for node-scoped controls.

All built-in workbench tabs are non-closable: they intentionally have **no ✕
in their tab header**. The tabs still switch, drag and detach normally, but
the client cannot silently lose an essential live-status, log or diagnostic
pane. A future Window/Show panels menu could introduce optional hiding
together with a reliable restore action. The native close button of a
separate floating window is distinct from a dock-tab close button.

Docking panes can be dragged into separate windows. Empty original dock
areas are pruned, so the remaining panes use that space. Each BentoFX dock
header has standard framework controls: **▼** selects tabs that overflow the
available header space, while **≡** opens the dock-area menu. The menu's
**Tab position** submenu moves headers to Top/Bottom/Left/Right. When the
selected tab is away from its original area, the same ≡ menu offers
**Dock selected tab back**. This works in floating windows as well as the main
workbench, including when the old home area was pruned. No extra custom ↩
tab button or separate right-click tab menu is needed.
**View → Reset layout** reconstructs the entire original dock arrangement,
without changing the connected target or SI-01 state.

### Timing, registration and data panes

**TimingNode**, **API**, **Registration** and **Simulation** share the local control-tab area.
Application/version identity and API diagnostic actions are in the **API** tab.
The Engineering Client
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

The maintained SI-02 UI design/review rules are documented in
[44-02-GPD Engineering Client design rules](https://github.com/brainboxemb/2026-010-01.meta.event-timing-software/blob/main/docs/44-02-GPD-engineering-client-design-rules.md).
The JavaFX stylesheet centralizes named palette/status tokens and shared form,
log and data-pane spacing rather than duplicating inline styles. Technical
updates reject stale async baseline, selected-node history, command and
target-bound API-check results after disconnect, a newer sync, target or
TimingNode selection; high-volume local/device logs are batched into
small JavaFX update passes.

Rendering is change-driven: a semantic TimingNode status change updates controls
and the Systems tree; a newly committed timing record updates the history views.
Duplicate WebSocket and HTTP messages no longer redraw identical data. Queued
timing events share a JavaFX history update, and commands keep their controls
disabled even while other status messages arrive.

The **API** tab sits next to **TimingNode** in the Engineering workbench.
It contains the remote Application, Version and API version diagnostics and
explicit Refresh version / Refresh status actions. The Registrations pane
is only the interpreted registration table. Its tab shows the effective scope:
**Registrations [24]** for an OPEN LocationId 24, **Registrations [All]**
when the user explicitly selects all locations, or **Registrations [–]**
when no current OPEN location is available. The default is the currently
OPEN location of the selected TimingNode. Click the dock's standard ≡ button
to select **Current open location** or **All locations** directly (no nested
submenu); the checked option updates immediately and survives resetting the
dock layout. When the selected node opens, closes, or changes location, the
scope caption follows without rebuilding the entire workbench.
The Time column displays local times in the operator's configured system
timezone. Table headers use regular-weight text, and Device Log, Terminal
and Client Log use compact two-to-three-pixel panel margins. The Systems
Explorer hides its redundant **LIVE** heading, while warnings like
NOT SYNCED / SYNCING / STALE remain visible.

The current-location Registrations table follows SI-01's maintained NodeService
projection rather than reconstructing validity from local LogBook history. A compact
status line above the table shows row count, unique-team count and the latest REV
sequence when present. The **All locations** scope remains the historical LogBook-derived
view.

Registration projection synchronisation is event-driven. Status/TimingData events are
invalidation signals: the client first reads the small registration metadata resource.
ADD-only growth fetches only missing rows after the latest known ADD sequence. A changed
REV sequence, LocationId, count regression or ambiguous mutation causes a bounded full
projection reread because a previously shown row may have changed validity. The existing
**Sync view** action remains the explicit manual recovery path; there is no periodic
full-list polling loop.

The Registrations and LogBook tables each have one empty final presentation row.
Selecting that row follows the latest incoming records. Selecting a historical row
or scrolling upward pauses following for that table only; selecting the empty
final row resumes. This row is never an actual registration or LogBook record.
TeamID is an interpreted reference-data value, not a renamed RegistrationId. In the
default/reference profile, `ANNNN` projects directly to TeamID `NNNN`;
`RNNNN` remains unresolved until reserve assignment data is available. The
technical LogBook always keeps the actual RegistrationId. A manual registration with manually entered time therefore deliberately shows
`Type = MAN` and `Code = MAN`; a manual registration whose time was captured
automatically by the client shows `Type = MAN` and `Code = AUTO`. An automatic registration already carries all needed
meaning in `Type = AUTO`, so its Code cell is empty. A REV record keeps the interpreted row present and replaces the icon-only trash action
with a visible **DELETED** marker. The trash action has no text header. When selected,
the client sends the original registration family, LocationId, RegistrationId, time and,
for manual records, the original AUTO/MAN time-source classification to the normal
node-scoped revoke operation. SI-01 appends the REV record; it does not delete or rewrite
the ADD record. The immutable LogBook therefore continues to show both ADD and REV.

Registration input uses a compact shared form with one canonical RegistrationId field,
local date/time and a Now button. The manual submit button directly shows its
AUTO/MAN time-source classification; normally supported direct capability no
longer adds an extra status row. Unsupported capability is explained only while
the connected status is LIVE.

Registration input uses one canonical RegistrationId field plus readable local civil
date and hundredth-second clock time. The UI shows the interpreted client time zone next
to the field (for example `Europe/Amsterdam`) and converts that explicit local value to
the canonical UTC API timestamp when sending. **Now** captures the current client
date/time and marks the manual-registration time source as `AUTO`; editing the date or
time marks it as `MAN`.

**Direct registration** injects an accepted registration at the TimingNode and
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
- accepts the canonical RegistrationId input, shows the interpreted client time zone
  explicitly and converts the entered local civil time to a hundredth-second canonical
  UTC API timestamp;
- defaults the interpreted Registrations view to the currently OPEN LocationId and
  provides **All** for synchronized history across locations;
- shows the returned source `seq` as the operation result;
- shows committed TimingData **Type** and **Code** in separate LogBook columns;
- uses SI-01's maintained active-WP registration projection for current LocationId
  order/validity and keeps **All locations** as the LogBook-derived historical view;
- synchronizes that projection automatically from Events-triggered lightweight metadata:
  ADD-only growth fetches the missing tail while a new REV forces bounded full reread;
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
TagProcessor/filter mutation or StageStartTimes/NextUpTeams editors. The first Central RACE_DATA editor is now available separately as **C|RaceData**.

The narrow explorer deliberately contains three different projections. **A|Systems** is
the direct IF-03/API system view of SI-01. **C|Nodes** does not invent a Central system
aggregate: Central currently only discovers NodeIds through typed `UPSTREAM_DATA` on the
explicit `/api/v1/events?role=upstream` session. It therefore shows a flat compact node
list such as `A [05] · H - 30s · CNT 1157`; heartbeat, TimingData head, RACE_DATA source/ACK
heads and latest LogBook record remain available on the node tooltip. In the compact row, `[05]` means currently OPEN at LocationId 5, `[--]` means known CLOSED and `[??]` means the lifecycle/location is not known yet. `H -NNNs` is the locally observed heartbeat age, right-aligned to three digits with leading spaces (refreshed every 10 seconds and capped at 999s) and `CNT` is the received contiguous TimingData/LogBook head, right-aligned to at least three digits.

**C|Locations** is another compact Central projection, for example
`5 · OPEN · A` or `6 · UNKNOWN`. Sequence and transition timestamps remain internal
projection state and are not permanent explorer columns. A later NODE_OPEN for the same
NodeId at another LocationId implicitly closes the previously active location.

**C|RaceData** is an operator form rather than an explorer and therefore lives in the
wide controls/form dock beside TimingNode, API, Registration, Simulation and Scripts.
It selects an observed Central NodeId and sends only RegistrationId-to-TeamId SET/DEL
changes for now. Each operator action creates the next
Central-owned RACE_DATA sequence, shows the source head and cumulative ACK, and is rejected
unless the explicit Central `role=upstream` session is active. Stage-start editing remains
deliberately separate because its time-only/date+time conversion must reuse the accepted
UTC/civil-time rules. The two models do not share state, so an API/upstream mismatch
remains visible during testing. On a TIMING_DATA source-head SYNC the simulator requests
replay from its own next sequence, and heartbeat PING requests are answered with the same
mode/correlation id.

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
GET  /api/v1/node/{id}/registrations
GET  /api/v1/node/{id}/registrations?after=...&limit=...

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
