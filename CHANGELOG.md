# Changelog

- Improve the Node engineering views: Registrations now presents SI-01-maintained RunningTime and labels manual AUTO/MAN classification as Time source; LogBook keeps its fast latest-100 baseline but adds an explicit bounded-page Load all action.

- Add a single `dev-client` Maven/NetBeans reactor entrypoint for SI-02 so shared contracts and the isolated Central module are built automatically before the runnable Engineering Client; Windows packaging and CI use the same reactor path.

- Add an operator-only Central TimingData source-head query over IF-06: `Query SI-01` sends a normal downstream `SEQ REQ`, displays the returned SI-01 LogBook/source count from `SEQ ANS`, and deliberately does not turn that answer into an automatic TIMING_DATA_REQ; automatic missing-suffix synchronization remains driven by source `SEQ NTF`.

- Add live terminal control for TIMING_DATA packet pacing: `config upstream timing-data-delay [<millis>|clear]` shows/overrides/clears the application-scoped delay without rebooting; future TIMING_DATA_REQ tasks capture the current value while YAML remains the startup value.

- Split SI-02 Central controls into separate RaceData and TimingData tabs: keep team/stage-start mutations on RaceData, move manual TIMING_DATA_REQ into TimingData with the selected node's contiguous received head and downstream packet state, and remove misleading operator-facing "replay" wording while retaining the Central LogBook viewer for received records.

- Add a secondary Engineering Client **Reconnect** action for partial target connectivity: it rechecks the API and restores only missing Events, Terminal and Device Log links while leaving already-connected links (notably Device Log after `reboot +log`) untouched; Central remains explicit.

- Add terminal `reboot +log`: restart SI-01 while retaining the active process Logging engine and LoggingServer client connection; ordinary `reboot` remains a full restart that recreates logging, and logging-related YAML changes are deferred until that full reboot when `+log` is used.

- Make TIMING_DATA execution boundaries explicit in INFO logging: replay logs its actual task start with requested/captured positions, and proactive live forwarding logs only productive task runs plus each queued LogBook read before the existing upstream queued/sent packet evidence.

- Extend SI-02 Central controls with StageStartTime SET/DEL and explicit TIMING_DATA_REQ from-sequence actions, and expose the same Central actions to local Lua scripts through a shared client action boundary while retaining normal DN_PACKET/RECV stop-and-wait behavior.

- Add formal `upstream.timingDataReplayPacketDelayMillis` configuration for TIMING_DATA replay pacing: zero preserves immediate replay; positive values release the node upstream task between successive replay packets without delaying the first/final boundary or normal live forwarding.

- Encode IF-06 PING message times in UTC with fixed millisecond precision instead of extending the wire value to nanoseconds; accept older higher-precision PING inputs unchanged.

- Make TIMING_DATA replay progress observable at INFO level: log active replay start with requested sequence and captured LogBook head, each queued LogBook replay range, empty handoffs, completed handoff to live forwarding, and requests beyond the next valid sequence without changing IF-06 delivery semantics.

- Restore IF-05 `recTime` as optional diagnostic record-creation metadata: readers accept its absence, decoded records preserve that absence on re-encode, Engineering Client parsing no longer requires it, and normal upstream INFO summaries do not surface it.

- Present Engineering Client viewer timestamps in the host's local timezone while retaining exact canonical UTC in raw IF-03/IF-05/IF-06 payloads. Central TIMING_DATA Messages now use IF-05 effective `time` as source Time (last record for a batch) instead of falling back to the near-identical UPSTREAM_DATA event/receive time.

- Make the existing IF-05 UTC record timestamps explicit in upstream verification and single-record TIMING_DATA INFO summaries: `time` remains effective UTC and `recTime` remains recorded-at UTC; no new IF-06 timing representation is introduced.

- Add terminal `reboot`: request a full application RESTART distinct from `quit`/`exit` STOP, cleanly deactivate the current runtime and process-level presentation/logging resources, reload the YAML configuration, and compose/activate a new SI-01 application in the same JVM.

- Keep Central IF-06 `UPSTREAM_DATA` traffic out of the Node Events viewer; add a Node HTTP inspector backed by a separate bounded diagnostic journal so LogBook baseline/synchronisation and other IF-03 request/response calls are visible with method, path, status and exact payloads.

## Unreleased

- Restore separate one-click SI-02 development startup: `engineering-client/pom.xml` is now the Engineering Client parent/reactor and normal NetBeans Run/Debug entrypoint, while the root Timing Point Parent remains the SI-01 run project without a `dev-client` configuration switch.

- Move the Java/Maven namespace from `io.github.brainboxemb.eventtiming` to `oss.relay.timing`, retaining compound package roots such as `timingpoint`, `timingdata`, `eventdata` and `upstreamdata`; update source packages, reactor coordinates, launch configuration and service/resource names.

- Preserve Central Messages historical inspection viewport during incoming traffic; shorten default IF-06 JSON wire keys to `pckNr`, `msg`, and `rcvPckNr`, and name the typed Java packet positions `packetNr` / `receivedPacketNr`.
- Show a single TIMING_DATA record concretely in upstream INFO summaries (sequence, IF-05 record type/location/registration/code); retain range + count for actual multi-record batches.


- Replace the unreleased flat IF-06 `SYNC`/`ACK`/`REQ` draft with the packet-oriented v1 development contract: explicit packet numbers on `UP_PACKET`/`DN_PACKET`, stop-and-wait downstream `RECV`/resend with idempotent duplicate handling, `PING` on packetNr 0 without RECV, TIMING_DATA-only `SEQ`/`TIMING_DATA_REQ` LogBook synchronization, and current-state RACE_DATA delivery without an independent source-sequence history. Update the Central simulator, Engineering Client inspectors and protocol verification to the same model.

- Log successfully decoded inbound IF-06 messages on the device side with the stable `Upstream received:` INFO prefix and the same compact semantic summary used for outbound queued/sent messages. This makes PING, SEQ, TIMING_DATA_REQ, RECV, RACE_DATA and TIMING_DATA traffic visible without logging full protocol JSON.

- Restructure the left Engineering Client explorer into two vertically stacked sections: **System** above and **Central** below, with Central containing **Nodes** and **Locations** tabs. Remove the older `A|Systems` / `C|Nodes` / `C|Locations` prefixes while keeping the underlying views unchanged.

- Improve Central Messages batch inspection: keep the retained IF-06 payload unchanged, but present multi-record TIMING_DATA detail with one compact TimingRecord per line; single-record and non-TIMING_DATA detail remain unchanged.

- Add a reconstructed Central LogBook viewer independent from the bounded Central Messages journal: retain every accepted contiguous inbound TimingRecord once across reconnect, clear it on target reset, show one row per record with the same IF-05 Type/Code/Location/Registration/time semantics as the existing LogBook, support Node filtering and follow-end, and expose canonical IF-05 JSON for selected records.

- Make Central Messages a true IF-06 message inspector: retain the raw IF-06 payload separately from the IF-03 event envelope for inbound and outbound rows, show that retained payload on selection instead of re-encoding the semantic value, and keep multi-record TIMING_DATA positions to the sequence range only while single-record messages may show the compact IF-05 record type.

- Improve Engineering Client Central Messages timing: retain semantic/source message time separately from local receive time, use PING.time when available, capture receive time at the WebSocket boundary, show separate Time/Received columns, default to compact local time-only formatting with at most millisecond precision, and allow switching to a date+time view.

- Standardize normal operational INFO logging across registration, durable LogBook persistence and upstream delivery: use stable `Registration accepted`, `LogBook persisted`, `Upstream queued`, `Upstream sent`, `Upstream connected` and `Upstream disconnected` prefixes; summarize TIMING_DATA once per actual message batch (sequence range + record count) without logging the full encoded payload.

- Add optional absolute UTC `time` to IF-06 PING values for legacy-compatible message-time visibility. SI-01 heartbeat requests and answers populate it from the composed TimeSource, while readers still accept older PING values without time; Central reference replies create their own answer timestamp rather than echoing the request time.

- Simplify TIMING_DATA synchronization: live forwarding is enabled by default for an available session; SEQ reports positions without gating live traffic; Central sends no automatic response when already caught up, while a real TIMING_DATA_REQ pauses live forwarding for its captured replay and replay completion resumes it at capturedHead+1.

- Tighten the shared IF-06 data contract: RACE_DATA now carries a required message-level LocationId, TIMING_DATA is limited to 16 TimingRecords per message, RACE_DATA is limited to 16 combined team/stage-start changes, and LogBook replay uses the same 16-record batch size. SI-02 Central creates RACE_DATA only for a currently OPEN location and shows the location in the semantic Messages position column.

- Add an explicit follow-end mode to live SI-02 tables. Central Messages now has a visible `— Follow end —` row plus an **End** toolbar button; selecting a real message pauses following. Registrations and LogBook reuse their existing trailing follow row and expose **End** in a compact column header to resume following without extra toolbar space.

- Clamp every detached Bento DragDropStage again after its first JavaFX layout, in addition to the existing Scene size cap. This prevents nested viewers such as Central <id> from expanding back to oversized source dimensions and repositions floating windows fully inside the target monitor visual bounds so the Windows title bar remains reachable.

- Refine the SI-02 desktop split into left-side explorer/Node Controls/Central Controls plus the original stacked Device Log, Terminal and Client Log docks, with a dedicated right-side viewer leaf for detachable Node <id> / Central <id> viewers. Apply one Bento SceneFactory policy to all floating panes so already-small panes keep their size while detached windows are bounded to 480–960 × 320–640 px instead of inheriting an oversized source dock.

- Reorganize SI-02 into top-level `Node <id>` and `Central <id>` perspectives with independent Bento workspaces and shared Device Log/Terminal/Client Log tools. Replace the Events raw text log with an IN/OUT WebSocket traffic inspector, add a separate Central semantic IF-06 Messages inspector with bounded journal state independent from RACE_DATA replay history, and make RegistrationId the primary Registrations identity while retaining optional TeamId separately.

- Replace the SI-02 Events raw TextArea with a structured WebSocket traffic inspector: one row per frame, explicit IN/OUT direction, semantic/event type and timestamp, exact selected payload, direction/type filters, and tracked outbound Central PING/REQ/RACE_DATA traffic without artificial blank lines. Make RegistrationId the primary Registrations identity column while retaining optional interpreted TeamId separately.

- Right-align the compact C|Nodes heartbeat age and `CNT` values with leading spaces (`H -  9s`, `CNT   9`) so rapidly changing numbers stay visually aligned in the narrow explorer.

- Show compact Central node liveness directly in `C|Nodes`: render rows as `A [05] · H - 30s · CNT 1157`, use `[--]` for known CLOSED and `[??]` for unknown location/lifecycle, refresh heartbeat age every 10 seconds, cap it at 999 seconds, and use the received TimingData/LogBook head as `CNT`.

- Fix the Central location projection so a NODE_OPEN for a new LocationId implicitly closes the same NodeId's previously active location even when no explicit NODE_CLOSE record exists; the new OPEN sequence/time becomes the inferred close transition, leaving at most one interpreted OPEN location per node.

