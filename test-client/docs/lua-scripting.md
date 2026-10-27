# Engineering Client — Lua scripting

SI-02 embeds **LuaJ 3.0.1** (Lua 5.2-style language). Scripts execute **locally in the Engineering Client**, never in SI-01.

## Run and availability

The editor starts with a **local-only hello/log example**; the simulated-tag batch is commented out. A normal script remains runnable without tag-simulation support, whereas uncommenting the batch requires SI-01 to advertise the enabled capability. If the client says `TAG_SCENARIO_SIMULATION unavailable`, inspect the **API** tab and verify you restarted the *SI-01 process* with the intended NetBeans configuration; upgrading SI-02 alone does not alter SI-01 capabilities.

1. Open **Scripts** beside **Terminal**. A generic script may run even while Events/Central are disconnected and without a selected TimingNode.
2. Edit the source, use **Open .lua**, or drag a UTF-8 `.lua` file onto the Scripts panel. **Open .lua** defaults to the tool's standard `scripts/` directory (`test-client/scripts/` in a source checkout and `scripts/` beside the packaged Windows launcher).
3. Click **Run**; read Output and use **Stop** to cancel. Opening or dropping a script does not execute it.

Connection/client automation and Central-owned configuration do not require a selected node. **Simulation** commands require the separate SI-01 capability `TAG_SCENARIO_SIMULATION`. The capability depends on a configured simulated antenna and compatible topology/data, **not** on a specific EventData provider ID. On Windows, the current single-node development fallback uses `SimulatedAntenna`, so both `config/application.yml` and `config/development-simulation.yml` can advertise the capability. Both select the same `reference` EventData mappings; the latter uses an isolated engineering LogBook. A physical-antenna runtime must not expose simulated-tag control unless that capability is explicitly composed. Verify the actual advertised values in the Engineering Client **API** tab after Events reaches LIVE.

## Application API
- `timing.log(text)`: append to script Output.
- `print(...)`: append tab-separated arguments to Output.
- `timing.now([offsetSeconds])`: current UTC timestamp text; for example `timing.now(-600)` is ten minutes before now.
- `timing.sleep(ms)`: interruptible sleep for 0–60000 milliseconds.
- `timing.simulate(regId, profile)`: send one simulated passage for selected TimingNode via IF-03, e.g. `timing.simulate("A0100", "simple")`. Profile defaults to `normal`.
- `timing.batch{count=10, from=1, to=2000, order="linear", seed=1, intervalMs=500, profile="normal"}`: send repeatable batch. `count` is required (1–10000); remaining values shown are defaults. Order: `linear` or seeded `random`. Profiles: `simple`, `normal`, `edge`. Intervals: 0–60000 ms. Generated IDs: `ANNNN`.
- `central.team_set(regId, teamId)` / `central.team_del(regId)`: mutate global Central Team Mapping state.
- `central.stage_start_set(regId, start)` / `central.stage_start_del(regId)`: mutate Central-owned RegistrationId-scoped StageStartTimes.
- `central.stage_start_range{prefix="A",from=1,to=100,start=timing.now(-600),intervalMs=0}`: bulk StageStartTimes authoring through the same Central state/range logic as the GUI.
- `central.stage_start_range_del{prefix="A",from=1,to=100}`: bulk delete.
- `central.timing_data_req(fromSeq)`: node-specific TIMING_DATA_REQ using the TimingNode captured at Run start.
- `client.reset()`: reset SI-02 to a clean disconnected state and clear Central simulator source/view state while retaining the configured target.
- `client.events_connect()` / `client.events_disconnect()`: control the ordinary IF-03 Events session.
- `client.central_connect()` / `client.central_disconnect()`: promote/demote the IF-03 event session to/from the Central upstream role.
- `client.api_available()`, `client.events_connected()`, `client.central_connected()`: inspect current boundary state. API is HTTP reachability, not a persistent connection.
- `client.status()`: returns `apiAvailable`, `eventsConnected`, and `centralConnected`.
- `client.wait(boundary, expected[, timeoutMs])`: wait for `"api"`, `"events"`, or `"central"`; default timeout 5000 ms, maximum 60000 ms.

### Local smoke test (simulation not required)
```lua
print("Lua works")
for i = 1, 3 do
  timing.log("Step " .. i)
  timing.sleep(100)
end
```

### Simulated-tag batch
```lua
timing.batch{count=10, from=1, to=100, order="random",
             seed=42, intervalMs=500, profile="normal"}
```

### Central configuration
```lua
local start = timing.now(-600)
central.stage_start_range{
  prefix="A", from=1, to=100,
  start=start, intervalMs=0
}
```

### Central reconnect exercise
```lua
client.reset()
assert(client.api_available())

client.central_connect()
client.wait("central", true, 5000)

client.central_disconnect()
client.wait("central", false, 5000)
assert(client.events_connected())

client.central_connect()
client.wait("central", true, 5000)
```

`timing.sleep(ms)` paces script actions. SI-01's configured
`upstream.timingDataReplayPacketDelayMillis` separately controls the delay between
successive TIMING_DATA packets inside one replay.

See [../scripts/batch.lua](../scripts/batch.lua), [../scripts/stage-starts.lua](../scripts/stage-starts.lua) and [../scripts/central-reconnect.lua](../scripts/central-reconnect.lua) for maintained examples.

## Boundaries
One script at a time; GUI and Lua batches cannot run simultaneously. Client disconnect/reconnect does not stop a running script. Node-specific calls use the TimingNode captured at Run start and reject when no node was selected. Simulation calls require the advertised capability. Maximum script file size: 128 KiB. Stop cannot undo already accepted commands.

The interpreter does not expose `io`, `os`, `package`, `require`, `luajava`, `loadfile`, `dofile`, Java reflection, or arbitrary process/file/network access. The restrictions are not a security sandbox: run trusted engineering scripts only.

The current `client.*` facade controls one configured SI-02 target. Multi-system orchestration and TimingNode OPEN/CLOSE commands remain separate future extensions.
