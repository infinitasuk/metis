# Personal fleet presets for interpreted rules

Implemented 2026-10-09 on the player-selected roster contract in
[RULES_ROSTER_SELECTION.md](RULES_ROSTER_SELECTION.md).

## User workflow

Each selectable seat roster in `/rules` now has **Save fleet**, **Load fleet**,
**Rename fleet** and **Delete fleet** controls. A preset saves one fleet, so it can
be reused for either an eligible human or CPU seat in a later match.

- Fleet names are trimmed, 1–100 characters, and case-insensitively unique per user.
- Saved fleets are private to their owner, including when another user is an admin.
- Saving validates the current selection against the chosen published definition.
- Loading validates against the currently selected definition and target seat.
- A successful load populates an editable draft and displays its authoritative
  total. Changed revisions also show the original saved total.
- Failed or stale loads leave the user's edited draft intact.
- Rename changes the name only. Save another named preset to retain a different
  composition or source revision.

Creation of a match still uses the ordinary roster-selection request and shared
validator. Its immutable starting checkpoint captures the chosen units and costs.
Renaming or deleting the preset later has no effect on that match.

## Stored revision and cross-revision reuse

Each preset retains its source definition ID, source seat, policy, ordered unit
codes, definition key/revision/fingerprint, source-backed unit profiles, provenance
and total cost. This snapshot makes saved fleets readable even when their original
definition can no longer be compiled by a future runtime.

Loading in the exact same definition uses the saved unit codes and revalidates
against the target seat's policy. For another definition or revision, the adapter
matches each unit by the provenance tuple **repository, catalogue file, entry ID**.
This permits catalogue-generated unit codes to change without guessing from names.
All matches must be unique. Missing or ambiguous source identities reject the load.

The target definition supplies current costs and eligibility. The saved policy
must be allowed for the target seat, and the shared validator enforces budget,
faction, count and uniqueness limits. A changed cost can make a previously valid
fleet invalid. Loading never rewrites the saved snapshot or silently alters an
existing session. A successful cross-revision load is visibly marked in the UI.

## API

All routes are under `/api/v1/rules/fleet-presets` and require authentication.
Responses use the existing no-store and authenticated-identity headers.

| Method / route | Purpose |
| --- | --- |
| `GET /` | List only the current user's presets |
| `GET /{id}` | Read an owned preset and its original snapshot |
| `POST /` | Validate and save a named fleet; HTTP 201 |
| `POST /{id}/load` | Read-only remapping/validation for a target definition and seat |
| `PATCH /{id}` | Rename using an expected edit version |
| `DELETE /{id}?expectedVersion=N` | Delete using an expected edit version; HTTP 204 |

Save example (use the actual published definition ID and unit codes from roster
metadata):

```json
{
  "name":"Imperial patrol",
  "definitionId":7,
  "seat":0,
  "policy":"imperial",
  "units":["<TIE code>","<TIE code>"]
}
```

The saved view contains `id`, `name`, `version`, `definitionId`, `seat`,
`selection` (`policy`, `units`) and `snapshot` (`definition`, `revision`,
`fingerprint`, `points`, source-backed `units`). Owner IDs and client-authored
costs are not accepted in requests.

Load example:

```json
{"definitionId":7,"seat":1}
```

The response contains `valid`, `code`, `revisionChanged`, `savedPoints`, target
`points`, target `budget`, target `fingerprint` and a validated `selection`.
Invalid loads return `selection:null`; they do not partially apply a fleet.
This endpoint performs no writes. Pass a successful selection in the target
session's ordinary `rosters` array when creating the match.

Rename example:

```json
{"name":"Reserve patrol","expectedVersion":0}
```

Renaming increments `version`. Both rename and delete reject stale versions with
HTTP 409 / `STALE_PRESET`. A duplicate name returns 409 / `PRESET_NAME_EXISTS`.
Unknown or unowned IDs return 404 / `PRESET_NOT_FOUND` for every operation.
Invalid fields/names return HTTP 400. Saving an invalid fleet returns HTTP 422
with its `ROSTER_*` code. The definition must be published and support selectable
rosters. Missing or ambiguous source matches during load report
`PRESET_UNIT_UNAVAILABLE` or `PRESET_UNIT_AMBIGUOUS`; ordinary validation failures
retain the shared roster error code.

## Database and client behavior

V42 adds `game_instance.rules_fleet_preset`, owner-filtered read functions and
create/rename/delete procedures. Creation checks source identity and snapshot/code
and total consistency. An immutability trigger protects owner, definition, source
seat, policy, unit codes, original snapshot and creation time. Rename/delete use
an optimistic edit version within the owner boundary. Database uniqueness handles
concurrent attempts to save the same name.

The browser retains the entered name and fleet draft after rejected requests.
Preset controls are disabled while a mutation is in flight, and load replies are
discarded if the definition, selected preset or roster changed meanwhile.
Stale edit errors refresh the preset list without retrying a destructive action.
Authentication changes clear private preset names/controls from the page; names
are rendered as text rather than HTML.

## Verification and local deployment

`mvn -B -Prules-full clean verify` passed on 2026-10-09:

- **345 Java tests**: 303 core, 37 API, 5 web, with no failures or skipped tests.
- V42 installation and repeated startup/checksum validation.
- All registered SQL/API/Chromium, CPU, restart/replay and cleanup suites.
- `fleet-presets.cjs` verifies privacy (including admin), concurrent rename,
  stale delete, transactional failure rollback, snapshot immutability,
  source-identity remapping, changed costs and session independence.
- Browser checks cover save/reload/load/rename/delete, literal markup in names,
  a delayed load racing a roster edit, creation from a preset and identity changes.

Acceptance report:
`target/rules-suite/metis-rules-1791547028802-dff027a5/summary.json`.

The local Docker app was rebuilt from the worktree and started at
`http://localhost:8080/rules`; the existing database upgraded from V40 to V42.
The selectable training pack is imported with local entries 7–9. Deployment
metadata identifies that tested local build as `58e957e-dirty`. Hosted CI release
qualification is tracked in `https://github.com/infinitasuk/metis`.