- Correct the Central explorer semantics: rename `C|Systems` to `C|Nodes` because Central currently discovers nodes rather than a system aggregate, show a flat `NodeId · lifecycle · Location` list with protocol diagnostics in tooltips, compact `C|Locations` to `LocationId · state · NodeId`, and move the `C|RaceData` operator form into the wide controls dock.

- Add the first visible Central backend mutation pane as `C|RaceData`: select an observed NodeId, send canonical RegistrationId-to-TeamId SET/DEL through the explicit Central `role=upstream` session, allocate the next retained RACE_DATA source sequence only after the connection gate passes, and show source-head versus cumulative ACK. Keep StageStartTime editing deferred until SI-02 reuses the accepted time-only/date+time UTC interpretation path.

- Add the first SI-02 Central System Simulator workbench slice: keep the direct API view as `A|Systems`, add independent `C|Nodes` and `C|Locations`, use the existing `ApiEventClient` for both ordinary Events and an explicitly selected full-duplex `role=upstream` ST-2 session so a normal API/Event connection never implies Central availability, decode/encode the shared typed IF-06 `UpstreamData` contract, request missing TIMING_DATA from the simulator's next sequence after SYNC, answer heartbeat PING requests, show the latest received LogBook record per observed node, derive Location OPEN/CLOSED from NODE_INFO history, and allow initial Central LocationIds through `central.locationIds`.

- Extend the SI-02 Central System Simulator with per-NodeId RACE_DATA source state: allocate ordered sequence numbers, retain immutable sent changes for REQ replay across reconnect, advertise the RACE_DATA source head when SI-01 sends TIMING_DATA SYNC, consume cumulative RACE_DATA ACK, expose source/ACK heads in `C|Nodes`, and clear both protocol streams only when the target Host identity changes.


- Preserve IF-06 stage-start knowledge as compact UTC longs instead of inventing dates: time-only values remain `HHmmsscc`, dated values use `yyyyMMddHHmmsscc`, `ClockTimeSource` supplies the configurable civil-time zone, and `StageTiming` resolves time-only midnight rollover only when runtime is unambiguous and below 12 hours.

- Remove NodeService pass-through access: application code now resolves shared EventData directly, NodeState exposes engineering stage-start mutation through a separate `NodeStateTestControl`, and TimingNodeProxy no longer depends on NodeService.

- Split retained node data from `NodeService` processing: `NodeState` now owns `RaceDataState`, `ActiveRegistrations` and `RegisteredTeams` and directly implements the read-only `NodeStateAccessor` boundary. Keep ordered/catch-up-aware reads with the state owner, while `NodeService` remains focused on input processing, mutation ordering and lifecycle.

- Align technical LogBook terminal output with the persisted IF-05 vocabulary: `lb` now prints compact `type=AUTO_REG|MAN_REG|NODE_INFO`, persisted `code=` values, and UTC `time`/`recTime` instead of leaking internal Java kind/action names. Add node-scoped `lbl <locationId> ...` with count/latest/all/global-sequence paging through the existing LogBook query boundary so one location can be inspected without introducing another persistence/index model.

- Add node-scoped technical terminal inspection alongside the display-ready `regs` projection: `lb` reuses the real LogBook query boundary for count/latest/range/all history and prints retained TimingRecord fields with absolute UTC time/recTime values; `data` reads one coherent current RaceData snapshot from the NodeService Task Lane and exposes live Central RegistrationId-to-TeamId mappings plus RegistrationId-scoped UTC stage starts without folding in fixed EventData mappings. Add focused `help lb` and `help data` pages.

- Make terminal time entry operator-local by default: compact `HHMMSShh` for `reg` and `start` uses the host/operator system time zone and local current date, optional suffix `U` selects UTC, registration rows are displayed in the same local zone, and ambiguous/nonexistent DST wall times are rejected. Reorganize terminal help into a compact logically ordered command index (help/version/log first) plus `help <command>` detail pages so command syntax, node-target examples, time semantics, paging and config subcommands are explained without interleaving unrelated entries.

- Restore ActiveRegistrations as the display-ready active-WP timing projection: retain RegistrationId-scoped stage-start scalars beside each compact registration row, derive runtime directly, maintain competition rank on live ADD/REV and stage-start changes, defer ranking until OPEN reconstruction catches up, expose runtime/rank through the terminal `regs` view, and remove the package-info policy file that incorrectly excluded calculated timing state from this projection.

- Correct draft IF-06 RACE_DATA stage-start identity: `stageStartTimes` now uses `regId` rather than `teamId`; retain stage starts by `RegistrationId` through NodeService/RaceDataState, reject legacy team-only stage-start items, and resolve terminal `start <id|team> <HHMMSShh>` input to a RegistrationId before applying the override.

- Tighten the Presentation-to-Domain ownership boundary: compose each `TimingNodeProxy` with its `TimingNode`, `NodeService` and LogBook access explicitly; route stage-start and registration-projection operations directly to `NodeService` and remove those pure passthrough methods from `TimingNode`.

- Add the node-scoped terminal command `start <id|team> <HHMMSShh>` for engineering stage-start input; resolve compact hundredths against the selected node clock's current UTC date and serialize the normalized epoch-millisecond value through NodeService race-data ownership without advancing IF-06 protocol sequence state.

- Keep internal absolute timing state scalar: normalize IF-06 stage-start civil time at NodeService ingress before RaceDataState, keep active-registration and antenna-observation reads in UTC epoch milliseconds, and materialize `TimingTimestamp` only at protocol/presentation boundaries that need it.

- Simplify per-node upstream execution: `SerialTaskRunner.executeOrWarn(...)` now owns short Runnable admission/rejection diagnostics, while `UpstreamInputTask` owns ordered stream consumption and directly propagates each semantic `YieldingTask` continuation without `NodeUpstream` future bookkeeping or nested task runs.

- Remove `TimingTimestamp` from the internal antenna-to-LogBook command path: carry absolute event times as epoch-millisecond `long` values through passage selection, `TagProcessor.NewRegistration`, `TimingNode`, `LogBookCommands` and `LogBookOperations`; keep IF-05 effective-time canonicalization at 10 ms, while preserving full 1 ms `recTime` precision.

- Decouple cooperative task-step metrics from `SerialLane`: both `SerialLane` and `SerialTaskRunner` now own independent `ExecutionMetrics` instances for queued work, admission rejection, wait duration and execution time. One task-runner metric unit is a scheduled step rather than a complete yielding task; delayed/awaiting tasks consume no queue slot.

- Rename `TimingRecord` creation factories to explicit `create...` names: `createAutomaticRegistration(...)`, `createManualRegistration(...)`, `createNodeOpened(...)` and `createNodeClosed(...)`; update production, codec and test call sites consistently.

- Keep `AGENTS.md` as repository-routing/local-exception guidance: route durable Java coding rules to `44-01-GPD-java-design-rules.md` and Engineering Client UI rules to `44-02-GPD-engineering-client-design-rules.md` instead of duplicating those standards locally.

- Complete upstream Messaging composition before runtime use: construct fixed node-scoped `UpstreamStream` ports first, construct `UpstreamGateway` once from the connector plus complete routing map, remove runtime `createApplicationPort(...)`, `NodeBinding` and the redundant connector/stream registry, keep connector input plumbing package-internal, and align Engineering factories/comments with the Java design rules.

- Simplify `UpstreamData` to one node-scoped immutable base with small concrete message values (`TimingDataTransfer`, `RaceData`, `Sync`, `Ack`, `Req`, `Ping`); remove the subtype-interface plus hidden `DefaultUpstreamData.*Value` implementation hierarchy and keep `DefaultUpstreamData` as a thin reference-profile factory.

- Move fixed system-level event wiring into `TimingSystem`: antenna observations now target the normal `TimingNode.onTagObserved(...)` API, upstream stream events target normal `NodeUpstream` input methods, and Runtime composition only constructs/resolves the components and mappings.

- Make `UpstreamConnector` an abstract shared base class: own the gateway input binding once, expose public `CONNECTED`/`DISCONNECTED` EventSource ports directly, and let concrete connectors implement only transport-specific availability/session/send behavior.

- Run `YieldingTask` steps directly on the Runtime-owned single-thread `ScheduledExecutorService`, without a nested `SerialLane<TaskRun>` worker or batch setting; keep per-runner bounded admission and task cancellation/timeout behavior. Typed Data Lanes remain unchanged.


- Refine upstream messaging boundaries: make each `UpstreamStream` a fixed gateway application port resolved once by NodeId during composition; remove the redundant generic `UpstreamMessage` envelope; use typed connector/stream events for post-fact lifecycle/data notifications; keep command-style inbound admission on an explicit gateway port; move heartbeat cadence and IF-06 semantics into `NodeUpstream`; and keep Engineering-specific adaptation under `io.messaging.engineering`.

- Add effective `upstream.transport` startup configuration. The current build supports `engineering-api`; an absent section preserves that existing default, while explicitly configured but unimplemented transports such as `rabbitmq` are rejected until their connector exists. Runtime connector composition now follows this effective setting.

- Align upstream messaging with the gateway/connector architecture: `UpstreamGateway` resolves inbound NodeId routing to fixed node-scoped application ports, `UpstreamConnector` owns transport/session mechanics only, `EngineerApiConnector` owns the Engineering WebSocket session and bounded outbound delivery, and `NodeUpstream` owns heartbeat protocol cadence/state. The `role=upstream` WebSocket remains full-duplex for inbound and outbound UpstreamData.

- Split `NodeUpstream` inbound processing into focused message tasks: `RaceDataTask` waits on the existing `CompletableFuture` from `NodeService`, `TimingDataReplayTask` owns LogBook batch waits, later messages remain queued in `UpstreamStream`, and protocol state is named explicitly as `RaceDataSync`, `TimingDataSync`, and `Heartbeat`.

- Rename per-node `UpstreamHandler`/`UpstreamHandlerTypes` to `NodeUpstream`/`NodeUpstreamTypes`; simplify the system `Upstream` constructor to a `List`, remove unused forwarding API and test-only factories, and make the production `TimingSystem` constructor require explicit upstream ownership.

- Scope registration-derived state to the currently OPEN WP: OPEN(locationId) rebuilds that WP from the complete LogBook so registrations from earlier OPEN periods return, CLOSE deactivates the projection, other WPs are excluded, rows no longer retain LocationId, NodeService reads are implicitly current-WP, and historical rebuild suppresses incremental team +/- events until catch-up completes.

- Make registration-derived state explicit: replace the mixed `domain.node.timing` package with `domain.node.registration`, document ownership/replay/REV/team-membership/display invariants in detail, remove test-only StageTiming/ranking production APIs and result types, and keep RaceData assignment/start helpers internal to RaceDataState.

- Align `RegisteredTeams` with the typed event-port convention: private `changedEvent` backing state plus explicit `CHANGED` EventSource port, removing the legacy `changes()` accessor.

- Normalize IF-06 stage starts to absolute UTC at RACE_DATA ingress using the node's shared TimeSource for the current UTC date of time-only values; retain stage starts as epoch milliseconds so no later consumer needs civil-time or time-zone interpretation.

- Stream `RegisteredTeams.visit(...)` directly over the retained unique-team set for CAN/display burst synchronization; remove the temporary sorted list because team order has no domain/protocol meaning.

