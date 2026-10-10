# Player-selected rosters and immutable session capture

Implemented 2026-10-09 on the sourced-cost/shared-validator foundation in
[RULES_SOURCED_POINTS.md](RULES_SOURCED_POINTS.md).

## Playable slice

The new `armada-squadron-rosters-v1` recipe creates standard, close-engagement and
short-battle entries. Each of two seats selects an Imperial or Rebel roster and
one to three generic squadrons within a **26-point training allowance**:

- TIE Fighter Squadron: 8 points, so up to three fit (24 points).
- X-Wing Squadron: 13 points, so up to two fit (26 points).
- Both seats may choose the same faction. Ownership, rather than faction identity,
  determines opposing units.

Prices and provenance come from the V41 source set. This format supports these
two generic profiles, separate deployment slots, alternating squadron activations,
and an optional single-die Swarm reroll when another friendly squadron engages the
defender. Elimination requires destroying the entire opposing roster. All Rebel
units have Escort, so no unprotected alternative target exists; Bomber has no ship
target. Timing, choices, unit construction and effects are authored in the recipe.

The allowance and movement scale are training policies. Standard fleet limits,
ships, aces, upgrades, autonomous CPU roster building,
and calibrated rulers remain subsequent work. CPU seats can play any roster
selected for them through the same validated creation request.

Personal saved fleet presets are now implemented; see
[RULES_FLEET_PRESETS.md](RULES_FLEET_PRESETS.md) for ownership, revision-aware reuse,
rename/delete and captured-session independence.

The follow-on [mixed squadron pack](RULES_COUNTER_SQUADRONS.md) adds A-Wings and
TIE Interceptors, sourced keyword capture and Counter/Escort interactions. This
original two-profile pack keeps its captured scope.

## Browser

In `/rules`, import the new recipe as an administrator using the published
catalogue revision ID. Select a resulting catalogue entry, then:

1. Choose each seat's faction and add/remove unit entries.
2. Use **Validate rosters** to receive authoritative per-seat totals and errors.
3. Create the session. Creation revalidates the selected rosters server-side.
4. Start and play the session. **Captured starting rosters** shows the original
   selections throughout play, reload and historical playback.

Draft selections are cached in session storage by user and definition fingerprint.
Changing a roster clears its old validation result. Responses arriving after an
edit or definition switch are discarded, and changing seat/setup inputs during
creation preflight requires a fresh submission. Invalid selections remain editable.

## API

Import:

```text
POST /api/v1/admin/rules/catalogue/imports
{"gameVersionId":4,"pack":"armada-squadron-rosters-v1"}
```

Use the actual revision ID. Inspect available units and policies:

```text
GET /api/v1/rules/catalogue/{entryId}/roster
GET /api/v1/rules/definitions/{definitionId}/roster
```

Published-definition reads require authentication. Draft definitions are not
exposed by the second route. Metadata includes `available`, `selectable`, the
definition fingerprint, sourced `units`, and zero-indexed `seats`. Each seat has
default unit codes and `choices`: allowed policy codes, faction names, budgets,
count bounds and eligible unit codes. Fixed older packs return `selectable:false`.

Preflight uses either corresponding `/roster/validate` route:

```json
{"seat":0,"policy":"rebel","units":["<X-wing code>","<X-wing code>"]}
```

The optional `policy` field defaults to that seat's default policy for existing
clients. Rules, points, factions and provenance are always read from the selected
immutable definition. Standard invalid rosters return `valid:false` with the
shared `ROSTER_*` code and totals. A policy outside the seat's choices rejects.

Create through either the catalogue route or `/api/v1/rules/sessions`:

```json
{
  "visibility":"private",
  "seats":[{"playerId":"alice","userId":1},{"playerId":"bob","kind":"CPU"}],
  "viewers":[],
  "rosters":[
    {"policy":"rebel","units":["<X-wing code>","<X-wing code>"]},
    {"policy":"imperial","units":["<TIE code>","<TIE code>","<TIE code>"]}
  ]
}
```

Use codes returned by roster metadata. Include `definitionId` for the direct
session route; catalogue creation supplies its pinned definition itself.
`rosters` is required for selectable definitions and must contain exactly one
entry per seat, in seat order. It is rejected for fixed-roster/unsupported
definitions. The creator chooses the starting rosters for the configured human
and CPU seats; gameplay remains controlled by the assigned seat owners.

Creation rejects unknown units/policies, wrong factions, empty/oversized or
over-budget lists, uniqueness violations, malformed fields and client-supplied
costs. Failed creation leaves no session or seat records. The direct session route
uses the same validation, so it cannot bypass the catalogue boundary.

Inspect the permanent selection:

```text
GET /api/v1/rules/sessions/{sessionId}/roster
```

This uses normal session-view permissions. It returns the captured definition
identity, revision and fingerprint, plus each seat's player ID, policy, units,
costs, allowance, provenance and distinct entity IDs. It returns no roster for
definitions without roster metadata. Public training rosters are visible to
permitted spectators; unrelated users cannot read private sessions.

## Persistence and rule contract

The published definition captures the catalogue profile dictionary and source
costs. Creation maps validated unit codes to dictionary indices and binds only
two declared typed fields in the version-zero checkpoint:

- `selectedRosters`: `list<list<integer>>`, in seat and unit order.
- `selectedPolicies`: `list<string>`, in seat order.

`rosterPolicyChoices` declares allowed policies per seat, `rosterPolicies` supplies
defaults, and `rosterEntityIds` supplies bounded distinct instance IDs. The recipe's
setup procedure creates the selected instances from the captured dictionary and
validates each completed roster through `scenario.roster` again. Repeated generic
profiles receive different instance IDs and deployment slots.

`CatalogueRosters` validates public metadata and calls `RosterValidator`; the
transport never accepts arbitrary globals, costs, resources, IDs or checkpoints.
The interpreter continues to execute ordinary language-v1 procedures. Existing
compiled/runtime contracts and older captures remain supported.

The existing atomic session-create procedure writes the initial checkpoint,
captured definition and seats together. Existing database immutability triggers
protect both `initial_checkpoint` and `captured_definition`. No additional mutable
roster table or post-creation edit endpoint is introduced. The roster read endpoint
reconstructs from that **initial** checkpoint and captured dictionary, so damage,
new publications, runtime progress and replay cannot change the starting roster.

## Verification

Required acceptance is registered as `selected-rosters` in the full runner.

- `SelectableRostersTest`: counts/faction swaps, atomic rejection, exact costs,
  distinct identities, independent deployment slots, checkpoint restoration,
  Swarm authorization/single reroll and whole-roster elimination.
- `selected-rosters.cjs`: both creation routes, read-only validation, rejection
  without partial rows, SQL capture immutability, privacy, restart, newer cost
  revisions, human/CPU completion, terminal/version-zero replay, browser edits,
  over-budget errors, draft reload and stale-setup rejection.
- Regression command: `mvn -B -Prules-full clean verify`.

Local qualification on 2026-10-09 passed: **340 Java tests** (303 core, 32 API,
5 web), coverage gates, benchmark recording, every registered SQL/HTTP/Chromium
suite, selected human/CPU roster games, and both success/failure cleanup checks.
The final acceptance report is
`target/rules-suite/metis-rules-1791543951390-57c318f7/summary.json`.

Hosted CI release qualification remains a separate pending release step.
