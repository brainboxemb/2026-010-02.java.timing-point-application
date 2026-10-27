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

The **Scripts** dock tab (beside Terminal) runs local `.lua` engineering
automation scripts. Open a file with **Open .lua**, drop it onto the Scripts panel, or
edit the script in place; **Run** is always explicit and dropping a file does
not execute it. **Open .lua** starts in the tool's standard `scripts/` directory
(`test-client/scripts/` in a source checkout; `scripts/` beside the packaged
Windows launcher in an app-image). **Stop** cancels the script, including a CPU-bound Lua loop
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
`A%04d` generator as the Simulation GUI. A selected TimingNode is optional at Run start. Generic `client.*` and
Central configuration calls work without one; node-specific simulation and
TIMING_DATA_REQ calls require the captured node. A script remains alive while
it intentionally disconnects/reconnects Events or Central. Simulation calls separately require the
TAG_SCENARIO_SIMULATION capability. Script and GUI simulation batches cannot
run concurrently. IF-03 remains the only operation boundary.
See the [Lua language/API reference](docs/lua-scripting.md) for supported calls,
requirements and troubleshooting, plus [scripts/batch.lua](scripts/batch.lua),
[scripts/stage-starts.lua](scripts/stage-starts.lua) and
[scripts/central-reconnect.lua](scripts/central-reconnect.lua) for editable standard scripts.

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

The Central simulator is built as the separate `test-client-central` Maven artifact.
That artifact has no dependency on the Engineering Client HTTP/API implementation.
Its only outbound application port is `CentralTransport.send(UpstreamData)`; desktop
composition adapts that port to the already connected upstream-role WebSocket. Central
presentation records and raw IF-06 encoding are Central-owned types, so Central code
cannot reuse `ApiClient` DTOs as an accidental shortcut. CI also rejects ordinary
`testclient` imports and JDK HTTP usage inside Central production sources.

Current client services are:

```text
TestClientFxApplication
  +-- ApiClient             IF-03 HTTP / JSON
  +-- ApiEventClient        IF-03 WebSocket + typed IF-06 UpstreamData
  +-- CentralClient         separate test-client-central module; IF-06 only
  +-- RemoteShellClient     line-oriented Remote Shell
  +-- LiveLogClient         LoggingServer diagnostics socket
```

JavaFX event handlers do not own HTTP/WebSocket, shell or live-log protocol semantics.
The client must not import SI-01 core/application implementation classes or mutate SI-01
domain state directly. It may consume the shared public Event Timing semantic artifacts
used at IF-05/IF-06 boundaries.

## Run on Windows

With `JAVA_HOME` pointing to a JDK 21 installation, use the dedicated SI-02
reactor so Maven automatically builds the shared IF-05/IF-06 contracts and the
isolated Central module before the runnable Engineering Client:

```powershell
.\mvnw.cmd -f engineering-client\pom.xml verify
```

In NetBeans, open `engineering-client/pom.xml` as the
**Event Timing Engineering Client Parent** project and use normal Run or Debug.
No configuration switch is required. Do not run `test-client-central`: it is
an internal SI-02 module and dependency boundary, not a second desktop
application.

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

The main window separates **engineering controls** from **viewers**:

```text
left: navigation / controls / diagnostics       right: detachable viewers
-----------------------------------------       -------------------------------
System                                         [ Node A ] [ Central ] [ StartTimes ]
Central: Nodes / Locations                      viewer content only
Node Controls / Central Controls
-----------------------------------------
Device Log
-----------------------------------------
Terminal
-----------------------------------------
Client Log
```

The upper-left explorer keeps the direct API system view and Central node/location
projections visible close to the controls. The separate right-side **StartTimes** viewer shows
Central-owned **StartTimes** for a selected StageId as RegistrationId/start-time rows.
StageId is Central source/routing context; authored values remain visible even before any node
opens. **Node Controls** contains TimingNode, API,
Registration and Simulation. **Scripts** is a generic engineering tool beside
**Terminal**, because it can automate client lifecycle, Central configuration and
node-specific operations. **Central Controls** separates current **Team Assignments /
Stage Starts** from **TimingData**: the two current-state families are authored independently,
while TimingData shows Central's contiguous received sequence and lets an operator request TimingData from an explicit
source sequence. The existing Central **LogBook** viewer remains the record-oriented view
of received TimingData.

Device Log, Terminal and Client Log are restored to their original vertically stacked
left-side docks, so all three can remain visible and be resized independently.

The right side is reserved for viewer workspaces. The **Node A** title follows the
API-selected NodeId. **Central** deliberately remains a global title because its Messages
and LogBook tabs already provide their own node filtering/selection and are not scoped to
the API-selected node. **StartTimes** is a separate global viewer beside Node and Central,
because stage-start definitions are Central-owned current state rather than LogBook history. This
right-side viewer leaf can later grow with additional node viewers without moving the
shared controls.

