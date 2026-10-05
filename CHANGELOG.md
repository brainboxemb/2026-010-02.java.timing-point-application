# Changelog

## Unreleased

- Split SerialExecutor and SerialScheduledExecutor measurement state into dedicated metrics classes and clarify logical-lane versus physical-worker lifecycle ownership in code documentation.

- Share TimingNode and TagProcessor physical workers by runtime role while keeping per-node bounded serial lanes; worker thread names now describe the shared role rather than a specific TimingNode.

- Add conventional packaged-application startup options (`--help`, `--version`, `--config`) and `--generate-config`, which writes the complete version-matched example IF-11 YAML without silently overwriting an existing target.

- Replace all-or-nothing antenna startup with independent per-antenna health/lifecycle, optional external power control, TimingNode-driven inventory activation and mutual-exclusion inventory multiplexing; extend the simulated antenna path with power and injected-failure behaviour.

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
- Contain TimingData startup-recovery failures to the affected TimingNode: keep SI-01 and diagnostic interfaces running, expose node state `ERROR` with `TIMING_DATA_RECOVERY_FAILED`, reject normal operations on the errored node, and add black-box `VC-ST1-004` for a persisted NodeId mismatch.
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