- Make `ActiveRegistrations` retained state compact and consistent: committed `TimingData` is copied once into primitive scalar fields and no longer retained; semantic `RegistrationId`, `LocationId`, and `TimingTimestamp` values are reconstructed only at read/output boundaries.

- Align SI-02 registration input with the canonical `A0001`-style RegistrationId: replace legacy configurable prefix + number controls with one validated field, remove `registration.prefix`, and rename visible `Direct auto-reg` wording to direct registration terminology.

- Simplify terminal registration input: `reg <id> [now|<UTC timestamp>]` is the primary command, omitted/`now` uses the selected TimingNode clock, explicit UTC timestamps remain available for replay, and `auto-reg` remains a compatibility alias.

- Fix SI-02 Engineering Client IF-03 status parsing: read the actual `locId` field and accept status payloads without the absent `problems` field; cover HTTP and WebSocket status snapshots with the deployed server shape.

- Separate chronological registration rows (including invalidated ADDs) from unique registered teams per WP (+/- events and full visits), so the smart display can synchronize each maintained projection without a second full TimingData history.

- Search active duplicate registrations from the newest storage position first, matching the common case where a duplicate follows shortly after acceptance while preserving the dense-array cleanup design.

- Simplify duplicate-registration state to a short-lived dense primitive list: sizing is independent of observation queue capacity, expired entries are removed on lookup and scheduled cleanup, storage starts with a small allocation batch and grows with ordinary `Arrays.copyOf`, and runtime characterization records capacity/current/peak active-state size rather than hash-table behavior.

- Simplify tag-processing time/state: antenna observations now carry epoch-millisecond event time plus monotonic nanoseconds, passage rules use the monotonic time base, default TimingData stores epoch milliseconds with standard `Instant` conversion, the custom compact-time helper is removed, duplicate suppression uses compact primitive active-state storage, and Java comment guidance now requires domain-rule/rationale documentation rather than syntax narration.

- Use antenna observation timestamps for passage boundaries and preallocate reusable RegistrationPassage state from a generic object pool; retain two fixed per-tag statistic slots and expose pool size/peak-use metrics for characterization.

- Simplify TagObservationFilter hot-path state: document the passage algorithm, remove continuously rebuilt passage snapshots and per-tag diagnostic maps, reuse one expired-burst buffer, and publish only a cheap pending-passage flag for characterization.

- Store TagId as one packed int with type, four-digit number and A/B variant, and retain TagObservation timestamps as compact centisecond longs through passage filtering.

- Store RegistrationId as one packed integer behind the canonical type-letter plus four-digit value, and use the compact key in default TimingData and reference processing.

- Store default TimingData values as compact scalars instead of retaining Context, NodeId, LocationId and TimingTimestamp wrappers per record. Absolute record times use one yyyyMMddHHmmsscc long at centisecond precision; public value objects are reconstructed only at the TimingData API boundary.

- Make the TagProcessor jobs follow the Java readability rules: FilterJob and HousekeepingJob each keep at most one delayed JDK run while relevant state exists, runtime policy changes replan that one run, every job helper documents its purpose, and simple contract guards stay on one line.

- Simplify TagProcessor around explicit scheduled jobs: move passage-filter scheduling to `FilterJob` and duplicate cleanup scheduling to `HousekeepingJob`, keep both jobs on the Runtime-owned scheduler, make lifecycle start/stop visible in `TagProcessor`, and remove TagProcessor-local scheduling locks, futures and defensive try/catch handling.

- Move Data Lane ownership fully into LogBook: remove `TimingNodeDataLane`, `TimingNodeDataItem` and the redundant `LogBookDataLane` wrapper; LogBook now owns `SerialLane<LogBookOperationItem<?>>` directly, while upstream protocol work stays on the TimingNode Task Lane and yields with `TaskStep.await(...)` for ordered LogBook reads.

- Simplify `TimingNode` activation to direct MainTask start: `onActivate()` now only starts the Task Lane, activates children and starts the known reusable MainTask; task completion owns the `INIT -> OK/ERROR` transition, and cancellation during deactivation publishes no completion state.

- Make lifecycle activation component-local instead of transactional: `ComponentLifecycleManager` and `AbstractConductor` no longer roll back already activated components, failed components remain inactive while independent components continue, `TimingNode` reuses one MainTask, same-lane startup facts execute directly, Task Lane shutdown cancels without waiting for a running step, and LogBook recovery cannot publish state after deactivation.

- Remove the last legacy scheduling wrappers: simulated tag scenarios now schedule their bounded next step directly on the Runtime-owned `ScheduledExecutorService`, `RunnableSerialLane` and `LaneScheduler` are deleted, and stale delayed scenario work is generation-guarded across deactivate/reactivate.

- Keep TimingNode operational health separate from LogBook health: a failed persisted-data recovery remains a LogBook recovery problem while the TimingNode MainTask completes to `OK`; `TimingNode.ERROR` is reserved for failure of the node's own startup/execution path. Functional `OPEN/CLOSED` remains independent from both.


- Simplify execution around Java primitives: `SerialLane<T>` owns bounded typed queues, `SerialTaskRunner` runs cooperative yielded tasks on one `SerialLane<TaskRun>`, and `TagProcessor` uses a `SerialLane<TagObservation>` plus direct single-thread `ScheduledExecutorService` filter/housekeeping tasks. Remove the duplicate `NodeTaskLane`, `SerialQueue`, `TaskSerialLane`, and `ScheduledSerialLane` layers.

- Start TimingNode asynchronously through its cooperative MainTask: lifecycle activation starts the shared task runner, activates child admission and queues the MainTask without waiting for recovery. The MainTask reconstructs persisted LogBook data on the shared node worker; Data Lane work may be admitted during startup and executes after that startup step releases the worker. Recovery is not a queued `LogBookOperationItem`, and operational `INIT/OK/ERROR` remains separate from functional `OPEN/CLOSED`.

- Simplify typed `SerialLane<T>` to its queue/worker responsibility: remove the global current-lane stack, five-state/FAILED lifecycle, lane-local application failure policy and redundant exception handling; keep bounded FIFO, batch fairness, one-worker scheduling, drain-on-close and item metrics.

- Add non-blocking ordered LogBook query submission: `submit(LogBookQuery)` queues the same typed Data Lane query operation and returns its `CompletableFuture`, allowing YieldingTasks to await Data Lane work without blocking the shared worker.

- Add `TaskStep.await(...)`: a cooperative Task Lane task can release its worker while an asynchronous dependency is incomplete and is re-admitted only after that dependency completes.

- Make `SerialLane<T>` the typed item queue/worker boundary: queue items are caller-owned values, the backing executor sees only one fixed worker, and LogBook uses `LogBookOperationItem` variants on its own typed SerialLane.

- Simplify delayed serial scheduling: `submitAfter(...)` now returns one standard `CompletableFuture` for result/completion/cancellation, removing duplicate scheduled-operation wrapper APIs while recurring scheduling keeps one narrow caller-facing registration handle.

- Reduce `SerialLane` to one bounded FIFO queue and one fixed scheduled worker: each worker run processes up to configurable `maxItemsPerRun` queued items, work arriving during that batch remains for the next run, timing is recorded centrally, and LogBook names its queued fire-and-forget operation by intent.

- Remove the unchanged committed-TimingData relay from TimingNode: NodeService now consumes the LogBook commit event directly and TimingNodeProxy exposes the same LogBook event through LogBookAccessor, keeping TimingNode focused on node state and coordination.

- Keep TimingNodeList as a plain immutable Domain collection: remove its composition-only nested Builder and unused `of(...)` factory, let Runtime assemble nodes with ordinary Java collections, and construct the immutable list once at the Domain boundary.

- Simplify caller-visible command/error handling: move presentation TimingData outcomes to Application `CommandResult`, let LogBook registration commands return committed `TimingData` directly, keep `TimingNodeTypes` to real node values/results, and remove the public `OperationException.Reason` state model while retaining detailed Data Lane failure logging.

- Add one documented `TimingNode.processLocked(Supplier<R>)` scope for presentation-triggered LogBook writes, keeping state-sensitive registration admission consistent with OPEN/CLOSE without adding one TimingNode method per TimingData action.

- Finish the production readability audit by removing the last reviewed normal-flow returns in logging command routing, property publication and runtime deactivation; true guards and explicit multi-state decisions remain unchanged.

- Continue the production readability audit by keeping normal equivalent paths linear in lifecycle, logging, lane cleanup, tag ingress, test-hook dispatch and upstream payload dispatch while retaining true guard/failure-path early returns.

- Add `SerialLane.isRunning()` as the semantic production query for RUNNING lifecycle state and use it from `TimingSystemConductor` instead of interpreting the lane's state enum in caller control flow.

- Replace `checkArgument(...)` / `checkState(...)` with explicit always-enabled failure guards `failIfInvalidArgument(...)` / `failIfIllegalState(...)`. Callers keep positive assertion conditions: true means the contract holds and false throws.

- Stabilize asynchronous AntennaManager and TimingNode tests by waiting for semantic readiness/startup completion before asserting or constructing full-queue scenarios.

- Give `AbstractConductor` an explicit construction-time diagnostic name so generic start/stop lifecycle logs identify the concrete conductor.

- Simplify TimingSystem antenna control: TimingSystemConductor calls `AntennaManager.setInventoryRequired(...)` directly. AntennaManager privately keeps `Setting<Boolean>` requested/applied/retry state; remove the shared `AntennaSettings` / grouped-settings transport while preserving explicit retry requests.

- Replace generic LogBook `history` wording with concrete `TimingData` record, registration, committed-data and persisted-data terminology.

- Filter TimingSystemConductor wakeups through the existing grouped property event: EventSource supports composition-time predicates, TimingNode exposes typed immutable property keys, and unrelated property updates no longer queue reconciliation work.

- Move the reusable inactive-component guard into `LifecycleComponent`; concrete components configure their caller-visible inactive failure at construction instead of reimplementing the active-state check.

- Keep fire-and-forget LogBook queue rejection inside the LogBook execution boundary and use `SafeExecute` there, leaving TimingNode OPEN/CLOSE and registration flow free of repeated queue-specific `try/catch` blocks.

- Simplify TimingNode current state back to functional OPEN/CLOSED plus LocationId. Remove the premature operational-health state/problem layer and keep lifecycle-history queue failures as LogBook diagnostics instead of a second TimingNode status model.

- Use strongly typed `Property<T>` values for TimingNode functional state and LocationId. `PropertyContainer.update()` emits one grouped `PropertyUpdate` containing every changed property; lifecycle teardown uses `sync()` so it does not emit a normal runtime change event.

- Move generic active/inactive lifecycle state and the reusable `failIfInactive()` guard into `LifecycleComponent`; TimingNode configures its caller-visible inactive failure at construction instead of reimplementing the lifecycle check.

- Decouple TimingNode OPEN/CLOSED runtime state from lifecycle LogBook persistence. OPEN/CLOSE now apply state at the registration-admission boundary, submit lifecycle history asynchronously, and no longer use a CLOSE-only registration-block flag or SafeExecute catch/rethrow path.

