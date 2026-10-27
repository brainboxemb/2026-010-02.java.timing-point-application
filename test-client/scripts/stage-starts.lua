-- Example Central-owned StageStartTimes configuration.
-- This script does not require a selected TimingNode.
local start = timing.now(-600) -- ten minutes before current UTC time

print("Stage 1 start reference: " .. start)

central.stage_start_range{
    stage = 1,
    prefix = "A",
    from = 1,
    to = 100,
    start = start,
    intervalMs = 0
}
