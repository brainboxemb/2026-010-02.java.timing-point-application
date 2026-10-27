-- Example Engineering Client reconnect scenario.
-- client.reset() clears SI-02 Central simulator state and disconnects client boundaries.
client.reset()

assert(client.api_available(), "SI-01 API is not reachable")

client.central_connect()
client.wait("central", true, 5000)
print("Central connected")

local connected = client.status()
assert(connected.eventsConnected)
assert(connected.centralConnected)

client.central_disconnect()
client.wait("central", false, 5000)
assert(client.events_connected())
print("Central disconnected; ordinary Events remains connected")

client.central_connect()
client.wait("central", true, 5000)
print("Central reconnected")
