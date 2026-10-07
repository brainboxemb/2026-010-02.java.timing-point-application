# Runtime characterization harness

Engineering-only deterministic runtime characterization for SIP Step 5.

This module is **not** part of the normal product reactor or application artifact. Build
or run it explicitly:

```bash
./mvnw verify -Pruntime-characterization

# Install the selected reactor artifacts once, then run only the harness module.
./mvnw -Pruntime-characterization -pl runtime-characterization -am install
./mvnw -Pruntime-characterization -pl runtime-characterization exec:java \
  -Dexec.args="--workload steady --output target/evidence/steady.json"
```

The harness depends directly on `timing-point-core`, composes one TimingNode plus one
SimulatedAntenna, and drives the normal:

```text
SimulatedAntenna
  -> TagProcessor
  -> TimingNode
  -> TimingData persistence / LogBook
```

path. It reads only the engineering `RuntimeMeasurementReader` boundary.

Available workload shapes:

- `steady` — paced observations at the configured aggregate rate;
- `burst` — the configured measured input set emitted without pacing;
- `history` — preloads a committed history and then runs the paced measured workload.

The JSON evidence is characterization input for SIP V01/A03. It is not a product
performance result or a product limit.
