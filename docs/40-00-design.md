# Java implementation design

## Authority boundary

This document describes the **current Java implementation decomposition**. Product
requirements and architecture remain owned by the companion meta repository, especially
the SI-01 SSD and focused SDDs.

If this implementation diverges from accepted design, that divergence is a review item;
this document does not make the code authoritative over the design.

## Maven artifacts

The normal product reactor contains:

```text
shared/timing-data/   event-timing-data
core/                 timing-point-core
app/                  timing-point-app
```

The verification-only `system-test` module is added only by the explicit
`system-test` Maven profile. The root parent POM is build/aggregation metadata.

The standalone `test-client` is a Java 17 / JavaFX engineering application and is not
part of the Java-8 SI-01 product reactor.

## Main implementation boundaries

Current core responsibilities include:

```text
application/
  PresentationGateway
  TimingNodeProxy
  TimingNodeStatus

domain/timing/
  TimingNode
  TimingNodeLogic
  TimingNodeTypes
  TagProcessor
  TagId
  TagRegistrationResolver

domain/timingdata/
  DefaultTimingDataPersistence

io/devices/antenna/
  Antenna
  SimulatedAntenna

io/storage/
  AppendOnlyRecordStore
  FileAppendOnlyRecordStore

platform/execution/
  SerialWorker

platform/environment/
  MonotonicClock
  SystemMonotonicClock
  RuntimeObservation

runtime/
  Application
  Composition
  Lifecycle
  config/...

presentation/interfaces/
  api/...
  console/...
  shell/...
```

Runtime composition owns the concrete graph. Lower I/O/storage code does not import
Application/Domain semantics.

## TimingNode execution

`TimingNode` is the visible Domain boundary used by Application.

- result-bearing commands use the node's ordered serial execution lane;
- submission-only producer ingress receives only bounded admission;
- consistency-sensitive reads are ordered through the same ownership boundary;
- sequence allocation, active LocationId, persistence, LogBook visibility and committed
  events remain TimingNode-owned.

`DefaultTimingDataPersistence` owns TimingData-specific codec/node/sequence semantics
above generic append-only storage.

## Simulated input and runtime observation

Current main contains `SimulatedAntenna`, `TagProcessor` and pull-based runtime
observation/counter code. These classes are implementation evidence currently under the
project's explicit meta design review for the simulated-input and observability
boundaries. Their presence in main must not be read as bypassing that review.

## Presentation and Development Client

Presentation adapters reach domain behaviour through Application boundaries rather than
importing TimingNode implementation directly.

The Development Client uses only supported external SI-01 boundaries and has no Java
dependency on `timing-point-core` or `timing-point-app`.

## Extension/provider model

Provider discovery is startup/composition work. Runtime/domain components receive normal
typed capability interfaces; they do not interact with class loaders or a generic plugin
object.

Exact product design remains in the meta SDDs.
