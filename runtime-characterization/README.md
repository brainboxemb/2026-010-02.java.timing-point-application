# Runtime characterization harness

Engineering-only deterministic runtime characterization for SIP Step 5.

This module is **not** part of the normal product reactor or application artifact. Build
or run it explicitly:

```bash
./mvnw verify -Pruntime-characterization -pl runtime-characterization -am

# Install the selected reactor artifacts once, then run only the harness module.
./mvnw -Pruntime-characterization -pl runtime-characterization -am install
./mvnw -Pruntime-characterization -pl runtime-characterization exec:java \
  -Dexec.args="--workload steady"
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

By default, every command-line run gets its own run id and writes:

```text
runtime-characterization/target/evidence/<run-id>.json
runtime-characterization/target/work/<run-id>/timing-data.jsonl
```

Use `--output` and `--work-dir` only when an explicit location is useful.

The JSON evidence is characterization input for SIP V01/A03. It is not a product
performance result or a product limit.


## V01 repeatable baseline

The retained development-host V01 baseline is run with:

```bash
bash tools/run_runtime_characterization_baseline.sh
```

It executes three repetitions of four fixed cases:

- `steady` — 100 measured observations paced at 20 registrations/s;
- `burst` — 100 measured observations delivered without pacing;
- `history-1000` — paced workload after 1,000 committed records are preloaded;
- `history-9999` — paced workload after 9,999 committed records are preloaded.

Every run uses 20 warm-up observations and a bounded history query limit of 100.
The GitHub workflow publishes successful main/manual baseline evidence to the generated
`prod/characterization` branch under
`v01/development-host/<source-sha>/`.

This baseline is engineering evidence for the recorded host/JVM only. It is not a product
performance limit and it does not replace later Raspberry Pi target characterization.