- Simplify `TimingNode.close()` failure handling: use the shared result-bearing `SafeExecute.callOrRethrow(...)` guard and model the temporary CLOSE ingress block as the simple `blockRegistrations` flag instead of manually repairing a derived `registrationsAllowed` flag.

- Add the node-scoped engineering terminal command `test:<NodeId> logbook write <fail|timeout>` to arm a one-shot failure for the next LogBook write. `fail` returns the normal FAILED operation path; `timeout` delays the real Data Lane write beyond the commit wait guard so the existing TIMEOUT/outcome-unknown behavior is exercised while the accepted write may still commit later.

- Simplify `TimingNode.close()`: use `setRegistrationsAllowed(boolean)` instead of a CLOSE-specific gate helper, keep status publication outside the LogBook try/catch, and name private `Event<T>` backing fields with an `Event` suffix.

- Rename the engineering/test automatic-registration API from `addCustomAutomaticRegistration` to `addTestAutomaticRegistration` so the method name reflects that this path injects controlled test registrations rather than a normal production registration mode.

- Make TimingNode authoritative for transient OPEN/CLOSED + LocationId state: successful open/close operations now apply and publish status directly after lifecycle TimingData commit, while committed LogBook lifecycle facts (including late commits after caller timeout) no longer mutate runtime status through the generic TimingData event.

- Clarify the CLOSE antenna-ingress gate and lifecycle commit results: `commitNodeOpen`/`commitNodeClose` now return `OPENED`/`CLOSED` directly to `TimingNode`; the separate close-failure restoration helper is removed; BUSY/UNAVAILABLE restore the temporary antenna ingress gate locally while ambiguous accepted failures remain fail-closed.

- Separate result-bearing LogBook wait guards into a 5-second TimingData commit wait timeout and a 2-second ORDERED-query wait timeout; log caller-visible operation failures centrally with NodeId/operation/reason context and log TimingData persistence/in-memory commit failures with record context.

- Make result-bearing TimingNode/TimingNodeProxy operation failures explicit with declared `OperationException` contracts; remove the redundant TimingNode LogBook-readiness preflight so commands naturally remain ordered behind startup recovery; and move CLOSE catch/rethrow mechanics into the shared `SafeExecute` guard while keeping timeout-specific ingress recovery in TimingNode.

- Hide internal software-component lifecycle behind a shared `LifecycleComponent` base: composition registers components directly with `ComponentLifecycleManager`, while component-specific activation/deactivation hooks are protected instead of public functional API.

- Simplify TimingNode status to a Java-8 immutable data carrier: `Status` is always present and contains only public final `state` and `locationId`; LogBook recovery is separate readiness/error information and never defines OPEN/CLOSED. `ErrorStatus` is independent error data.

- Simplify TimingNode operation-state handling: OPEN/CLOSED and current LocationId remain TimingNode-owned transient runtime state, persisted history is not replayed to reconstruct it, and one normal LogBook `NEW_DATA_ENTRY` fact enters TimingNode, which updates status, republishes `TIMING_DATA_COMMITTED`, then publishes `STATUS_CHANGED`; remove the two LogBook-specific before/after sequencing events.

- Expose concrete LogBook events as named `EventSource` ports so fixed composition wiring uses the same `INPUT.bind(component, SOURCE)` form as TagProcessor and other components; keep getter methods only where `LogBookAccessor` requires method dispatch.

- Correct TimingNode OPEN/CLOSED terminology: operation-state events and handlers no longer use `lifecycle`; lifecycle remains reserved for component activate/deactivate behavior.

- Apply the typed local-event port pattern consistently across SI-01 components: receivers declare `EventInput<C,T>` ports, concrete producers expose `EventSource<T>` ports, and component-specific bind/accessor wrappers are removed where no interface contract requires them.

- Keep LogBook as the sole full TimingData history owner: startup history is handed to NodeService without extra list copies, NodeServiceState stores only compact active-registration/unique-team derived state, and StageTiming no longer refolds or retains full committed history.

- Reflow recently refactored TimingNode code to DR-18: keep readable statements on one line up to the 120-character limit instead of mechanically wrapping around 80 columns.

- Make `LogBook.activate()` read linearly by naming the persisted-load scheduling and rejection-cleanup `Runnable` values before passing them to `SafeExecute.run(...)`.

- Restore `LogBookCommands` to the shared validation/comment conventions: command preconditions use `Checks.checkArgument(...)`, private helpers are documented, and the repeated deferred `logBookState -> ...` command lambda is explained once at the factory boundary.

- Remove unused `LogBookState` getters and its unused convenience constructor, and document every remaining state/helper method plus the missing `LogBookData` constructor/size contracts.

- Document `LogBookQuery` ORDERED/CURRENT construction and read contracts, including why CURRENT reads go through safely published `LogBook` state.

- Document the remaining non-trivial `LogBook` execution lambdas at their call sites, including what `()` and `->` mean and which work the `SerialLane` executes later.

- Align `NodeService` execution naming with Java semantics: synchronous result work uses a literal `submitAndWait(...)` helper, direct same-lane query execution is decided by the caller, and private Task Lane helper names describe domain intent rather than execution plumbing.

- Rename the generic execution guard to `SafeExecute.run(...)` and document the LogBook startup lambda/method-reference arguments explicitly at the call site.

- Compose `UpstreamHandler` beside `LogBook` and `NodeService`, pass the real Data/Task `SerialLane`s directly, remove the LogBook execution passthrough and custom lane adapter interfaces, and share one explicitly composed `NodeServiceState`.

- Simplify LogBook activation: use a generic `serialLane` name, let `SerialLane.start()` own start-state validation, load persisted data through intent-based naming, and centralize rejection boilerplate in `SafeExecute.run(...)`.

- Replace the remaining SerialLane `accepted` metric vocabulary with queued-work terminology (`queuedCount`, `queuedTaskCount`, `queuedTaskDelta`) and bump runtime-characterization evidence schema to version 2 for the renamed JSON field.

- Simplify `SerialLane` internals: per-task queue/execution timestamps live in a dedicated `TaskTiming` object, queue-capacity failure throws directly, and helper names describe intent instead of synchronization mechanics.

- Align `SerialLane` with standard Java execution semantics: lane users call `execute(Runnable)` or `submit(Callable) -> Future`, rejection uses `RejectedExecutionException`, bounded queue `offer`/`poll` remains internal to the lane, and `LogBook` now has one explicit constructor.

- Remove the separate `LogBookReader` port: internal upstream synchronization now uses the same `LogBookAccessor` query boundary as other LogBook consumers, including a bounded `timingDataRange(...)` query.

- Finish the `domain.node` DR-16 audit by expressing the remaining pure Status/LogBook command preconditions with `checkArgument(...)`.

- Rename the synchronous LogBook command boundary from `invoke(command)` to `execute(command)` and document TimingNode public result/status contracts at the type definition.

- Separate `NodeService` composition from Task Lane state: new passive `NodeServiceState` owns RaceData/derived state and implements the narrow `UpstreamTaskState` port; `NodeService` no longer implements an upstream child interface.

- Refine `domain.node` readability: rename the active `LogBookProcessor` component to `LogBook`, passive committed history to `LogBookData`, `NodeTaskProcessor` to `NodeService`, and use plain queue/queued vocabulary for non-blocking lane handoffs.

- Add type-safe default lifecycle registration: `ComponentLifecycleManager` derives the diagnostic class name while activation/deactivation remain explicit compile-time method references; no reflection or common lifecycle interface is required.

- Clarify the `TimingNode` composition boundary: name the TagProcessor-to-LogBook handoff, make rejected Data Lane admission observable, document LogBook access precisely, keep child lifecycle solely in `ComponentLifecycleManager`, separate independent Task Lane activation from persisted TimingData startup loading, and report load failures as `TIMING_DATA_LOAD_FAILED`.

- Tighten the `TimingNode` boundary: child lifecycle now uses `ComponentLifecycleManager`, LogBook commands/queries/events are exposed through `LogBookAccessor`, and child/test-only passthrough getters are removed in favor of explicit runtime/test composition.

- Keep TimingNode timing-history ownership explicit under `domain.node.logbook`, with persistence under `domain.node.logbook.persistence`; keep live race state separate under `domain.node.racedata`.

- Move TimingNode heartbeat protocol state onto the bounded Task Lane: heartbeat ticks and inbound PING handling use non-blocking Task Lane admission, while heartbeat status is published as an immutable direct-read snapshot.

- Simplify Java 8 execution around one bounded `SerialLane` mechanism: ordinary work uses `Runnable`/`Callable`, delayed admission is handled separately by `LaneScheduler`, genuine multi-step work uses `YieldingTask`, and the former `SerialScheduledLane`/`ScheduledTaskRunner` layers are removed.

- Reduce `TimingNode` to one component constructor and lifecycle/facade role; `TimingNodeComposer` builds its LogBook, Task and Tag children, while `TagProcessor` publishes one-way `newRegistrationEvent` facts that `TimingNode` forwards to `LogBookProcessor`.

