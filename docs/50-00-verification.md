# Verification

## Authority

The companion meta repository owns verification strategy and stable VTS cases.

This repository owns executable tests, CI execution and retained implementation evidence.

## Normal Maven verification

```bash
./mvnw verify
```

runs the normal product reactor tests. It does not start the separate packaged application
for formal black-box cases.

## Black-box system verification

```bash
./mvnw verify -Psystem-test
```

adds the `system-test` module after packaging SI-01.

The module:
- launches the packaged application as a separate process;
- uses only supported external interfaces;
- imports no application/core product classes;
- retains evidence under the formal VC identifier.

Current executable cases include:
- VC-ST1-001 — packaged startup/version/status/events/shutdown;
- VC-ST1-002 — public registration flow, committed LogBook, reconnect and restart recovery;
- VC-ST1-004 — contained TimingData recovery failure with diagnostics preserved.

The formal meaning and expected results remain in the VTS.

## Manual Development Client verification

VC-ST1-003 requires the real JavaFX Development Client. The meta VTS is normative; the
repository-local checklist is execution support and may not redefine the case.

See [VC-ST1-003 checklist](../test-client/VC-ST1-003.md).

## CI qualification

The canonical Linux Maven producer retains:
- product artifacts;
- Surefire test results;
- execution log/evidence;
- toolchain/build provenance.

Windows qualification is selected by event/impact. Exact release-tag qualification
performs full Windows Maven verification, Linux verification, black-box VC-ST1 profile
execution on both platforms and exact Linux-artifact Windows smoke where required.

Generated evidence is published under PR/main/release `bld` branches according to the
repository workflow.

## Evidence rule

A passing source document or checklist is not execution evidence. PASS/FAIL belongs to
the exact run/revision evidence produced by tests or a recorded manual execution.
