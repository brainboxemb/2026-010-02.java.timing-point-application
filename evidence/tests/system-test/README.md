# Separate-process system verification

Retained evidence from the VC-ST1 black-box verification of source revision
`483da15e6e693ab213503744f43e0d419d337bce`.

- **VC-ST1-001:** PASS — executable/API/reconnect/control-shutdown baseline.
- **VC-ST1-002:** PASS — first committed registration, WebSocket reconnect and persisted LogBook recovery across a full SI-01 restart; recovered history is not emitted as a new live commit.
- `VC-ST1-002/timing-data.jsonl` is the actual persistence file written by SI-01 during the test.
- `surefire-reports/` contains the JUnit reports for both separate-process tests.

Each VC directory also retains the generated application configuration,
captured SI-01 process output and the explicit PASS/FAIL result.