- Restore clear BentoFX tab affordance: the strip has its own surface, every tab has a raised fill plus subtle separator, selected tabs use a dedicated light selection fill (#dbeaf9), and only the active leaf adds the accent underline.

- Restore the neutral selected BentoFX tab fill so a selected tab remains visibly distinct from the raised header strip; the active leaf still adds the restrained accent underline. Add a stylesheet regression test for the selected/active distinction.

- Run the NetBeans Engineering Client actions in a separate Java 21-capable child JVM via `exec:exec`; Run/Debug use the Maven runtime classpath, `${java.home}/bin/java`, Windows long-classpath handling and NetBeans JDWP child-process debugging, keeping Maven/JGit/ForkJoin threads out of the SI-02 runtime.

- Run the NetBeans Timing Point Application actions in a separate JVM via `exec:exec` instead of Maven's in-process `exec:java`; Run/Debug now use the runtime classpath, Windows-safe long classpath handling and NetBeans JDWP child-process debugging. The real application JVM main thread is named `tp-main`.

- Remove the dedicated Presentation prompt scheduler thread: the passive console-prompt quiet-period deadline is serviced by the existing `tp-inf-logging` writer while retaining separate blocking console and remote-shell threads.

- Normalize project-owned thread names for compact diagnostics: `tp-main`, `tp-dl-*` for Domain workers and `tp-pl-*` for Presentation threads, while keeping `tp-io-*` and `tp-inf-*` unchanged.

- Move default console and retained-file logging behind one bounded non-blocking Infrastructure queue and writer thread; batch file flushes periodically and on WARN/ERROR/shutdown so timing lanes never perform log file or console I/O.

- Add operational TimingData/LogBook logging: INFO records recovery start/completion, selected tag passages and successful durable+LogBook commits; repaired tails use WARN, while raw observations and filtering detail remain TRACE/DEBUG.

- Move TimingNode startup recovery onto the node's own serial lane: `activate()` starts execution/lifecycle and queues recovery without waiting; CURRENT status becomes available after recovery publication and SystemConductor tolerates startup-not-ready nodes.

- Add SI-02-only embedded Lua scripts via LuaJ with a Scripts docking tab, open/drop editable .lua files, Run/Stop, log output and an example batch; reuse IF-03 simulation and current GUI batch planning, without adding Lua to Timing Application.


- Simplify inventory control using shared transactional AntennaSettings; remove duplicate DerivedProperty/SourceProperty and PropertyRegistry state from SystemConductor while retaining CURRENT status queries and asynchronous AntennaManager reconciliation.

- Consolidate SI-02 styling into documented flat desktop tokens, common pane spacing and shared console CSS; eliminate ad-hoc log styling and duplicate dock-header selectors.
- Protect UI state against obsolete async baseline/node history completions after disconnect/reconnect/selection changes; batch client/device log updates on FX and coalesce redundant follow-latest scroll scheduling.


- Align the Host connection toolbar controls to one compact height, add a clearly labeled power-icon Connect/Disconnect action with active/partial visual states, and remove the always-on Client Log status label (logging remains available in its own dock).


- Make Registrations filtering mouse-friendly with directly selectable BentoFX ≡ menu radio items; display the effective OPEN LocationId or All in the tab title, keeping default scope on the selected node's open location and showing no stale id when inactive. Hide the redundant bold LIVE label in Systems while retaining syncing/disconnection warnings.


- Move application/version/API diagnostics to an independent **API** tab beside TimingNode, remove the duplicated Registrations status header, move the Current location/All locations filter to the standard BentoFX ≡ menu, and tighten log-panel spacing and table header typography.


- Enlarge the stopwatch dial/hand artwork for clearer 16–32px Windows taskbar rendering and regenerate a 256px JavaFX PNG and an optimized seven-size ICO (16–256px); preserve editable SVG and reproducible design-source generator.


- Review Claude's proposed Windows caption improvements without reverting newer main changes: add safe lazy library loading/failure diagnostics and explicitly reserve caption-control width; restore the application name and version in a balanced title row. Introduce a branded stopwatch/signal application icon for the JavaFX Stage and Windows jpackage launcher.


- Replace the handcrafted right-aligned Windows caption title with a compact clock mark and flat View/Help menus on the left; keep one set of library caption buttons at right and remove inherited button borders from them.


- Use BentoFX's built-in ≡ dock-area menu (tab position and selected-tab dock-back) and automatic ▼ overflow selector throughout the Engineering Client, including floating windows. Remove bespoke ↩ tab icons and duplicated tab context handlers.


- Remove default BentoFX close ✕ icons from every permanent Engineering Client dock tab (not only Systems) via the real non-closable property. Docking, detaching and dock-back remain available.


- Render SI-02 state/history views only when their semantic values change; deduplicate matching HTTP/WebSocket records, coalesce queued timing events, keep node selection stable and maintain disabled input controls through asynchronous status updates.


- Prevent silent mixed JavaFX 17/21 runtime linkage in SI-02: explicitly pin JavaFX base/graphics alongside controls/fxml, and check the loaded JavaFX module versions before launching BentoFX so NetBeans/IDE classpath conflicts produce an actionable diagnostic instead of cascading NoSuchMethodError stack traces.


- Integrate the SI-02 menu and title in the main native Windows title-bar row, using a Windows-only Win32 caption extension with system drag/window control support; leave floating BentoFX windows unchanged, and provide a safe opt-out property for debugging.


- Simplify Registration input into aligned RegistrationId, local time and action rows; show AUTO/MAN on manual submit instead of status clutter, and make JavaFX ComboBox arrow/popup styles fully flat.


- Use a single Host → Connect action and stable independent boundary option labels, with color denoting verified current connection state (green connected, yellow busy, red error), plus unchanged manual toggles.


- Keep SI-02 Systems as a small top-left Explorer beside TimingNode/Registration/Simulation tabs rather than stacking the two vertically; remove the confusing close X from the persistent Explorer.


- Make SI-02 Direct auto-reg injection explicit: choose node clock (default) or a provided replay time independently from manual data edits; explain the bypassed antenna path and report acceptance with node, ID and provenance separately from observed LogBook records.

- Allow IF-03 direct automatic registration without a supplied time: the owning TimingNode obtains its TimeSource during execution. Store optional `tagSrc`/`timeSrc` provenance in AUTO_REG TimingData, preserve legacy records and verify through black-box tests.

- Place the SI-02 Systems tree as a compact upper-left pane, prune empty dock leaves on detach and reliably show/re-pin detached panels through a tab button or right-click; simplify Events to raw JSON, and unify all JavaFX controls under one square-edged desktop theme.


- Refine the SI-02 Engineering workbench with a dockable Systems/node status tree, compact boundary buttons, dynamic log-level and LogBook-count headers, readable docking/context menus, detachable-pane dock-back and View → Reset layout, plus live-follow trailing rows in Registrations/LogBook.


- Use concise `id` for IF-11 TimingSystem/TimingNode declarations while preserving explicit `timingSystemId` and `timingNodeId` in reference bindings. Migrate all YAML examples and system-test fixtures; legacy declaration fields are rejected rather than supported as aliases.


- Make TimingSystem IDs compact: one-node systems use the same ID as their node (`A`, `B`); multi-node systems require a distinct application-wide ID (`9` for the current A/B fixture). Reject prefixed identifiers and conflicting identities; align default composition, samples, and black-box verification.


- Require ordered `timingSystems` and nested `timingNodes` YAML lists with explicit IDs; remove meaningless keyed topology entries, migrate all configs and fixtures, and reject old mappings (no compatibility mode).


- Standardize system-test sample identities: normal TagId pair `TT-A-NNNN-1/-2` maps to `RT-A-NNNN` and TeamId `NNNN`, with reserve `TT-R`/`RT-R` distinguished; IF-03 direct-registration tests use only RegistrationId. Keep one shared fixture helper and document tag-mapping test-scope boundaries.

- Move system-test IF-11 configuration out of Java concatenation into readable YAML test resources, with one small test-only factory for dynamic port/node values. Name multi-node topologies explicitly and clarify the intent of their black-box verification cases.


- Add packaged-process IF-03 verification for two supported multi-node topologies: one TimingSystem SID-9 with TimingNodes A/B (VC-ST1-007), and two TimingSystems SID-A/SID-B with one node each (VC-ST1-008); assert independent per-node LogBook files, ordered registrations, isolation and restart recovery.

- Record the exact packaged application version/revision/source provenance through configured runtime logging at startup, so the first INFO record reaches the retained log file. Keep direct console fallback when configuration or logging cannot be initialized, without double-printing a normal startup banner.

- Align Step 6 V01 / VC-ST1-003 execution with the current SI-02 baseline: use node-specific `vc-st1-003-node-A-logbook.jsonl` storage, run the packaged Java 21 Engineering Desktop Client app-image, and rename the standard client configuration/log path to `engineering-client.properties` / `logs/engineering-client`.

- Restructure the standalone desktop application as SI-02 Engineering Desktop Client on Java 21 / JavaFX 21 with BentoFX 0.16.0, instance-scoped connected-system context, dockable real workbench panes, and a CI-verified Windows jpackage app-image path.

- Add deterministic IF-11 YAML source parameters and contextual TimingData path templates. The compact single-system default is `SID-A`; examples use `SID-{ID}` and `node-{NodeId}-logbook.jsonl` while runtime configuration receives only resolved values.

- Preserve Java 8 compatibility when building with newer JDKs by compiling against the Java 8 API via Maven `release=8` and disambiguating `SerialScheduledExecutor.State` from Java 19+ `Future.State`.

- Add explicit CURRENT versus ORDERED TimingNode query consistency. Status can use a safely published current snapshot without queueing, ordered reads preserve command sequencing, SystemConductor reconciles from CURRENT state, and same-lane ordered reentrancy fails immediately.

- Replace the heavyweight TrackedProperty refresh scheduler with passive SourceProperty/DerivedProperty state. SystemConductor owns explicit source-state reconciliation and derived behaviour, while CooperativeTaskController remains the sole coalescing scheduler.

- Allow command-shell node commands to target a specific TimingNode per invocation using `command:NodeId` (for example `open:A 1`), without changing the selected default node. Multi-node sessions show the selection in the prompt and default to strict explicit addressing; `node-mode selected` can opt into implicit selected-node targeting. Applies to local and remote terminals.

- Move AntennaManager TimingSystem binding uniqueness, target validation and lookup into a typed `AntennaManagerConfigRegistry`; preserve the existing Config/YAML API and IF-11 behavior.

- Move TimingSystem/TimingNode configuration identity, storage-path validation and lookup into a typed `TimingSystemConfigRegistry`; keep `Config` as the public effective configuration root and preserve its API.

- Replace `PresentationGateway`'s parallel TimingNode proxy map/list bookkeeping with one ordered `TimingNodeProxyRegistry` that owns proxy creation, NodeId uniqueness and lookup while preserving the public Presentation API.

- Group Runtime TimingSystem types by name and replace generic composition lists with typed collections: `TimingSystemResolvedData`, `TimingSystemResolvedDataList`, `TimingSystemComponentsList`, and end-to-end `TimingNodeList` use through Runtime and PresentationGateway.

- Move simulation-specific Runtime code to `runtime.simulation`: `SimulationRuntime` and `SimulatedTagScenarioRunner` are grouped outside the default Runtime package without changing simulation behavior or the production processing path.

- Support IF-11 antenna manager configuration and observation routing per TimingSystem; compose distinct I/O and node components per system without passing a generic I/O facade to Domain. Preserve Windows single-system fallback and reject unsupported providers/power settings explicitly.

- Give SystemConductor a strongly typed `TimingNodeList` and a dedicated `PropertyRegistry` for ordered state property registration and identity-based lookup. Registry has no executor or queue responsibilities; no inventory behavior changes.

- Rename the Java system-level coordinator class to `domain.system.SystemConductor` to distinguish it from `ApplicationConductor`; the architecture diagram still displays the role as `Conductor`. No behavior changes.

- Align Domain source packages with the architectural system/node distinction: move `domain.timing` to `domain.node` (including `processing`), preserving runtime behaviour, while keeping `domain.timingdata` separate.

- Compose 1..N configured TimingSystems in Runtime. Each system resolves its own EventData/TimingData providers, receives its own TimeSource and domain.system.Conductor, and contributes its TimingNodes to the application-wide PresentationGateway. The single ApplicationConductor registers every system; implicit Windows antenna composition remains limited to one system with one node.

- Compose 1..N TimingNodes inside the current single TimingSystem: load all configured nodes, resolve a unique per-node LogBook file such as `node_A_logbook.jsonl`, create node-local persistence/execution lanes, coordinate the full node list with one system Conductor, and expose all nodes through Presentation. Multi-node composition does not use the temporary implicit Windows antenna fallback.

- Rename the application lifecycle coordinator to `ApplicationConductor`, keep `domain.system.Conductor` for TimingSystem coordination, and allow Runtime to register 1..N systems without fully qualified type names.

- Restore separate Application and TimingSystem Conductor responsibilities: Application orders AntennaManager/system lifecycle; each system Conductor owns its TimingNodes and directly drives AntennaManager inventory; remove the temporary InventoryControl interface.

- Shorten the system-local Domain coordinator name to `domain.system.Conductor`; update its tests and Runtime composition without changing behavior.

- Move operational antenna inventory coordination into a Domain TimingSystemConductor supporting 1..N TimingNodes, retaining a single manager-wide inventory control port and direct observation wiring.

- Add the default/reference EventData identity convention: `TT-A/R-NNNN-{1|2}` physical tags resolve to `RT-A/R-NNNN`; normal registrations resolve directly to TeamId `NNNN`; reserve TeamId remains lookup-driven; `0000` is invalid; the Development Client and simulation fixture use the same identity grammar.

- Add capability-gated simulated-tag scenarios through the real `SimulatedAntenna -> AntennaManager -> TagProcessor -> TimingNode` path, with public `simulation` EventData using `RT-A-0001..RT-A-2000` and physical tag pairs `TT-A-NNNN-1/-2`, deterministic `simple`/`normal`/`edge` profiles, and a Development Client batch driver for count/range/ascending-or-seeded-random selection and scenario interval.

- Add normal IF-03 manual-registration ADD with client-selected AUTO/MAN time semantics; normalize registration/lifecycle effective time to centiseconds and record-creation time to milliseconds, and emit canonical IF-05 v1 timestamp text with fixed two/three fractional digits.

- Refine the Development Client registration workbench: hundredth-second input/display, automatic AUTO/MAN classification from **Now** versus edited time, normal manual registration, and current-open-location filtering with an explicit **All** history view.

- Add Step-5 TimingData lifecycle and revoke support: successful OPEN/CLOSE transitions commit as `NODE_INFO` with `OPEN`/`CLOSE` code before the corresponding status change; registration revoke appends `AUTO_REG [REV]` or `MAN_REG [REV,AUTO|MAN]` through the same sequence/persistence/LogBook/event path without rewriting ADD history.

- Expose registration revoke as normal node-scoped IF-03 behaviour and enable the Development Client trash action: the client supplies the original registration semantics, SI-01 performs bookkeeping commit only, and the interpreted row remains visible as DELETED while the technical LogBook retains both ADD and REV.


- Let unpaced runtime-characterization bursts settle on their bounded processing/admission outcome instead of incorrectly requiring every emitted observation to commit; queue rejection remains measurable V01 evidence.

- Add the repeatable V01 development-host runtime-characterization baseline: three repetitions of steady, burst, 1,000-record history and 9,999-record history workloads, with durable generated evidence published separately on `prod/characterization`.

- Add the optional `runtime-characterization` Maven profile/module for SIP Step-5 engineering workloads, using the normal SimulatedAntenna -> TagProcessor -> TimingNode -> file persistence path and retained JSON evidence from `RuntimeMeasurementReader`.

- Move Step-5 runtime characterization out of the public TimingNode Domain contract: remove `TimingNode.runtimeMetrics()` / `TimingNodeTypes.RuntimeMetrics`, keep component-owned counters, and add the engineering-only `runtime.measurement.RuntimeMeasurementReader` with TimingNode, tag-processing and JVM snapshots.

- Keep asynchronous console logging readable while the local terminal prompt is waiting: the first log in a burst moves to a fresh line and one prompt is redrawn after 150 ms of quiet instead of after every log record.

- Remove Conductor's mirrored `lastHandledTimingNodeState`: `TimingNodeStateProperty` remains the sole TimingNode-state holder, while AntennaManager reuses its existing `Setting<Boolean>` through an idempotent desired-inventory update; explicit repeated requests still remain available for retry.

- Bound IF-03 WebSocket outbound delivery: track only a small per-client buffered-send budget, disconnect a slow client with close code 1013 before another event is queued after the limit, and recover through the existing reconnect snapshot + LogBook flow instead of adding an application event queue.

- Simplify `ScheduledTaskRunner` around the cooperative task model: remove legacy `runAsync` / `runDelayed` / direct-run scheduling helpers and replace nested completion lambdas with a named per-run context that makes cancellation and task-step boundaries explicit.

- Separate raw platform wall-clock time from the shared timing `TimeSource`: move the `TimeSource` contract below Domain/I/O into `platform.time`, add a `ClockTimeSource` baseline implementation, and let `RuntimeTimeSources` compose explicit timing sources from `PlatformEnvironment.clock()` instead of hiding that boundary in an inline lambda.

- Generalize cooperative state-machine execution beyond scheduled I/O: add a serial-lane task runner and reusable wake/coalescing controller, use the same controller in AntennaManager, and make Conductor own one self-scheduled current-state handling task instead of cross-component decisions in event handlers.

- Use compact one-character TimingNodeIds (`A`..`Z` or `1`..`9`) and one-digit AntennaIds (`1`..`9`); simplify lifecycle INFO logs; add shared local/remote terminal `log` commands for temporary TRACE/DEBUG/INFO/WARN/ERROR changes.

- Add cooperative reusable task execution with `AbstractTask`; refactor antenna control around `AntennaSet`, manager-owned self-test/inventory/shutdown state machines, diagnostic-only self-test PASS/FAIL, and explicit retryable inventory attempts after earlier device failure without requiring process restart.

- Add small `infra.setting.Setting<T>` requested/applied/pending state; make AntennaManager activation start an asynchronous startup self-test with PASS/FAIL result, retain the latest inventory setting while busy, avoid redundant initial CLOSED disable work, and let Presentation start without waiting for antenna startup I/O.

- Rename TimingNode OPEN/CLOSED/ERROR from lifecycle to operational `State`; replace boolean antenna inventory control with explicit enable/disable actions; publish tracked-property changes through `EventSource`; and make local event wiring composition-time only with compact 0/1/N subscriber storage.

- Restore normal startup build-identity output so application name, version and provenance are written before configuration/loading can fail.

- Add typed EventData/TimingData provider bootstrap: stable `reference` built-ins, `infra.extension.ExtensionRegistry`, Java `ServiceLoader` discovery from a supplied ClassLoader, IF-11 provider selection in Config/YAML, Runtime provider resolution before composition, duplicate/unknown-id validation, and a synthetic external-JAR discovery test.

- Make normal Windows development startup compose antenna `1` with the built-in `SimulatedAntenna` when no explicit antenna composition exists yet; expose OS identity through `PlatformEnvironment`, log clearly that no physical RFID reader is in use, and exercise the normal AntennaManager/Conductor lifecycle.

- Extract generic Conductor lifecycle/cleanup mechanics into `application.logic.AbstractConductor`; keep concrete `Conductor` focused on SI-01 properties, startup actions and cross-component rules, and move `ComponentLifecycleManager` beside the lifecycle base.

- Replace Conductor-specific state bookkeeping with tracked application properties: add generic `infra.property.TrackedProperty`, add `application.property.TimingNodeLifecycleProperty`, wire TimingNode status events as change signals, and keep Conductor focused on the TimingNode-to-antenna application rule.

- Simplify antenna lifecycle ownership: keep device startup and power/initialize/inventory sequencing inside `AntennaManager`, reduce `AntennaSwitchController` to multiplex round-robin only, and rename provider cleanup from `close()` to `shutdown()`.

- Move application component lifecycle coordination into `Conductor`; add `ComponentLifecycleManager` for ordered activation, rollback and reverse deactivation, keep Runtime focused on shared execution resources/composition and start Presentation only after the coordinated application core is ready.

- Rework `Conductor` status handling around coalesced current-state reads: synchronous TimingNode callbacks no longer throw downstream queue overloads, stale status snapshots are not executed as commands, and TimingNode/Conductor control transitions gain causal runtime logging.

- Extract `EventData` and semantic `TagId` into a shared Java-8 `event-data` artifact with typed `EventDataProvider` SPI; keep TagProcessor multi-tag passage diagnostics on the shared contract and remove EventData injection from the public `TimingApplicationRuntime` API, leaving an explicit simulation-only seam.

- Add immutable `EventData` and semantic `TagId`; resolve RFID observations through EventData before RegistrationId-keyed duplicate/passsage filtering; retain compact per-tag passage diagnostics; remove `TagRegistrationMapper`/`DecryptedTagId` and the loose mapper runtime-composition path.

- Move generic bounded result waiting, cancellation propagation and delayed scheduled task handling out of `AntennaControlLane` into platform `ScheduledTaskRunner`; AntennaManager keeps only antenna-specific failure semantics. TimingNode and TagProcessor continue to use their execution primitives directly.

- Rename the concrete top-level composition/lifecycle type from `TimingApplication` to `TimingApplicationRuntime` so it is symmetric with its owned `PresentationRuntime`; the external SI-01 Timing Application concept is unchanged.

- Keep Runtime execution role-based and worker items bounded: remove TimingNode identity from RuntimeExecutors lane creation, add one-shot delayed work to SerialScheduledExecutor, and replace antenna power-stabilization sleeps with delayed continuations on the existing AntennaManager serial scheduled lane. The one-worker I/O baseline remains unchanged.

- Collapse runtime construction into one `TimingApplication.create(...)` composition root: create `PlatformEnvironment`, construct without starting physical workers, wire cross-component behaviour explicitly, then `start()` Runtime execution resources and `activate()` application components in visible order.
- Give `Conductor` its own serial application-coordination lane so synchronous local events only hand off immutable state; cross-component behaviour no longer executes on the emitting TimingNode thread.
- Move concrete HTTP/WebSocket/remote-shell/local-console construction and lifecycle out of `Main` into runtime-owned `PresentationRuntime`; `Main` now supplies only process console I/O and shutdown-hook handling.
- Use one physical shared I/O worker for the Step-5 baseline; additional I/O parallelism is deferred to V01 evidence.

- Refactor antenna ownership under `io.devices.antenna.manager`: `AntennaManager` exposes activation/inventory/status only, one `SerialScheduledExecutor` lane handles immediate/result-bearing/scheduled control, `AntennaSwitchController` coordinates the set/multiplex group, and `ManagedAntenna` owns one physical power/self-test/init/inventory sequence. Remove the separate JDK scheduler dependency from AntennaManager.

- Expose authoritative TagProcessingPolicy startup/current runtime configuration through Application ConfigurationControl, IF-03 HTTP/WebSocket change events, and shared local/remote terminal commands including open, close and automatic-registration control.

- Map the current single-TimingNode YAML subset through the IF-11 `timingSystems -> timingNodes -> tagProcessing` hierarchy, apply partial TagProcessingPolicy startup overrides over compiled defaults, and keep invalid policy values rejected before composition.

- Move explicit JVM heap/thread/GC sampling from `platform.environment.RuntimeObservation` to `platform.metrics.RuntimeObservation`; keep `PlatformEnvironment` focused on wall-clock and monotonic-clock dependencies.

- Split SerialExecutor and SerialScheduledExecutor measurement state into dedicated metrics classes; make both classes pure logical lanes on externally owned workers, removing standalone/private-worker modes and keeping physical worker construction/shutdown in composition/test owners.

- Share TimingNode and TagProcessor physical workers by runtime role while keeping per-node bounded serial lanes; worker thread names now describe the shared role rather than a specific TimingNode.

- Add conventional packaged-application startup options (`--help`, `--version`, `--config`) and `--generate-config`, which writes the complete version-matched example IF-11 YAML without silently overwriting an existing target.

- Add independent per-antenna startup handling, optional external power control, TimingNode-driven inventory activation and mutual-exclusion inventory multiplexing; extend the simulated antenna path with power and injected-failure behaviour.

- Correct configuration package ownership: move generic typed configuration mechanics to `infra.configuration`, move the concrete running `ApplicationConfiguration` tree to `runtime.configuration`, and stop exposing the writable runtime tree directly from `Application`.

- Introduce central typed `ApplicationConfiguration` with read-only/dynamic configuration views, compiled `TagProcessingPolicy` defaults, runtime override/clear notifications, startup-only queue-capacity protection, and live TagProcessor policy consumption without coupling policy to antenna I/O composition.

- Complete the first antenna runtime lifecycle slice: add bounded shared-I/O `AntennaManager` control with per-manager ordering/timeouts, application-owned antenna subscriptions and TagProcessor lifecycle, and a normal `SimulatedAntenna -> TagProcessor -> TimingNode.offer(...) -> TimingData` composition path without adding IF-11 configuration fields.

- Align runtime metric ownership: rename `TagProcessingCounters` to `TagProcessingMetrics`, group `SerialExecutor` and `SerialScheduledExecutor` measurements under component-owned `Metrics` objects, and expose immutable pull-based snapshots instead of loose executor metric getters.

- Move accepted-registration duplicate suppression ahead of passage aggregation after tag-to-registration mapping, rename the TimingNode producer handoff to `offer(...)`, and keep that handoff bounded/fire-and-forget so TagProcessor never waits for lower-priority TimingNode processing.

- Replace the hand-written TimingNode `SerialWorker` with JDK-backed `SerialExecutor`, add `SerialScheduledExecutor`, and move TagProcessor to a bounded observation input queue with coalesced serial draining and same-lane housekeeping.

- Map provider-decoded `DecryptedTagId` values to `RegistrationId` before passage filtering so multiple tags for one registration share one strongest-RSSI burst; keep `RegistrationId` as the typed HashMap key and use direct one-field hash codes instead of `Objects.hash(...)` for `RegistrationId`/`LocationId`.

- Simplify tag-processing APIs: one TagProcessor constructor, collection-style filter APIs, TagProcessor-owned periodic-task lifecycle through `platform.execution.PeriodicExecutor`, and no lifecycle/threading knowledge in child filters.

- Fix protected-main black-box evidence publication by checking out the exact integrated source revision before running the repository evidence-staging script.

- Refactor tag processing into `domain.timing.processing`: focused passage/RSSI and RegistrationId duplicate filters, a small orchestration-only `TagProcessor`, and separate low-allocation `TagProcessingCounters` with pull-based snapshots.

- Repair the Step-5 antenna/tag path against D04: Event-based TagObservation with RSSI/time, Antenna lifecycle methods, strongest-RSSI passage selection, injected TagRegistrationMapper, timed burst closure and RegistrationId duplicate suppression before bounded TimingNode submission.

- Align current repository documentation with its existing Java-project structure: shorten the root README to an entrypoint, remove implementation-step narration from Development Client docs, and rename the manual VC-ST1-003 checklist/config away from Step-4 demo terminology.
- Add Step-5 A02 pull-based runtime instrumentation: fixed SerialWorker admission/wait/execution counters, TimingNode append/commit/event metrics, TagProcessor ingress counters and on-demand JDK heap/thread/GC observations without per-event sample allocation or measurement logging.
- Start Step-5 A01 with a normal `Antenna` callback contract, deterministic built-in `SimulatedAntenna`, distinct `TagId` resolution and `TagProcessor` submission into the existing bounded TimingNode registration/commit path; unknown synthetic tags are filtered before commit.
- Rename the node-scoped Application-layer status projection from `ApplicationStatus` to `TimingNodeStatus`; `TimingNodeProxy.status()` and `statusChangedEvent()` now expose the node-specific type and lifecycle naming directly.
- Resume Step-5 development as `0.2.4-SNAPSHOT` after the accepted `v0.2.3` Step-4 release.

## 0.2.3 — 2026-10-04

- Top-align the API Timing workbench by moving application identity into the left column so Registrations starts at the same vertical position on the right.
- Refine the interpreted Registrations table to `Time | Type | TeamID | Code | <action>`, distinguish registration Type from time-origin Code, use an icon-only delete action with no header, and rebalance the two-column workbench.
- Add a two-column Development Client Timing workbench with input/control on the left, an interpreted local-time Registrations projection with Code `AUTO`/`MAN` on the right above the immutable technical LogBook, and future REV projection that marks entries DELETED rather than hiding history.
- Make the Development Client registration time zone explicit: show the interpreted client zone beside the local civil Time field, keep **Now** in that same zone and convert to canonical UTC only at the IF-03 boundary.
- Make Development Client Timing synchronisation prominent above Version/Status, start baseline sync immediately on Events connection, buffer the initial snapshot during sync, and keep mutating controls disabled until LIVE.
- Introduce a node-scoped `TimingNodeProxy` behind `PresentationGateway`, remove the standalone Set Location operation in favor of `open(locationId)`, rename automatic-registration intent to `applyAutomaticRegistration(action, registrationId, time)`, and expose explicit `statusChangedEvent()` / `timingDataCommittedEvent()` facts.
- Make the Development Client target host/IP editable at runtime with an explicit Apply target action, and turn the previously grey API indicator into a clickable stateless HTTP CHECK that reports CHECKING / READY / UNREACHABLE.
- Promote **Device Log** and **Client Log** to independent top-level Development Client tabs, removing the nested Logs/source-tab structure while preserving separate runtime log-level controls.
- Correct the Development Client Logs tab: show Device Log first and Client Log last, and give both sources independent current/set-level controls while keeping the local client log usable when SI-01 is offline.
- Rename the standalone JavaFX tool to **Development Client** and align the API-first workbench: remove the prominent Set Location button, split TimingData Type/Code columns, retain node-scoped IF-03 problems, and give client log lines real source context while keeping Client and SI-01/Device logs separate.
- Contain TimingData startup-recovery failures to the LogBook: keep the TimingNode and diagnostic interfaces running, expose the LogBook recovery problem separately, allow functional OPEN/CLOSED to continue, and add black-box `VC-ST1-004` for a persisted NodeId mismatch.
- Make IF-03 OPEN carry the requested LocationId as one ordered TimingNode operation; the HTTP `/open` request now requires `locationId`, while the separate closed-state Set Location operation remains available for engineering/configuration work. Update the Engineering Client and VC-ST1-002 to use the combined OPEN operation.
- Implement the reviewed API-first Engineering Client workbench: one client configuration file for target/per-boundary ports and presentation defaults, API as the primary Status/Timing surface, top-level Events/Terminal/Device-log connection controls, separate Client versus SI-01 log sources, low local domain intelligence, split registration prefix/number plus readable date/time entry, and retained raw API results/errors/TimingData records for engineering inspection. Keep this change separate from the still-open Step-4 VC-ST1-003 evidence.

- Rename the transport-independent Application-layer presentation entry point from `CommandHandler` to `PresentationGateway`; the gateway continues to expose the same commands, queries, application metadata/capabilities and events without changing IF-03 behaviour or TimingNode execution semantics.

- Align SI-01 naming with the Timing Point Application boundary: rename the parent/core/app/system-test Maven artifacts to `timing-point-*`, keep shared `event-timing-data` under the Event Timing family namespace, use the stable IF-03 application identity `timing-application`, and rename SI-01-owned runtime threads with the `tp-<owner>-<role>[-<identity>]` diagnostic convention.

- Rename the standalone test-client Maven artifact and IDE labels to **Event Timing Engineering Client** while keeping its existing source directory/package boundary.

- Strengthen Step-4 VC-ST1-002 with a real second SI-01 process run against the same TimingData file, proving persisted LogBook recovery separately from WebSocket reconnect and confirming recovered history is not emitted as a new live commit.


- Add the Step-4 Engineering Client Timing view for the compact node-addressed IF-03 contract, including node selection, location/open/close control, capability-gated dev `auto-reg`, bounded LogBook loading, live committed TimingData merge/deduplication and stale/reconnect handling.


- Expose the Step-4 first-registration IF-03 application/transport slice: compact node-addressed status/control, capabilities, bounded LogBook queries, dev `auto-reg` injection and live committed/status WebSocket events.

- Compose the reference TimingData persistence stack in the real executable from `io.storage.timingData.path`, including startup recovery, default factory/codec and UTC recorded-time source.

- Add `TimingNode.registerAccepted(...)` as the shared post-filter registration boundary used by Step-4 engineering simulation and later RFID processing; it creates automatic TimingData through the same ordered durable commit path.

- Add a small typed local `Event<T>` primitive and publish newly committed TimingData after durable store append and LogBook visibility; listener RuntimeExceptions are isolated and reported without rolling back the commit.

- Rebuild LogBook from committed TimingData before the TimingNode serial worker starts; preserve CLOSED/no-location restart policy and expose repaired incomplete-tail recovery in TimingNode status.

- Add append-only `FileTimingDataStore` with canonical LF framing, `FileChannel.force(true)` append gating, contiguous-stream validation and explicit incomplete-tail recovery reporting.

- Add the default/reference IF-05 v1 TimingData JSON codec using Jackson streaming only, with canonical writer ordering, compatible extra-member reads and explicit invalid/version/record-type failures.

- Compact the shared TimingData API by grouping registration variants, manual time source and record key under `TimingData`, construction context under `TimingDataFactory`, codec failures under `TimingDataCodec`, and small shared identity values under source-only `TimingDataTypes` as `NodeId`, `LocationId` and `RegistrationId`; keep `TimingTimestamp` standalone.

- Clarify SerialWorker usage in Javadoc and distinguish queue `AdmissionResult` from the later `futureResult()` returned for accepted result-bearing work.

- Add the first manual TimingData commit path on the TimingNode serial lane with commit-time sequence allocation, TimeSource-based recorded time, store-before-LogBook ordering and failure blocking.

- Add the passive per-TimingNode LogBook and TimingDataStore append boundary as the foundation for ordered durable TimingData commits.

- Add the first serialized TimingNode state slice: bounded per-node execution, LocationId, CLOSED/OPEN lifecycle, processed state-operation results, timeout-as-unknown-outcome semantics and ordered status snapshots.

- Move the shared TimingData library to `shared/timing-data` and rename its artifact from `event-timing-data-api` to `event-timing-data`; keep semantic contracts, default/reference profile, codec and factory/provider in one JAR.

- Refine the IF-05 TimingData API around `TagId` / `TeamId` -> `RegistrationId`, one common `TimingDataContext`, and type-safe `AutomaticRegistrationTimingData` / `ManualRegistrationTimingData` factory returns; remove the premature universal state/revocation record model.

- Rename the reusable SI-01 Maven module from `framework/` to `core/` and the artifact from `event-timing-framework` to `event-timing-core`; keep `app/` as the thin executable boundary and keep Core out of the architecture-layer model.

- Add the narrow shared `TimingDataCodec` and `TimingDataProvider` SPI; codecs translate one record payload while stores own framing/recovery, and codec failures distinguish invalid data from unsupported version/record-type compatibility cases.

- Complete the first semantic IF-05 model slice with a provider-neutral `RegistrationIdentity`, positive configured `LocationID`, registration origin/time-source enums, exact nine-digit UTC `TimingTimestamp`, and immutable record-family factories for `TimingDataRecord` including revocation-reference validation.

- Start the Step-4 shared IF-05 boundary with a Java-8 shared TimingData reactor artifact (introduced as `event-timing-data-api`, later renamed to `event-timing-data`) and the first real interchange value type, `TimingDataRecordKey`; keep SI-01 `TimingNodeId` in the framework Domain model and represent the IF-05 record identity value as a validated string.

- Document `test-client/` consistently as the standalone **Engineering Client**, including its current UI/boundaries, Step-4 Timing/DebugConnector direction and planned deterministic CI screenshot workflow while keeping the module name and code unchanged.

- Rename SI-01 to **Timing Point Application**, move SI-01 framework/app Java packages under `io.github.brainboxemb.eventtiming.timingpoint`, and split runtime `Logging` from the independently composed `infra.loggingserver.LoggingServer` package while preserving the external `logging.live` YAML shape.

- Rename the programmable IF-03 presentation interface from `Remote API` to `API`, including `presentation.interfaces.api`, `Api*` configuration types, the `presentation.api` YAML key and engineering test-client names; HTTP/WebSocket wire paths and behavior are unchanged.

- Decouple black-box VC-ST1-001 from ordinary local/PR verification: make `system-test` an explicit Maven profile, keep NetBeans Build/Rebuild test-free, run the process-level test after affected protected-main integration, and require Linux plus Windows system-test evidence for release-tag qualification.

- Resume normal development on `0.2.3-SNAPSHOT` after the accepted `v0.2.2` Step-3 release; this mechanical successor does not pre-decide the eventual Step-4 release number.

## 0.2.2 — 2026-09-29

- Add a verification-only `system-test` reactor module for automated VC-ST1-001 black-box process testing: launch the packaged app JAR, verify HTTP version/status and WebSocket snapshot/reconnect behaviour, shut down via the remote terminal, and keep the verifier free of framework/app Java dependencies.

- Move the default IF-11 YAML loader and embedded build-identity interpretation into `event-timing-framework`; the executable now owns only its filtered provenance resource, provider selection, launcher and packaging while SnakeYAML becomes a framework implementation dependency.

- Move reusable `infra.logging` runtime infrastructure and component-owned logging configuration into `event-timing-framework`; keep only SLF4J provider selection in the executable app, and replace the bootstrap-owned live config with `LoggingServerConfig` plus an independent `LoggingLevel` type.

- Simplify the primary Remote API class names inside `presentation.interfaces.remoteapi` to `HttpEndpoint`, `WebSocketEndpoint` and `MessageWriter`; package structure and IF-03 behaviour are unchanged.

- Add Step-3 A08 runtime logging on the existing SLF4J/JUL boundary: executable-owned `infra.logging.Logging` and separate `LoggingServer`, configurable semantic startup level, timestamp-named rotating retained text files with non-overwriting collision handling for unsynchronised Pi clocks plus the same compact console/live representation, an optional client-initiated best-effort live diagnostics socket, temporary runtime-global level control, and a JavaFX Logs tab without mixing diagnostic records into IF-03 status/events.

- Organise source packages and artifact ownership around the architecture: keep primary IF-03 classes at `presentation.interfaces.remoteapi`, move `TimingApplication` runtime plus `infra.bootstrap.ApplicationBootstrap` and the effective configuration model into the reusable framework, and reduce `event-timing-app` to `TimingApplicationMain` plus concrete YAML/build-resource loaders.

- Add the Step-3 A07 IF-03 WebSocket event stream on a separate loopback-configured Java-WebSocket 1.6.0 listener with bounded inbound frames, snapshot-on-connect/reconnect, real-change-only `STATUS_CHANGED` broadcasting, and a Java 17 Events inspector in the standalone JavaFX test client.

- Start Step-3 A06 with loopback-configured IF-03 HTTP/JSON `/api/v1/version` and `/api/v1/status` resources, real HTTP adapter tests, TimingNode-focused shared status without a redundant application lifecycle block, and a standalone Java 17/JavaFX test client with versioned window title, Help/About build identity, manual version/status inspection and a built-in black remote-shell terminal.

- Start Step-3 A05 with an optional loopback-capable line-oriented TCP remote terminal, explicit `presentation.remoteShell` bind/port configuration, and shared command-session behaviour with the local console; no SSH/Telnet protocol or second command model is introduced.

- Replace wall-clock `buildTime` provenance with deterministic embedded `sourceRef`, `buildOrigin` and `dirty` fields alongside version/revision, so one JAR identifies its source/build context without CI run/user/timestamp sidecars.

- Add the Step-3 A04 local console/debug shell with `help`, `version`, `status`, `quit` and `exit`; expose the first real shared `ApplicationStatus` query through `CommandHandler` and document a clean-checkout Windows acceptance session.

- Add the Step-3 A03 process lifetime: a configured application remains running until shutdown, waits without polling, and uses a JVM shutdown hook so Ctrl+C / normal OS shutdown closes the existing application lifecycle cleanly.

- Start SIP Step-3 A02 with one external YAML configuration file: load and validate a single `TimingNodeId`, introduce the minimal reusable `TimingNodeId` / `TimingNode` domain objects, and compose that configured TimingNode without prebuilding presentation, overlay or I/O configuration models.

- Align implementation terminology with the accepted architecture: future logical timing aggregates are `TimingNode` objects identified by `TimingNodeId`; update README scope/examples without introducing compatibility types before the first real Step-3 configuration slice.

- Resume the active Step-3 `0.2.x` development line as `0.2.2-SNAPSHOT` after the published `v0.2.1` release, preventing ordinary `main` pushes from being misclassified as attempts to recreate `v0.2.1`.

- Inline embedded build-metadata reading into `TimingApplication`, make the executable composition directly own its `BuildIdentity`, and remove the standalone `BuildIdentityLoader` class; deployment configuration remains a separate Step-3 concern.

- Simplify the first-executable implementation around real behaviour: keep the minimal shared `CommandHandler.version()` boundary and compact `TimingApplication.Builder`, remove bootstrap-only `*Layer` markers and premature status-model objects, and move `BuildIdentity` to `infra` because build provenance is infrastructure rather than application/domain state.

- Route repository agent guidance through `brainboxemb.meta/AGENTS.md`, make dependency-owner AGENTS explicitly non-inherited, and keep implementation-specific architecture/boundary guidance local.

## 0.2.1 — 2026-09-17

- Publish the intended 0.2.x product baseline after the first tagged `0.2.0` candidate was archived as failed during final GitHub Release evidence packaging.
- Fix GitHub Release packaging so it consumes the finalized immutable `rel/vX.Y.Z/bld` tree, including durable `orchestration/**` evidence, rather than the pre-finalization Actions artifact.
- Introduce the shared `application` responsibility as the authoritative semantic home for application build identity and current status.
- Move executable build-metadata loading behind composition and map it into the reusable framework `BuildIdentity`, including IF-03 API major version `1`.
- Add immutable first-executable status snapshots with application state, minimal timing-system status and observable problem values, plus one application-owned current-status authority for later presentation adapters.
- Keep process hosting/shutdown, external settings and concrete console/remote/HTTP/WebSocket adapters deferred to their later SIP Step-3 activities.
- Adopt released `tool.java-project v0.3.2` and `tool.git-project v0.2.8` as the Migration-006 execution baseline.
- Replace repository-owned Linux/Windows/Moon build orchestration with shared Java production workflows while keeping Maven authoritative for the multi-module reactor.
- Keep Moon as `java.canonical` / `java.windows-full` impact declarations only and retain exact preflight/timing evidence in generated `bld` output.
- Use event-sensitive Windows qualification: pull requests select `auto`, ordinary protected-main publication uses `none`, and exact release-tag qualification uses `full`.
- Run native full-Windows Maven release qualification in parallel with the Linux canonical producer and run exact Linux-artifact smoke after the canonical artifact is available.
- Preserve repository-owned release metadata, embedded build-identity validation, logging dependency boundaries and both product release JARs.

## 0.2.0-failed — 2026-09-17

- Candidate `v0.2.0` at exact source `e8066c9e33cf2ed77bb7f37ac8a68707933e28e8` passed Linux canonical Maven, independent native Windows Maven and exact Linux-produced application-JAR smoke on Windows.
- Finalized `rel/v0.2.0/bld` publication also succeeded, but GitHub Release packaging incorrectly read the pre-finalization Actions artifact and failed because `orchestration/**` was not present there yet.
- The release fail-safe removed normal `v0.2.0` and preserved the consumed candidate as `v0.2.0-failed`; version `0.2.0` is not reused.

## 0.1.0 — 2026-09-13

- Establish `0.1.0` as the SIP Step-2 software baseline for the reusable framework and minimal runnable application.
- Consume the released `tool.java-project v0.1.0` toolchain baseline through `project.yml`, while keeping the committed gitlink and reusable workflow callers pinned to the exact immutable release commit.
- Record the released Java-tooling baseline and its external `template.java-project` conformance role in the framework documentation.
- Archive failed release candidates as `vX.Y.Z-failed`, remove the corresponding normal release tag, and never reuse a consumed release version; the next attempt advances the patch version and records the failed attempt in this changelog.

## 0.0.1 — 2026-09-13

- Bootstrap the SI-01 Maven reactor with one reusable `event-timing-framework` library and one runnable `event-timing-app` consumer.
- Keep `domain`, `core`, `platform`, and `comm` as package/architecture responsibilities inside the framework artifact rather than speculative separate libraries.
- Adopt Java SE 8, Maven 3.9.16 and Maven Wrapper 3.3.4.
- Adopt `tool.git-project` for clean-checkout repository bootstrap/dependency restoration.
- Consume the reusable `tool.java-project` CI workflow as the first external product repository.
- Replace the wiring-only application smoke shell with an explicit minimal `NEW -> RUNNING -> STOPPED` lifecycle and automated lifecycle tests.
- Embed application/build identity in the executable artifact instead of hard-coding it in Java source:
  - Maven `${project.version}` remains the authoritative software version;
  - `git-commit-id-plugin` 4.9.10 captures the full source revision and UTC build timestamp on the Java 8 baseline;
  - runtime startup logging exposes the concrete build provenance while the stable CI smoke line remains version-focused.
- Add intent-focused Javadoc/design references for the executable composition, lifecycle and build-identity boundary, and record the code-documentation convention in `AGENTS.md`.
- Adopt SLF4J as the logging facade while keeping the framework provider-neutral; the executable application selects `slf4j-jdk14` / `java.util.logging` for the initial runtime composition.
- Keep Step-2 execution deliberately short-lived and deterministic; HTTP/WebSocket, long-running service behaviour and timing-domain capability remain later work.
- Adopt generated build-output publication from the pinned `tool.java-project` revision:
  - PR builds publish both product JARs and evidence to `dev/pr-N/bld`;
  - `main` publishes the same canonical output to `prod/bld`;
  - the publication reuses the canonical Linux build rather than rebuilding solely for publication.
- Publish a readable aggregate unit-test report from the canonical Surefire results while retaining the raw XML/TXT evidence.
- Add version-aware release verification so release versions, CHANGELOG sections, Git tags, embedded build identity and retained GitHub Release artifacts are checked as one release baseline.
