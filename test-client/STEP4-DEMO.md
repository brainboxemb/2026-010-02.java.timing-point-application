# Step-4 V04 / VC-ST1-003 Development Client demo

This checklist is the executable/manual procedure for `VC-ST1-003 — Development Client reconnect/resynchronisation integration`, the Step-4 V04 running-system evidence,
after `VC-ST1-002` is green. It uses only public interfaces and the
JavaFX Development Client.

`VC-ST1-002` already proves the SI-01 server-side lifecycle, first registration,
LogBook/history, WebSocket reconnect and persisted restart recovery. V04 does not repeat
that proof. V04 verifies the client integration that the server-only black-box test cannot
prove:

- the editable target host/IP and **Apply target** select the SI-01 instance used by all
  external boundaries;
- the stateless IF-03 HTTP boundary is checked explicitly through **API CHECK**, rather
  than being presented as a persistent connection;
- the main tabs are **API | Events | Device Log | Terminal | Client Log**;
- Device Log and Client Log are independent sources with independent level controls;
- connecting Events immediately starts the client resynchronisation path before the
  Timing view becomes LIVE;
- supported state-changing controls become available once the selected TimingNode view is
  LIVE without the client reimplementing SI-01 lifecycle-acceptance rules;
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

Open the **API** tab. Verify the configured target host/IP is visible, edit it if needed,
choose **Apply target**, then use **API CHECK**. Connect **Events** from the target bar;
the Events connection is what starts the history/status resynchronisation flow.

## V04 flow

1. Verify the top target bar shows:
   - editable target host/IP;
   - **Apply target**;
   - **API :8081 CHECK**;
   - separate Events, Terminal, Device Log and Client Log boundary controls/states.
2. Choose **API CHECK** and verify it reaches **READY**.
3. Verify the main tab order is:
   **API | Events | Device Log | Terminal | Client Log**.
4. Verify **Client Log** is usable before connecting SI-01 Device Log and that each log
   tab has its own current/set level controls.
5. Connect **Events**.
   - the prominent **Timing view** state above Version/Status changes from
     **NOT SYNCED — connect Events** to **SYNCING**;
   - state-changing controls remain unavailable while synchronising;
   - the view becomes **LIVE** only after status/capabilities/LogBook baseline recovery;
   - TimingNode `TN-01` is shown as `CLOSED` with no current LocationId.
6. Enter LocationId `24` and choose **Open**.
   - Last operation shows `OPENED`;
   - state becomes `OPEN`;
   - LocationId becomes `24`;
   - no preceding location-setting request is required.
7. Enter registration prefix `N` and number `0001`.
8. Enter date `2026-10-01` and time `12:00:00`. Verify the UI shows the interpreted
   client time zone next to the Time field and that the public record contains the
   correct canonical UTC equivalent. For `Europe/Amsterdam` on this date, `12:00:00`
   becomes `2026-10-01T10:00:00Z`.
9. Choose **Send auto-reg**.
   - Last operation shows `seq 1`;
   - the interpreted **Registrations** view contains one row with local clock time,
     RegistrationId `N0001`, Code **AUTO** and no deleted state;
   - LogBook count becomes `1`;
   - the technical LogBook contains sequence 1 / Type `AUTO_REG` / Code `ADD` /
     LocationId 24 / RegistrationId `N0001`;
   - **Type** and **Code** are separate LogBook columns;
   - selecting the LogBook row exposes the complete public record/raw representation;
   - the **Events** tab contains one `TIMING_DATA_COMMITTED` event for that same record.
10. Connect **Device Log** and verify it remains a separate top-level source from
    **Client Log**. Change one source's level and verify the other source's level is
    unaffected.
11. Choose **Close**.
    - Last operation shows `CLOSED`;
    - state becomes `CLOSED`;
    - LocationId remains `24`.
12. Enter LocationId `25` and choose **Open** again.
    - Last operation shows `OPENED`;
    - state becomes `OPEN`;
    - LocationId becomes `25`.
13. Choose **Close** and verify the node returns to `CLOSED`.

The Development Client deliberately keeps supported Open/Close requests sendable once
the selected node view is LIVE. SI-01 remains authoritative for lifecycle-dependent
acceptance; the client does not reimplement those acceptance rules locally.

## Reconnect / resynchronisation scenario

1. Open the **Terminal** tab, connect and enter `quit`.
2. Verify SI-01 exits cleanly and the Events/API history view becomes stale or
   disconnected while **Client Log** remains available.
3. Keep `data\step4-demo-timing-data.jsonl`; restart SI-01 with the same demo
   config.
4. Use **API CHECK** and verify IF-03 HTTP is READY again.
5. Reconnect **Events**.
6. Verify the **client**:
   - enters syncing/reconnecting before becoming LIVE;
   - keeps state-changing controls disabled while synchronising;
   - resynchronises current status to `CLOSED` with no operational LocationId;
   - resynchronises LogBook count `1` with sequence 1 / `N0001`;
   - does not add the recovered sequence-1 row again as a new live event;
   - merges any history/live overlap by stable TimingData record key;
   - reaches LIVE only after the baseline and buffered events are reconciled.
7. Shut SI-01 down cleanly.

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

Editable target / Apply target PASS / FAIL
API CHECK -> READY             PASS / FAIL
Top-level tab order            PASS / FAIL
Device/Client Log independence PASS / FAIL
Initial CLOSED/no-location     PASS / FAIL
OPEN with LocationId 24        PASS / FAIL
Auto-reg N0001 -> seq 1        PASS / FAIL
Displayed time zone + UTC conversion PASS / FAIL
Interpreted local-time Code row PASS / FAIL
Type / Code separate LogBook columns PASS / FAIL
Raw selected record visible    PASS / FAIL
Live committed event           PASS / FAIL
LogBook count/row              PASS / FAIL
Re-open with LocationId 25     PASS / FAIL
Prominent Timing-view sync state PASS / FAIL
Reconnect/resynchronisation before LIVE PASS / FAIL
History/live deduplication     PASS / FAIL
Client Log remains available   PASS / FAIL
Clean shutdown                 PASS / FAIL
```

Screenshots may be retained as supporting UI evidence, but the pass/fail result
comes from the observed behaviour above.
