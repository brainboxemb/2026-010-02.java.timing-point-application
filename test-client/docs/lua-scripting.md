# Engineering Client — Lua scripting

SI-02 embeds **LuaJ 3.0.1** (Lua 5.2-style language). Scripts execute **locally in the Engineering Client**, never in SI-01.

## Run and availability

The editor starts with a **local-only hello/log example**; the simulated-tag batch is commented out. A normal script remains runnable without tag-simulation support, whereas uncommenting the batch requires SI-01 to advertise the enabled capability. If the client says `TAG_SCENARIO_SIMULATION unavailable`, inspect the **API** tab and verify you restarted the *SI-01 process* with the intended NetBeans configuration; upgrading SI-02 alone does not alter SI-01 capabilities.

1. Connect Events and wait for baseline synchronisation (**LIVE**). Select a TimingNode.
2. In **Scripts**, edit the source, use **Open .lua**, or drag a UTF-8 `.lua` file onto the Scripts panel.
3. Click **Run**; read Output and use **Stop** to cancel. Opening or dropping a script does not execute it.

Local Lua scripts can run when the client is LIVE even if simulation is disabled. **Simulation** commands require the separate SI-01 capability `TAG_SCENARIO_SIMULATION`. The capability depends on a configured simulated antenna and compatible topology/data, **not** on a specific EventData provider ID. On Windows, the current single-node development fallback uses `SimulatedAntenna`, so both `config/application.yml` and `config/development-simulation.yml` can advertise the capability. Both select the same `reference` EventData mappings; the latter uses an isolated engineering LogBook. A physical-antenna runtime must not expose simulated-tag control unless that capability is explicitly composed. Verify the actual advertised values in the Engineering Client **API** tab after Events reaches LIVE.

## Application API
- `timing.log(text)`: append to script Output.
- `print(...)`: append tab-separated arguments to Output.
- `timing.sleep(ms)`: interruptible sleep for 0–60000 milliseconds.
- `timing.simulate(regId, profile)`: send one simulated passage for selected TimingNode via IF-03, e.g. `timing.simulate("A0100", "simple")`. Profile defaults to `normal`.
- `timing.batch{count=10, from=1, to=2000, order="linear", seed=1, intervalMs=500, profile="normal"}`: send repeatable batch. `count` is required (1–10000); remaining values shown are defaults. Order: `linear` or seeded `random`. Profiles: `simple`, `normal`, `edge`. Intervals: 0–60000 ms. Generated IDs: `ANNNN`.

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

See [../examples/batch.lua](../examples/batch.lua) for the maintained example.

## Boundaries
One script at a time; GUI and Lua batches cannot run simultaneously. A script captures the selected node at start; disconnect or node change stops it. Simulation calls require the advertised capability. Maximum script file size: 128 KiB. Stop cannot undo already accepted commands.

The interpreter does not expose `io`, `os`, `package`, `require`, `luajava`, `loadfile`, `dofile`, Java reflection, or arbitrary process/file/network access. The restrictions are not a security sandbox: run trusted engineering scripts only.

Multi-system connection management, open/close and state assertions are **not yet supported Lua API functions**.