The Node viewer contains **Registrations**, read-only **StartTimes**, LogBook, Raw Data,
Events and HTTP. StartTimes is read directly from SI-01 applied STAGE_STARTS state through
bounded IF-03 pages; it does not mirror the Central editor. A StageStart revision is shared with
the Registrations projection so RunningTime is refreshed after an applied start-time
SET/DEL. The Central viewer contains separate **Messages** and
**LogBook** tabs: Messages inspects IF-06 message/batch boundaries, while Central LogBook
reconstructs one record-oriented history from accepted TIMING_DATA. Input/editor panes
do not travel with a detached viewer.

All Bento instances use one detached-window size policy. BentoFX normally copies the full
source dock size into a floating window, which made tabs detached from tall panes open
nearly full-screen. SI-02 first constrains the new Scene to 480–960 px wide and 320–640 px
high, then clamps the actual Windows Stage again after JavaFX performs its first layout.
That second pass is required for nested viewer roots, which can otherwise expand the Stage
again. The Stage is also moved fully inside the selected monitor's visual bounds so its
Windows title bar remains reachable. This applies equally to individual panes such as
Scripts and to whole Node/Central viewer tabs.

**Events** inspects ordinary IF-03 WebSocket events for the Node view. Central
`UPSTREAM_DATA` is deliberately excluded and is inspected only through Central
**Messages**. Direction and type filters are presentation-only; selecting a row shows
the exact event payload.

**HTTP** inspects IF-03 request/response calls made by the Engineering Client. The
system context retains a bounded diagnostic journal independently from the JavaFX table.
Rows show method, path/query, local start time and status; selecting a row shows the exact
request body and response body. This makes Node LogBook baseline/synchronisation visible,
including `GET /api/v1/node/{nodeId}/logbook`,
`?from=...&limit=...` and `?last=...` calls that precede/repair live
`TIMING_DATA_COMMITTED` event processing. The Engineering Client uses 128 records as its
normal bounded page size for paged IF-03 reads; the initial LogBook view deliberately remains
the latest 100 records and single-record command follow-ups remain single-record requests.

**Messages** is the Central semantic IF-06 inspector. It shows typed messages received
from and sent to nodes, including `SEQ`, `TIMING_DATA_REQ`, `RECV`, `PING`,
`TIMING_DATA`, `TEAM_ASSIGNMENTS` and `STAGE_STARTS`. Viewer time columns use the Engineering Client host's
local timezone while the selected raw IF-06 JSON remains unchanged UTC wire data. For
TIMING_DATA, **Time** uses IF-05 effective `time` (the last record for a batch); **Received**
is the separate local WebSocket receive timestamp. The Packet / position column shows the packet number separately from any
TIMING_DATA/LogBook sequence. TEAM_ASSIGNMENTS and STAGE_STARTS carry no LocationId or
source sequence. The shared IF-06 profile bounds TIMING_DATA to 16 TimingRecords,
TEAM_ASSIGNMENTS to 16 assignment changes and STAGE_STARTS to 16 start changes per
message. The table ends with one presentation-only
`— Follow end —` row: while that row is selected, new messages keep the table scrolled
to the end; selecting a real message suspends follow and preserves the inspected viewport
while later messages arrive. The **End** button at the far right of the filters resumes
follow. The simulator retains the latest 10,000 semantic messages as bounded diagnostic model state. The JavaFX table keeps only lightweight references/wrappers to that history and relies on TableView cell virtualization; it does not duplicate the raw payloads as rendered rows. Central TEAM_ASSIGNMENTS and STAGE_STARTS are current state plus at most one outstanding
normal DN_PACKET for retry; neither has retained source-sequence history.

**Central LogBook** is independent from that bounded Messages journal. Every accepted
contiguous TIMING_DATA record is retained once in receive order, regardless of whether it
arrived alone or in a 16-record replay/live batch. Duplicate replay records at or below
Central's receive head do not create duplicate rows. Ordinary reconnect retains the
reconstructed LogBook together with the TimingData receive head; changing target host clears
both. The table uses the same IF-05 Type/Code/Location/Registration/Effective/Recorded
semantics as the normal Node LogBook and can filter by NodeId. Effective/Recorded table
values are presented in the Engineering Client host's local timezone; selecting a record
still exposes its exact canonical UTC IF-05 JSON.

In **Registrations**, RegistrationId is the primary identity column. TeamId is a separate
optional/interpreted value and may be unresolved (`-`); SI-02 never substitutes TeamId
for RegistrationId. Revoke remains tied to the original RegistrationId/ADD source record.
Registrations and LogBook already use the same presentation-only trailing-row follow
pattern; their compact **End** column header resumes live following without consuming
additional toolbar space.

