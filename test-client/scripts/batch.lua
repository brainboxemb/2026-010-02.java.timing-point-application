-- Event Timing SI-02 Engineering Client example
-- Runs locally in the desktop client, not on the Timing Application.
-- Requires Events LIVE, selected TimingNode and simulation capability.
-- Registration IDs are generated as A0001, A0002, ...

timing.log("Starting a repeatable batch")
timing.batch {
  count = 10,
  from = 1,
  to = 100,
  order = "random",    -- "linear" or "random"
  seed = 42,           -- repeatable random sequence
  intervalMs = 500,    -- gap between simulated registrations
  profile = "normal"   -- "simple", "normal" or "edge"
}
timing.log("Finished")
