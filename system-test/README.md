# System-test fixtures

Black-box verification runs the packaged SI-01 application using YAML fixtures
under `src/test/resources/configuration/`. Runtime ports are supplied by
`TestApplicationConfigFactory`; the application's `{NodeId}` and `{SystemId}`
path variables remain unchanged so the application resolves them itself.

## Participant identifiers

Use the shared `TestParticipantIds` helper instead of inventing IDs in each
test. Numbering starts at `0001`; `0000` is invalid.

| Kind | TagId (two physical tags) | RegistrationId | TeamId |
| --- | --- | --- | --- |
| Normal | `A0001A`, `A0001B` | `A0001` | `0001` |
| Reserve | `R0001A`, `R0001B` | `R0001` | determined by reference-data lookup |

Both tags of one pair refer to the same RegistrationId. The `A` / `B` suffix
identifies the physical tag, **not** another team or registration.
The `A` in `A0001` means normal participant, **not TimingNode A**.

The current VC-ST1-002 and VC-ST1-005 through VC-ST1-008 black-box flows call
the IF-03 engineering `auto-reg` operation, which accepts **RegistrationId**.
They verify registration commit and LogBook behaviour, **not** the RFID
TagId-to-RegistrationId mapping or TeamId resolution. Those require an
RFID/tag-observation verification scenario and reference-data checks.

The two-node tests intentionally register two different normal participants,
`A0001` and `A0002`, to prove records stay with their intended
TimingNode and physical LogBook.
