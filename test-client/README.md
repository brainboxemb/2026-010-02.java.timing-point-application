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

The default composition keeps target/client-wide diagnostics permanently available in
the left column and limits the selected-node perspectives to the right side:

```text
shared diagnostics                     selected-node perspectives
------------------------------          ------------------------------------------
Device Log                              [ Node A ] [ Central A ]
------------------------------          |
Terminal                                | Node A: Systems / controls / history /
------------------------------          |         Raw Data / Events
Client Log                              |
                                        | Central A: Nodes / Locations /
                                        |            Messages / RaceData
```

Device Log, Terminal and Client Log each own a separate vertically stacked Bento dock,
so all three can remain visible at once and be resized independently.

**Node A** and **Central A** are ordinary BentoFX dock tabs in one right-side leaf, not a
separate JavaFX TabPane. Their titles follow the API-selected NodeId. Before SI-02 knows
a node they read simply **Node** and **Central**. Each tab contains its own nested Bento
workspace, so the whole Node or Central perspective can be dragged out into a floating
window and moved to another monitor while its internal dock layout remains intact.

The Node perspective is the direct SI-01/API viewpoint. **Events** is deliberately a
transport inspector: every WebSocket frame is one row with explicit **IN**
(SI-01 → SI-02) or **OUT** (SI-02 → SI-01) direction, event/semantic type, timestamp
and source/wire payload. Direction and type filters are presentation-only; selecting
a row shows the exact frame payload. No artificial blank lines are inserted.

The Central perspective is the semantic simulator viewpoint of the same NodeId.
**Messages** shows typed IF-06 messages received from and sent to nodes, including
`SYNC`, `REQ`, `ACK`, `PING`, `TIMING_DATA` and `RACE_DATA`. It exposes the
stream/sequence meaning separately from the exact IF-06 JSON. This message journal is
bounded inspection state only; Central-owned ordered `RACE_DATA` source history remains
independent so a later `REQ(RACE_DATA, fromSequence)` can replay the original messages.

**RaceData** is the Central-owned source/editor. One RACE_DATA message has one source
sequence and may contain multiple team/stage-start changes. Synchronization is therefore
about the ordered RACE_DATA messages, not about the individual change fields inside one
message.

In **Registrations**, RegistrationId is the primary identity column. TeamId is a separate
optional/interpreted value and may be unresolved (`-`); SI-02 never substitutes TeamId
for RegistrationId. Revoke remains tied to the original RegistrationId/ADD source record.

The compact toolbar vertically aligns **Host**, the target IP and all link buttons. The
main **Connect** action opens ordinary Events, Terminal and Device Log. **Central** remains
an explicit separate role switch: only `/api/v1/events?role=upstream` makes SI-01 see a
Central upstream peer. Ordinary API/Events connectivity never implies Central availability.

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
