# Engineering Central boundary

This standalone Maven module owns the SI-02 Central System Simulator: IF-06 state,
Central protocol actions and Central-specific JavaFX presentation.

It intentionally has **no dependency on the ordinary Engineering Client HTTP/API
implementation**. Production code in this module may use shared IF-05/IF-06 contracts
and the narrow `CentralTransport` outbound port only.

The desktop application adapts `CentralTransport.send(UpstreamData)` to the existing
`role=upstream` WebSocket session. HTTP/API helpers such as `ApiClient` are outside
this module and therefore unavailable at compile time.
