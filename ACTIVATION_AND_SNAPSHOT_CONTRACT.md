# Captured battle definitions and configurable activations

Delivered 2026-09-19: F04 and the first F05 implementation slice.

## Snapshot policy

Every newly started battle captures the complete `GameDefinition`, including unit
profiles and its `TurnStructure`, in `game_instance.session_definition_snapshot`.
The record contains a snapshot schema version, rules implementation version and
SHA-256 checksum of the exact serialized definition. Captures are immutable and
are written in the same transaction as starting the battle.

Actions, legal-action discovery, CPU decisions and the battle inspector use the
captured definition. They do not reload mutable catalogue profiles for that battle.
Unsupported versions, checksum failures and revision mismatches prevent further
actions. Existing state and event history remain readable.

Battles created before capture support are `LEGACY_UNPINNED`. Their original
maximum resources and source rules cannot be recovered reliably from remaining
resources alone. They are therefore historical/read-only, including to the CPU.
The UI offers **Restart as a new battle**, which:

1. Creates a separate session owned by the requesting editor.
2. Copies players, stored rosters, board selection and the original setup options.
   New states retain arbitrary game-specific setup parameters under
   `values.initialSetupOptions`; legacy states use a compatibility allowlist so
   runtime counters/results are not mistaken for setup parameters.
3. Reconstructs formation coordinates from recorded deployment events where
   available, including stored CPU engine-unit mappings.
4. Starts at deployment with a freshly captured definition and fresh resources.
5. Leaves the source session's state/events unchanged.

Restart requires the same published catalogue revision so unit/faction IDs remain
meaningful. If the published revision changed, construct a new roster for that
revision rather than implicitly translating IDs. No in-place repair or automatic
healing is performed. The policy is a clean restart, not a claim that historical
profile errors and combat damage have been distinguished.

Endpoints:

- `GET /api/v1/sessions/{id}/compatibility`
- `POST /api/v1/sessions/{id}/restart` (existing session edit permissions apply)

Snapshot schema: `1`. Supported rules contracts: `legacy-v1` for explicitly captured
legacy definitions and `activation-v1` for configured definitions. New sessions
started through the battle API require a configured turn structure. F05 introduced
event schema version `2`; its activation metadata and changed-unit
records allow the client replay to reproduce phase, readiness and resource transitions.
F06 advances new sessions to event schema `3` with player projections and response
windows; see [MULTIPLAYER_CONTRACT.md](MULTIPLAYER_CONTRACT.md).

## Sequencing definition

`game_version.turn_structure` is revision-owned configuration. Draft copies inherit
it; published/retired structures are immutable. Admin endpoints:

- `GET /api/v1/admin/definitions/{id}/turn-structure`
- `PATCH /api/v1/admin/definitions/{id}/turn-structure`

The PATCH body is the full structure. Java record validation rejects duplicate
codes, invalid budgets, reserved control actions and inconsistent phase modes.
New game definitions have no implicit turn structure.

Example, using neutral classifications and action names already implemented by
the current mechanical adapter:

```json
{
  "deploymentRequired": true,
  "phases": [
    {
      "code": "engagement",
      "label": "Unit activations",
      "mode": "ALTERNATING_UNITS",
      "classifications": ["Vehicle"],
      "groupSize": 1,
      "steps": [
        {
          "code": "weapons",
          "label": "Weapons",
          "actions": ["attack_unit"],
          "minimum": 0,
          "maximum": 2,
          "distinctParameter": "arc"
        },
        {
          "code": "movement",
          "label": "Movement",
          "actions": ["move_unit"],
          "minimum": 1,
          "maximum": 1,
          "editableMovement": true
        }
      ]
    }
  ]
}
```

Available sequencing modes:

| Mode | Behavior |
| --- | --- |
| `MANUAL` | Explicit phase completion, no entity effects in this first slice |
| `ALTERNATING_UNITS` | Finish an entity before choosing another; pass after `groupSize` entities, or sooner if none remain |
| `PLAYER_PHASE` | A player completes eligible entities before control passes; later phases receive independent budgets |
| `ALTERNATING_ACTIONS` | One configured action per player turn, without requiring an active entity; usable by a grid-attack effect module |

Eligibility uses configured classification names; an empty list allows any living
entity. Exhausted players are skipped. A phase completes only when its eligible
entities/player actions are finished. Empty entity phases are skipped with a
bounded traversal. `refreshResourcesAtEnd` selects the current resource-refresh
adapter; a fully general resource/effect refresh system remains F18.

## Activation state and actions

`GameState.values.activation` records:

- Active unit ID and current step index.
- Uses consumed per named step.
- Distinct action sources used per step.
- Completed units and completed players.
- Entities completed in the current activation group.

Supported controls:

- `activate_unit`: explicitly claim an eligible entity, including when electing to
  skip an optional action. The first effect may also claim it automatically.
- `advance_step`: move to the next step after satisfying the current minimum.
- `end_activation`: commit and finish the active entity after all minimums are met.
- `advance_phase`: complete a manual phase/deployment.
- `end_turn`: compatibility alias for finishing the active entity in configured
  entity phases, not a way to bypass remaining activations.

Optional earlier steps may be skipped by choosing a later effect. Earlier steps
cannot be revisited after advancing. Repeated action types in separate steps can
be separated explicitly with `advance_step`.

Legal actions include structured `parameters` (unit/target IDs) in addition to
their input schema and description. The UI filters targets and movement controls
for the selected entity rather than combining every unit's available targets.

Movement remains editable within the current movement step and retains the
original movement allowance. Reset removes the movement use and restores the
pre-move pose/speed, preserving earlier attacks and their outcomes. A move-or-attack
choice step cannot turn a completed attack into a movement action. Finishing the
entity locks its actions for that phase. The UI distinguishes **Finish activation**
from **End phase** and displays the active entity/step.

`ActivationEngine` takes an action-effect callback. It is responsible for ordering,
authorization, budgets and handover; movement, attack, geometry and custom grid
effects are separate mechanics. The production adapter still uses the existing
movement/combat implementations. Synthetic grid tests exercise sequencing through
a `fire_cell` callback; a complete grid game's hidden placements and hit-resolution
module are not delivered by this slice.

## Current battle configuration

Migration V33 expresses the previous catalogue's phase/classification identifiers
as configuration data. The sequencer contains no game-name checks. New battles
now alternate one entity in the first combat group and two in the next, with ordered
attack/movement or move-or-attack steps as configured. The CPU uses the same legal
actions and must finish an entity before continuing.

Still pending: per-entity command queues/effects, pass-token effects, complete
attack/defense timing, canonical maneuver geometry, roster legality and official
scoring. Command/manual phases remain transitions until their effect modules are
implemented. Existing mechanical simplifications have not become rules-complete
merely because activation order is now configurable.

F06 supplies private per-player phase choices and response windows as a foundation
for those command and combat modules; see `MULTIPLAYER_CONTRACT.md`.

## Verification

- Java tests: alternating entities, groups, exhausted players, required actions,
  distinct sources, editable movement/reset, step ordering, whole-player phases
  and alternating grid actions without units.
- Snapshot tests: serialization, original-data retention, checksums, unsupported
  schema/rules versions and revision mismatches.
- `database/src/test/sql/session_snapshots.sql`: immutable captures and sequencing
  metadata; fixture records roll back.
- Live API checks: two different attacks followed by movement/reset, alternation,
  phase completion, CPU attack/movement/handover, pinned-definition loading and
  restarting without changing the source history. Temporary test sessions removed.
