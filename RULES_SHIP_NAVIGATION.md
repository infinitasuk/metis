# Navigate-aware corvette training

`armada-ship-navigation-v1` adds command-driven movement to the CR90 Corvette A and
Raider I-class Corvette. The recipe extends `armada-ship-maneuvers` through captured
JSON overrides, using the existing `queues:1`, `resources:1`, `maneuver:1`, `space:1`
and `scenario:1` contracts. Command effects and timing are definition-owned.

## Play

An administrator imports a published Armada revision with:

```json
POST /api/v1/admin/rules/catalogue/imports
{"gameVersionId":4,"pack":"armada-ship-navigation-v1"}
```

Use the installation's published revision ID; `4` is the local development revision.
The catalogue offers **Navigate-aware corvette training — Standard** (six rounds)
and **Short course** (three rounds). Each seat chooses a faction and one sourced
44-point ship. Personal presets from the maneuver pack can be revalidated and loaded.

1. **Deploy:** activate the ship, choose its pose and starting speed, then Execute.
   Starting speed must be a printed speed, 1–4 for these profiles.
2. **Plan orders:** both players privately fill their command queues. The current
   ships have command value 1, so supply a list such as `["navigate"]`. Offered
   inputs provide complete suggestions. Execute submits and locks the orders.
3. **Reveal:** activate a ship and Execute **Reveal command**. A Navigate dial gives
   a **keepDial / bankToken** decision. Banking is available only at this point.
4. **Determine course:** choose **Maneuver**, **Navigate dial**, **Navigate token**,
   or **Navigate dial and token**. Select speed, tool side and joint yaw, then Preview.
   The preview shows the final footprint and resource changes. Execute commits the
   payment, speed and pose together.

The course ends with `maneuver-training-complete` and no winner. CPU seats use the
same projected offers and decisions. The baseline CPU keeps revealed Navigate
dials and prefers available command-paid movement; it is a legal baseline policy.

## Supported rules and boundaries

| Input/payment | Speed change from the persistent speed dial | Yaw allowance |
| --- | --- | --- |
| Maneuver | None | Printed speed-chart row |
| Navigate dial | Up to one in either direction | Up to one extra click at one joint |
| Navigate token | Up to one in either direction | Printed row; no bonus |
| Dial and token | Up to two in either direction | The dial's single extra click |

- Speed remains between zero and the profile maximum. Speed zero is available after
  deployment and executes a stationary maneuver.
- A joint cannot exceed two clicks in either direction. The dial's extra click may
  be used at **one** joint across the maneuver, not once at each joint. The printed
  row is selected using the resulting speed.
- Dial/token payment is chosen before effects resolve and counts as one Navigate
  resolution. `resources.once(...,period:"ROUND")` enforces one resolution per ship
  per round. A resolution with no speed/yaw effect is allowed and still consumes its
  payment and allowance.
- A revealed dial can be banked immediately. The token can be spent in the same
  activation or retained across rounds. Only one Navigate token can be held; a newly
  banked duplicate is discarded while the existing token remains. Since only Navigate
  tokens are supported and both command values are positive, this also respects the
  total command-token capacity.
- Unspent revealed dials are discarded after movement. The public `revealedCommand`
  remains visible for the round and clears when the next orders are submitted.
- Planning fills the uncommitted suffix of the FIFO queue up to the captured command
  value. The interpreter also verifies longer synthetic queues in conformance tests:
  refilling appends orders without rewriting the committed prefix.

The four standard dial names can be planned and revealed. Repair, Squadron and
Concentrate Fire effects are explicitly declined in this movement-only course;
banking/resolving their tokens is unsupported. Ship combat, shields, upgrades,
obstacles, official initiative/scoring and full fleet formats remain separate slices.

The inherited geometry is the pinned **TTS-coordinate training calibration** described
in [RULES_SHIP_MANEUVERS.md](RULES_SHIP_MANEUVERS.md). Invalid final overlaps and
off-board destinations are rejected. Automatic overlap shortening, collision damage,
off-board destruction and physical-ruler certification are outside this pack.

## Source and persistence contract

The rules reference is version **1.6.0**, captured with URL and SHA-256:

- URL: `https://cdn.svc.asmodee.net/production-amgcom/uploads/2025/01/SWM_Rules_Reference_Guide_1.6.0_web.pdf`
- SHA-256: `c48ee9e97676aec77e998b58d36fe88e3024740a7206060352f64531c472e7fb`
- Sections: Command Dials/Phase/Tokens (p3), Commands/Navigate (p4), Setup (p16),
  Ship Activation/Movement (p17), Speed (p19), Yaw (p22).

Both source profiles have **Command 1**. They retain the BSData commit/file hashes,
44-point costs and TTS charts/calibration from the earlier ship pack.
`tools/verify-armada-ships.ps1` prints the source Command characteristic along with
the other profile values and hashes.

