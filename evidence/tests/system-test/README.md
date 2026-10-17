# Separate-process system verification

Retained evidence from the VC-ST1 black-box verification of source revision
`a79ce4fa4cc2809cee3cf13fd0db40d801893b2d`.

- **VC-ST1-001:** PASS — executable/API/reconnect/control-shutdown baseline.
- **VC-ST1-002:** PASS — first committed registration, WebSocket reconnect and persisted LogBook recovery across a full SI-01 restart; recovered history is not emitted as a new live commit.
- `VC-ST1-002/timing-data.jsonl` is the actual persistence file written by SI-01 during the test.
- `surefire-reports/` contains the JUnit reports for both separate-process tests.

Each VC directory also retains the generated application configuration,
captured SI-01 process output and the explicit PASS/FAIL result.
