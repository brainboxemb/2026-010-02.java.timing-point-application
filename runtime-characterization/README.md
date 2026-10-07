# Runtime characterization harness

This module is engineering-only tooling for SIP Step 5 runtime characterization.
It is not part of the normal application reactor or product artifact.

Build and test the optional module:

```bash
./mvnw verify -Pruntime-characterization -pl runtime-characterization -am
```

Run one default steady characterization:

```bash
./mvnw package -Pruntime-characterization -pl runtime-characterization -am
./mvnw -Pruntime-characterization -pl runtime-characterization exec:java
```

The default workload is 100 measured observations at 20 observations/s aggregate,
with 20 warm-up observations. The rate is an engineering workload input, not a
product limit.

Useful options:

```text
--workload steady|burst|history
--observations <count>
--rate <per-second>
--warmup <count>
--history <count>
--repetitions <count>
--evidence-dir <path>
```

By default, retained JSON summaries are written under
`runtime-characterization/target/evidence`. Each repetition gets one JSON
summary plus a separate work directory containing the file-backed TimingData
stream used by that run.

The measured input route is the product simulated-input path:

```text
SimulatedAntenna
  -> AntennaManager event
  -> TagProcessor
  -> TimingNode
  -> TimingData persistence / LogBook
```

The harness reads engineering diagnostics only through
`runtime.measurement.RuntimeMeasurementReader`. It does not add an IF-03
metrics endpoint and does not use the formal black-box `system-test` module.