V45 adds `fn_rules_catalogue_command_source(version,names,costSet,maneuverSet)`.
It layers command capture over the existing maneuver projection, requiring exactly
one Command characteristic matching the profile, catalogue file and revision tied
to the sourced points. The capture includes the raw value, characteristic provenance
and source-file identity. Missing/ambiguous values become null and fail import;
the adapter checks the raw value against the integer rating and validates provenance.
The recipe bounds ratings to its declared four-slot queue fixture capacity.

Existing migrations, source projections, published packs and captured sessions retain
their contracts. The new recipe, rules-reference metadata and command data contribute
to a new immutable source/definition fingerprint. Compiler/runtime version constants
remain compiler5/runtime4; the module signatures are unchanged.

## Shared controls, previews and visibility

An action's existing `maneuver` hint now optionally includes:

```json
{
  "speeds":{"list":{"elementType":"integer","items":[{"get":{"entity":{"var":"entity"},"field":"speed"}}]}},
  "note":"Maintain current speed and printed yaw."
}
```

`speeds` is a typed list expression over the active entity and acting seat. Runtime
projection checks distinct, valid chart rows; hidden dependencies fail closed.
`note` is bounded definition-authored text. Both fields are omitted from older DTOs
when absent. The generic browser renders these values without game-name branches.

For dial-paid movement, controls show each joint's capped potential limit. The note
explains the one-joint budget; the shared action validator checks it over the entire
yaw list. Manual HTTP inputs receive the same checks as browser/CPU suggestions.
Available suggestions are bounded, so the client can also submit a manual plan.

The owner alone sees `orders`, including through history. Reveal emits a public
`game.commandRevealed` event. The subsequent banking decision is public because the
dial has already been revealed; only its owning seat may respond. Spectators and
administrators cannot operate a player's seat or inspect its unrevealed queue.

Navigate actions preview their full deterministic procedure on an isolated visible
snapshot, including resource payment, once-per-round markers, geometry and phase
completion. Invalid plans commit nothing. Successful commits use the existing atomic
checkpoint/delta/receipt transaction; replay applies recorded results. Queues and
resources allocate mechanical state but these rules consume no random draws.

Public events include `game.commandRevealed`, `game.commandDeclined`,
`game.navigateTokenBanked` (with `duplicateDiscarded`), `game.navigationResolved`
(old/new speed and dial/token flags), and the existing `rules.maneuvered` pose event.
HTTP preview deliberately exposes safe reason codes rather than internal diagnostic
messages. Internal conformance tests check the detailed rule labels as well.

## Verification

Use Node **24.21.0** from `.nvmrc`, then:

```text
mvn -B "-Prules-full,rules-mutation" clean verify
```

- `NavigationConformanceTest`: starting speeds, private-view/CPU invariance, mandatory
  reveal, persistent speed, dial/token/combined limits, yaw caps and budget, same-turn
  token use, duplicate banking, no-effect resolution, once-per-round enforcement,
  rollback, FIFO refill, restore/replay and complete CPU courses.
- `NavigationCatalogueTest`: source ratings/provenance, data-driven capacity,
  supported-profile bounds, selected rosters and older-pack compatibility.
- `navigation_commands.sql`: strict command projection, source identities, missing
  sources and preservation of the earlier projection.
- `navigation-commands.cjs`: authenticated private planning/history, public reveal,
  preset reuse, bank-decision recovery, actual process kill inside a combined-payment
  transaction, exact retries, role-specific terminal replay, CPU completion, browser
  draft/decision retention, dynamic controls, resource previews and lost acknowledgements.

The existing suites remain mandatory. CI uses Ubuntu 24.04 and Windows, the pinned
Node runtime, and Node-24-based checkout/setup/artifact actions.

### Local qualification and delivery — 2026-10-11

- Full acceptance (`mvn -B -Prules-full verify`) passed **378 Java tests**:
  323 core, 50 API and 5 web, with zero failures/errors/skips and the coverage gates met.
- All **7 SQL** and **15 API/browser integration** suites passed, including the
  Navigate transaction-time process kill, role-specific replay and intentional-failure
  cleanup checks. Report: `target/rules-suite/metis-rules-1791735519797-278929b3/summary.json`.
- The clean-build focused mutation gate detected **653 / 757 mutants (86%)**;
  no targeted mutants lacked coverage. Versioned benchmark measurements were also recorded.
- Local Compose deployment (`cb6e874-dirty`) applied **V45**. Catalogue entries and
  definitions **15** (Standard) and **16** (Short course) are available at
  `http://localhost:8080/rules`.
- The deployed Chromium smoke banked a token, retained it into the next round, then
  previewed and committed combined dial/token payment to reach speed 4. Reload and
  replay agreed; the temporary smoke session was removed. Screenshot:
  `target/navigation-local.png`. Existing 14 catalogue entries were preserved.
- All five hosted jobs passed for feature commit `662c7a7` in
  [run 38156147156](https://github.com/infinitasuk/metis/actions/runs/38156147156),
  including full acceptance in 6m40s. Subsequent CI-maintenance results are recorded
  by GitHub Actions for their pushed revisions.
