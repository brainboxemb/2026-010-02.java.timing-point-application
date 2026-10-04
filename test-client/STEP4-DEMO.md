# Step-4 V04 / VC-ST1-003 Development Client demo

This checklist is the executable/manual procedure for `VC-ST1-003 — Development Client reconnect/resynchronisation integration`, the Step-4 V04 running-system evidence,
after `VC-ST1-002` is green. It uses only public interfaces and the
JavaFX Development Client.

`VC-ST1-002` already proves the SI-01 server-side lifecycle, first registration,
LogBook/history, WebSocket reconnect and persisted restart recovery. V04 does not
repeat that proof. V04 verifies the client integration that the server-only
black-box test cannot prove:

- supported state-changing controls are unavailable while synchronising and become
  available once the selected TimingNode view is LIVE, without the client reimplementing
  SI-01 lifecycle-acceptance rules;
- reconnect enters a resynchronisation/sync state before LIVE;
- current status and bounded LogBook history are rebuilt before LIVE;
- later live events are buffered while that baseline is rebuilt;
- history/live overlap is merged by the stable TimingData record key;
- recovered history is not presented as a new live commit.

The restart remains in the demo because it gives a repeatable non-empty-history
resynchronisation scenario.

## Prepare

Use a clean checkout of the intended Java revision. The SI-01 application is
Java 8; the Development Client is Java 17.

Build the application with the repository Maven Wrapper:

```powershell
.\mvnw.cmd package -DskipTests
```

The V04 demo uses `config\step4-demo.yml`, which writes to the dedicated file
`data\step4-demo-timing-data.jsonl`. To start a fresh demo, remove only that
file:

```powershell
Remove-Item -Force data\step4-demo-timing-data.jsonl -ErrorAction SilentlyContinue
```

Do **not** remove the file between the first run and the restart/recovery part of
the demo.

## Start SI-01

From a terminal using Java 8:

```powershell
java -jar app\target\timing-point-app-0.2.3-SNAPSHOT.jar config\step4-demo.yml
```

Expected development endpoints:

```text
HTTP       http://127.0.0.1:8081
WebSocket  ws://127.0.0.1:8082/api/v1/events
Shell      127.0.0.1:8023
Live logs  127.0.0.1:8030
```

## Start the Development Client

In a second terminal using Java 17:

```powershell
.\mvnw.cmd -f test-client\pom.xml javafx:run
```

Open the **API** tab. Connect **Events** from the target bar when live-event inspection is needed.

## V04 flow

1. Verify the API workbench first shows **CONNECTED / syncing** or
   **SYNCING**, keeps state-changing controls disabled during synchronisation, and
   only then becomes **LIVE**. Verify it shows:
   - TimingNode `TN-01`;
   - state `CLOSED`;
   - no current LocationId;
   - LocationId input plus **Open** and **Close** once LIVE;
   - **Auto-reg** available when the advertised development capability is enabled.
2. Enter LocationId `24` and choose **Open**.
   - Last operation shows `OPENED`;
   - state becomes `OPEN`;
   - LocationId becomes `24`;
   - no preceding location-setting request is required.
3. Enter registration ID `N0001`.
4. Use the explicit time below for deterministic evidence:
   `2026-10-01T12:00:00Z`.
5. Choose **Auto-reg**.
   - Last operation shows `seq 1`;
   - LogBook count becomes `1`;
   - the table contains sequence 1 / Type `AUTO_REG` / Code `ADD` /
     LocationId 24 / RegistrationId `N0001`;
   - **Type** and **Code** are separate columns;
   - the **Events** tab contains one `TIMING_DATA_COMMITTED` event for that
     same record.
6. Choose **Close**.
   - Last operation shows `CLOSED`;
   - state becomes `CLOSED`;
   - LocationId remains `24`.
7. Enter LocationId `25` and choose **Open** again.
   - Last operation shows `OPENED`;
   - state becomes `OPEN`;
   - LocationId becomes `25`.
8. Choose **Close** and verify the node returns to `CLOSED`.

The Development Client deliberately keeps supported Open/Close requests sendable once
the selected node view is LIVE. SI-01 remains authoritative for lifecycle-dependent
acceptance; the client does not reimplement those acceptance rules locally.

## Reconnect / resynchronisation scenario

1. Open the **Terminal** tab, connect to `127.0.0.1:8023` and enter `quit`.
2. Verify the application exits cleanly and the API workbench becomes stale or
   disconnected.
3. Keep `data\step4-demo-timing-data.jsonl`; restart SI-01 with the same demo
   config.
4. Reconnect the Development Client.
5. Verify the **client**:
   - enters syncing/reconnecting before becoming LIVE;
   - keeps state-changing controls disabled while synchronising;
   - resynchronises current status to `CLOSED` with no operational LocationId;
   - resynchronises LogBook count `1` with sequence 1 / `N0001`;
   - does not add the recovered sequence-1 row again as a new live event;
   - merges any history/live overlap by stable TimingData record key;
   - reaches LIVE only after the baseline and buffered events are reconciled.
6. Shut SI-01 down cleanly.

The fact that the server can recover the persisted row across the process restart
is already automated in `VC-ST1-002`; here it is the stimulus used to verify the
Development Client resynchronisation behaviour.

## Record VC-ST1-003 evidence

Record these values with the pass/fail notes:

```text
SI-01 source revision :
SI-01 version         :
Development Client rev:
Operating system      :
SI-01 Java            :
Client Java           :

Initial CLOSED/no-location     PASS / FAIL
OPEN with LocationId 24        PASS / FAIL
Auto-reg N0001 -> seq 1        PASS / FAIL
Type / Code separate columns   PASS / FAIL
Live committed event           PASS / FAIL
LogBook count/row              PASS / FAIL
Re-open with LocationId 25     PASS / FAIL
Reconnect/resynchronisation before LIVE PASS / FAIL
History/live deduplication     PASS / FAIL
Clean shutdown                 PASS / FAIL
```

Screenshots may be retained as supporting UI evidence, but the pass/fail result
comes from the observed behaviour above.
