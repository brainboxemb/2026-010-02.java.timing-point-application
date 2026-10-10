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
| Normal | `TT-A-0001-1`, `TT-A-0001-2` | `RT-A-0001` | `0001` |
| Reserve | `TT-R-0001-1`, `TT-R-0001-2` | `RT-R-0001` | determined by reference-data lookup |

Both tags of one pair refer to the same RegistrationId. The `-1` / `-2`
suffix identifies the physical tag, **not** another team or registration.
The `A` in `RT-A-0001` means normal participant, **not TimingNode A**.

The current VC-ST1-002 and VC-ST1-005 through VC-ST1-008 black-box flows call
the IF-03 engineering `auto-reg` operation, which accepts **RegistrationId**.
They verify registration commit and LogBook behaviour, **not** the RFID
TagId-to-RegistrationId mapping or TeamId resolution. Those require an
RFID/tag-observation verification scenario and reference-data checks.

The two-node tests intentionally register two different normal participants,
`RT-A-0001` and `RT-A-0002`, to prove records stay with their intended
TimingNode and physical LogBook.