The main **Connect** action opens ordinary Events, Terminal and Device Log. When only
some of those links are alive, the toolbar shows the existing orange partial state and
enables **Reconnect**. Reconnect checks the API and restores only missing ordinary links;
already-connected links are not cycled. This is particularly useful after SI-01
`reboot +log`: Device Log remains connected while Events and Terminal can be restored
with one action. **Central** remains an explicit separate role switch:
only `/api/v1/events?role=upstream` makes SI-01 see a Central upstream peer. Ordinary
API/Events connectivity and Reconnect never imply Central availability.

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
TagProcessor/filter mutation or node-side StageStartTimes/NextUpTeams editors. It does
provide a read-only Node **StartTimes** view of the values actually applied in SI-01. The
Central current-state editor remains separately available as **C|Current State**.

The narrow explorer deliberately contains separate **System** and **Central** projections.
System is the direct IF-03/API system view of SI-01. Central uses the complete IF-03
`STATUS_SNAPSHOT` and later `STATUS_CHANGED` events on the explicit
`/api/v1/events?role=upstream` session as the authority for current TimingNode
OPEN/CLOSED and LocationId state. Typed `UPSTREAM_DATA` still supplies heartbeat,
TimingData synchronization and downstream protocol state. Its Nodes tab therefore shows a
flat compact list such as
`A [05] · H - 30s · CNT 1157`; heartbeat, TimingData head, last acknowledged downstream
packet number, any outstanding downstream packet number and the latest LogBook record
remain available on the node tooltip. In the compact row, `[05]` means currently OPEN at LocationId 5, `[--]` means known CLOSED and `[??]` means the lifecycle/location is not known yet. `H -NNNs` is the locally observed heartbeat age, right-aligned to three digits with leading spaces (refreshed every 10 seconds and capped at 999s) and `CNT` is the received contiguous TimingData/LogBook head, right-aligned to at least three digits.

**Central → Locations** is another compact current-runtime projection, for example
`5 · OPEN · A` or `6 · UNKNOWN`. It is derived from IF-03 current status rather than
from LogBook lifecycle records. A later runtime OPEN for the same NodeId at another
LocationId closes the previously active location in this projection.

`NODE_OPEN` and `NODE_CLOSE` records received through TIMING_DATA remain durable
LogBook history only. They never restore current runtime OPEN state. In particular, after
SI-01 restarts, an old unmatched `NODE_OPEN` may still exist in the LogBook while the
new runtime correctly reports CLOSED; Central follows that CLOSED status and does not
release current TEAM_ASSIGNMENTS/STAGE_STARTS delivery until a new runtime OPEN occurs.

**Central Controls → Current State** is an operator form rather than an explorer and
authors Central-owned Team Assignments and Stage Starts independently from a selected
node. Team Mapping is global Central state; Stage Starts are authored per StageId. Authored
data may be retained while nodes are CLOSED. Under the current mapping, OPEN LocationId N
selects StageId N for routing. Central then emits the applicable projection through
STAGE_STARTS; the wire message itself carries no LocationId or StageId. Registration input
uses an explicit A-Z prefix selector plus numeric values: for example prefix `A` and
number `8` becomes canonical RegistrationId `A0008`, and a range `8..100` becomes
`A0008..A0100`. Stage-start time accepts the existing IF-06 TimingTimestamp forms and
also compact operator time `HHmmss`, so `190000` is normalized to `19:00:00`. The separate **TimingData** control tab shows Central's own
contiguous received position, can explicitly query the current SI-01 LogBook/source head
with IF-06 `SEQ REQ` / `SEQ ANS`, and can issue a TIMING_DATA_REQ from an
operator-entered sequence so retained-data synchronization can be exercised without
forcing a reconnect. A manual source-head answer updates only the displayed SI-01
position; it does not implicitly start a TIMING_DATA_REQ. When no normal DN_PACKET is outstanding, the next action is sent
with the next downstream packet number; while a packet is outstanding it remains
immutable and later actions wait behind it. The pane shows the last acknowledged
downstream packet and any pending packet. It is rejected unless
the explicit Central `role=upstream` session is active.

Stage-start input normalizes only the operator convenience form `HHmmss[.fraction]`
before using the shared TimingTimestamp parser, so time-only, local date/time and absolute
UTC forms keep the same accepted IF-06 semantics. The API
and upstream models do not share state, so a mismatch remains visible during testing. A fresh SI-01 runtime reports
`RECV 0` to request a rebuild of current downstream state and publishes a TIMING_DATA
`SEQ NTF`; Central then issues `TIMING_DATA_REQ` from its own next contiguous LogBook
sequence. Heartbeat PING requests are answered directly with packet number 0 and the same
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
.\mvnw.cmd -Pdev-client -pl test-client -am verify
```

The reactor keeps `test-client-central` architecturally separate while making
its build/install ordering automatic for normal SI-02 development.

API HTTP/WebSocket, live-log and remote-shell client logic remain outside the JavaFX event
handlers so the UI does not become the owner of protocol semantics. The API client code
remains independent of SI-01 implementation classes, matching the headless black-box
client boundary.

The workbench deliberately uses flat, square-edged JavaFX controls throughout,
similar to a conventional Windows IDE. **Events** shows the raw JSON stream only;
the synchronized current state belongs in Systems, and raw event payloads remain
independently available for engineering inspection.
